package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

public class TransactionalSqlSessionLazyLoadingTest {
    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(LazyRow.class, ParentRow.class, ObserveLazy.class, FailOnce.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:lazy_session_lifecycle;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.datasource.username", "sa")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "enabled")
            .overrideConfigKey("quarkus.mybatis.transaction-factory", "MANAGED");

    @Inject
    SqlSessionFactory factory;
    @Inject
    TransactionManager manager;

    @AfterEach
    void cleanup() throws Exception {
        if (manager.getTransaction() != null) {
            int status = manager.getStatus();
            if (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK) {
                manager.rollback();
            } else {
                manager.suspend();
            }
        }
        manager.setTransactionTimeout(0);
    }

    private Configuration configuration() {
        Configuration c = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        c.setDefaultExecutorType(ExecutorType.REUSE);
        c.setLazyLoadingEnabled(true);
        c.setAggressiveLazyLoading(false);
        c.setLazyLoadTriggerMethods(Set.of());
        ResultMap integers = new ResultMap.Builder(c, "integers", Integer.class, List.of()).build();
        c.addMappedStatement(new MappedStatement.Builder(c, "plain", new StaticSqlSource(c, "SELECT 42"),
                SqlCommandType.SELECT).statementType(StatementType.STATEMENT).resultMaps(List.of(integers)).build());
        c.addMappedStatement(new MappedStatement.Builder(c, "prepared", new StaticSqlSource(c, "SELECT 42"),
                SqlCommandType.SELECT).statementType(StatementType.PREPARED).resultMaps(List.of(integers)).build());
        c.addMappedStatement(new MappedStatement.Builder(c, "parent", new StaticSqlSource(c, "SELECT 1 AS id"),
                SqlCommandType.SELECT).resultMaps(
                        List.of(new ResultMap.Builder(c, "parent-row", LazyRow.class,
                                List.of(new ResultMapping.Builder(c, "ids", "ID", List.class).nestedQueryId("plain").lazy(true)
                                        .build()))
                                .build()))
                .build());
        return c;
    }

    @Test
    void lazyStatementMustNotBeReusedByPreparedMapper() throws Exception {
        Configuration c = configuration();
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        LazyRow row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertEquals(List.of(42), session.selectList("prepared"));
        manager.commit();
    }

    @Test
    void lazyStatementWithoutCachingExecutorMustNotBeReused() throws Exception {
        Configuration c = configuration();
        c.setCacheEnabled(false);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        LazyRow row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertEquals(List.of(42), session.selectList("prepared"));
        manager.commit();
    }

    @Test
    void lazyCursorRowsMustNotCloseTheRemainingCursor() throws Exception {
        Configuration c = configuration();
        c.addMappedStatement(new MappedStatement.Builder(c, "parents",
                new StaticSqlSource(c, "SELECT X AS id FROM SYSTEM_RANGE(1, 2)"), SqlCommandType.SELECT)
                .resultMaps(c.getMappedStatement("parent").getResultMaps()).build());
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        try (Cursor<LazyRow> cursor = session.selectCursor("parents")) {
            var rows = cursor.iterator();
            assertEquals(List.of(42), rows.next().getIds());
            assertEquals(List.of(42), session.selectList("prepared"));
            assertEquals(List.of(42), rows.next().getIds());
            assertFalse(rows.hasNext());
        }
        manager.commit();
    }

    private void addParent(Configuration c) {
        c.addMappedStatement(new MappedStatement.Builder(c, "grandparent", new StaticSqlSource(c, "SELECT 1 AS id"),
                SqlCommandType.SELECT).resultMaps(
                        List.of(new ResultMap.Builder(c, "grandparent-row", ParentRow.class,
                                List.of(new ResultMapping.Builder(c, "child", "ID", LazyRow.class)
                                        .nestedQueryId("parent").lazy(true).build()))
                                .build()))
                .build());
    }

    @Test
    void lazyCursorRowInsideAnotherMapperMustNotReuseTheCursorStatement() throws Exception {
        Configuration c = configuration();
        c.addMappedStatement(new MappedStatement.Builder(c, "cursorParent", new StaticSqlSource(c, "SELECT 42"),
                SqlCommandType.SELECT).resultMaps(
                        List.of(new ResultMap.Builder(c, "cursor-parent-row", LazyRow.class,
                                List.of(new ResultMapping.Builder(c, "ids", "42", List.class)
                                        .nestedQueryId("plain").lazy(true).build()))
                                .build()))
                .build());
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        try (Cursor<LazyRow> cursor = session.selectCursor("cursorParent")) {
            var rows = cursor.iterator();
            LazyRow row = rows.next();
            session.select("prepared", result -> assertEquals(List.of(42), row.getIds()));
            assertFalse(rows.hasNext());
        }
        manager.commit();
    }

    @Test
    void nestedLazyResultMustKeepItsOwnerAfterItsQueryExecutorCloses() throws Exception {
        Configuration c = configuration();
        addParent(c);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        ParentRow row = session.selectOne("grandparent");
        LazyRow child = row.getChild();
        assertEquals(List.of(42), child.getIds());
        manager.commit();
    }

    @Test
    void nestedLazyResultMustAlsoLoadInsideAnotherMapperInvocation() throws Exception {
        Configuration c = configuration();
        addParent(c);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        ParentRow row = session.selectOne("grandparent");
        LazyRow child = row.getChild();
        session.select("prepared", result -> assertEquals(List.of(42), child.getIds()));
        manager.commit();
    }

    @Test
    void lazyGetterAfterCompletionMustKeepMyBatisDetachedLoadingBehavior() throws Exception {
        Configuration c = configuration();
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        LazyRow row = session.selectOne("parent");
        manager.commit();
        assertEquals(List.of(42), row.getIds());
    }

    @Test
    void failedLazyQueryMustReleaseItsLeaseAndStatements() throws Exception {
        Configuration c = configuration();
        FailOnce failure = new FailOnce();
        c.addInterceptor(failure);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        LazyRow row = session.selectOne("parent");
        assertThrows(Exception.class, row::getIds);
        assertTrue(failure.failed.get());
        assertEquals(List.of(42), session.selectList("prepared"));
        manager.rollback();
        assertEquals(2, failure.closed.get(), "Both the deferred executor and its owning session must close");
    }

    @Test
    void lazyQueryMustKeepExecutorOpenUntilItReturns() throws Exception {
        Configuration c = configuration();
        ObserveLazy observer = new ObserveLazy();
        c.addInterceptor(observer);
        manager.setTransactionTimeout(1);
        manager.begin();
        Transaction actual = manager.getTransaction();
        Transaction observed = (Transaction) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { Transaction.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("registerSynchronization")) {
                        Synchronization callback = (Synchronization) args[0];
                        actual.registerSynchronization(new Synchronization() {
                            public void beforeCompletion() {
                                callback.beforeCompletion();
                            }

                            public void afterCompletion(int status) {
                                try {
                                    callback.afterCompletion(status);
                                } finally {
                                    observer.completed.countDown();
                                }
                            }
                        });
                        return null;
                    }
                    return method.invoke(actual, args);
                });
        TransactionManager observedManager = (TransactionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { TransactionManager.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getTransaction")) {
                        return observed;
                    }
                    return method.invoke(manager, args);
                });
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), observedManager);
        LazyRow row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertTrue(observer.entered.get(), "The lazy statement must be intercepted");
        assertTrue(observer.closed.get(), "Completion must close the executors after the getter returns");
    }

    public static class ParentRow {
        private LazyRow child;

        public LazyRow getChild() {
            return child;
        }

        public void setChild(LazyRow child) {
            this.child = child;
        }
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "query", args = {
                    Statement.class, ResultHandler.class }),
            @Signature(type = Executor.class, method = "close", args = { boolean.class })
    })
    public static class FailOnce implements Interceptor {
        final AtomicBoolean failed = new AtomicBoolean();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("close")) {
                closed.incrementAndGet();
            }
            if (invocation.getMethod().getName().equals("query")
                    && ((StatementHandler) invocation.getTarget()).getBoundSql().getSql()
                            .equals("SELECT 42")
                    && failed.compareAndSet(false, true)) {
                throw new SQLException("Deferred query failed");
            }
            return invocation.proceed();
        }
    }

    public static class LazyRow {
        private List<Integer> ids;

        public List<Integer> getIds() {
            return ids;
        }

        public void setIds(List<Integer> ids) {
            this.ids = ids;
        }
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "query", args = {
                    Statement.class, ResultHandler.class }),
            @Signature(type = Executor.class, method = "close", args = { boolean.class })
    })
    public static class ObserveLazy implements Interceptor {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean entered = new AtomicBoolean();

        public Object intercept(Invocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("close")) {
                closed.set(true);
            }
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("query")
                    && ((StatementHandler) invocation.getTarget()).getBoundSql().getSql()
                            .equals("SELECT 42")) {
                entered.set(true);
                assertTrue(completed.await(5, TimeUnit.SECONDS), "Expected the transaction timeout");
                assertFalse(closed.get(), "The lazy executor was closed while its query was still active");
            }
            return result;
        }
    }
}
