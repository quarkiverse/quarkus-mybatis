package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.managed.ManagedTransaction;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

public class TransactionalSqlSessionLazyCompatibilityTest {
    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(Row.class, TimeoutFactory.class, TimedTransaction.class, Timeouts.class, Decorator.class,
                            AuxiliaryTransaction.class, ReentrantPlugin.class, CursorTransactionFactory.class,
                            CursorTransaction.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:lazy_release_review;DB_CLOSE_DELAY=-1")
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
    }

    private Configuration configuration(boolean timed) {
        Environment original = factory.getConfiguration().getEnvironment();
        Environment environment = timed ? new Environment("timed", new TimeoutFactory(), original.getDataSource()) : original;
        Configuration c = new MyBatisConfiguration(environment);
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
                        List.of(new ResultMap.Builder(c, "parent-row", Row.class,
                                List.of(new ResultMapping.Builder(c, "ids", "ID", List.class)
                                        .nestedQueryId("plain").lazy(true).build()))
                                .build()))
                .build());
        return c;
    }

    @Test
    void lazyQueryMustRetainTheTransactionTimeout() throws Exception {
        Configuration c = configuration(true);
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        Row row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertEquals(Arrays.asList(3, 3), timeouts.values, "Parent and lazy query must both retain transaction timeout");
        manager.commit();
    }

    @Test
    void reuseCursorMustRetainTheOwningTransactionTimeout() throws Exception {
        Configuration c = configuration(true);
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        assertEquals(List.of(42), session.selectList("prepared"));
        ((TimeoutFactory) c.getEnvironment().getTransactionFactory()).transaction.timeout = 2;
        try (var cursor = session.<Integer> selectCursor("prepared")) {
            assertEquals(42, cursor.iterator().next());
        }
        assertEquals(Arrays.asList(3, 2), timeouts.values,
                "The cursor must inherit the owning transaction's timeout, even when the connection factory has none");
        manager.commit();
    }

    @Test
    void cursorMustKeepItsStricterTimeoutAndCloseItsOwnTransactionResources() throws Exception {
        Configuration c = configuration(true);
        CursorTransactionFactory transactions = new CursorTransactionFactory();
        c.setEnvironment(new Environment("cursor", transactions, c.getEnvironment().getDataSource()));
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        Connection connection = session.getConnection();
        assertEquals(List.of(42), session.selectList("prepared"));
        try (var cursor = session.<Integer> selectCursor("prepared")) {
            assertEquals(42, cursor.iterator().next());
        }
        assertEquals(1, transactions.cursor.closes);
        assertFalse(connection.isClosed(), "Closing the cursor must leave the owning session's connection open");
        assertEquals(List.of(42), session.selectList("prepared"));
        assertEquals(Arrays.asList(3, 1, 3), timeouts.values);
        manager.commit();
        assertEquals(1, transactions.cursor.closes);
    }

    @Test
    void originalExecutorPathRetainsTheTransactionTimeout() throws Exception {
        Configuration c = configuration(true);
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        manager.begin();
        try (SqlSession session = new SqlSessionFactoryBuilder().build(c).openSession(true)) {
            Row row = session.selectOne("parent");
            session.commit();
            assertEquals(List.of(42), row.getIds());
            assertEquals(Arrays.asList(3, 3), timeouts.values);
        }
        manager.commit();
    }

    @Test
    void unlimitedCursorTimeoutMustNotDisableTheOwningTransactionTimeout() throws Exception {
        Configuration c = configuration(true);
        CursorTransactionFactory transactions = new CursorTransactionFactory();
        transactions.cursorTimeout = 0;
        c.setEnvironment(new Environment("cursor", transactions, c.getEnvironment().getDataSource()));
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        try (var cursor = session.<Integer> selectCursor("prepared")) {
            assertEquals(42, cursor.iterator().next());
        }
        assertEquals(List.of(3), timeouts.values);
        manager.commit();
    }

    @Test
    void unlimitedOwnerTimeoutMustNotDisableTheCursorTransactionTimeout() throws Exception {
        Configuration c = configuration(true);
        CursorTransactionFactory transactions = new CursorTransactionFactory();
        c.setEnvironment(new Environment("cursor", transactions, c.getEnvironment().getDataSource()));
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        session.getConnection();
        transactions.transaction.timeout = 0;
        try (var cursor = session.<Integer> selectCursor("prepared")) {
            assertEquals(42, cursor.iterator().next());
        }
        assertEquals(List.of(1), timeouts.values);
        manager.commit();
    }

    @Test
    void customExecutorDecoratorMustNotDisableLazyStatementIsolation() throws Exception {
        Configuration c = configuration(false);
        c.addInterceptor(new Decorator());
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        Row row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertEquals(List.of(42), session.selectList("prepared"));
        manager.commit();
    }

    @Test
    void auxiliaryExecutorCreatedByPluginMustNotReplaceTheOwningTransaction() throws Exception {
        Configuration c = configuration(true);
        Timeouts timeouts = new Timeouts();
        c.addInterceptor(timeouts);
        c.addInterceptor(new ReentrantPlugin(c));
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(c), manager);
        manager.begin();
        Row row = session.selectOne("parent");
        assertEquals(List.of(42), row.getIds());
        assertEquals(Arrays.asList(3, 3), timeouts.values);
        manager.commit();
    }

    public static class AuxiliaryTransaction extends ManagedTransaction {
        public AuxiliaryTransaction(DataSource dataSource) {
            super(dataSource, null, true);
        }

        @Override
        public Integer getTimeout() {
            return 1;
        }
    }

    public static class ReentrantPlugin implements Interceptor {
        private final Configuration configuration;
        private boolean initialized;

        public ReentrantPlugin(Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public Object plugin(Object target) {
            if (target instanceof Executor && !initialized) {
                initialized = true;
                Executor auxiliary = configuration.newExecutor(
                        new AuxiliaryTransaction(configuration.getEnvironment().getDataSource()), ExecutorType.SIMPLE);
                auxiliary.close(false);
            }
            return target;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    public static class Row {
        private List<Integer> ids;

        public List<Integer> getIds() {
            return ids;
        }

        public void setIds(List<Integer> ids) {
            this.ids = ids;
        }
    }

    public static class TimeoutFactory extends ManagedTransactionFactory {
        TimedTransaction transaction;

        @Override
        public Transaction newTransaction(DataSource dataSource, TransactionIsolationLevel level, boolean autoCommit) {
            transaction = new TimedTransaction(dataSource, level);
            return transaction;
        }
    }

    public static class TimedTransaction extends ManagedTransaction {
        int timeout = 3;

        public TimedTransaction(DataSource dataSource, TransactionIsolationLevel level) {
            super(dataSource, level, true);
        }

        @Override
        public Integer getTimeout() {
            return timeout;
        }
    }

    public static class CursorTransactionFactory extends TimeoutFactory {
        CursorTransaction cursor;
        int cursorTimeout = 1;

        @Override
        public Transaction newTransaction(Connection connection) {
            cursor = new CursorTransaction(connection, cursorTimeout);
            return cursor;
        }
    }

    public static class CursorTransaction extends ManagedTransaction {
        int closes;
        private final int timeout;

        public CursorTransaction(Connection connection, int timeout) {
            super(connection, true);
            this.timeout = timeout;
        }

        @Override
        public Integer getTimeout() {
            return timeout;
        }

        @Override
        public void close() throws SQLException {
            closes++;
            super.close();
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = { Connection.class, Integer.class }))
    public static class Timeouts implements Interceptor {
        final List<Integer> values = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            values.add((Integer) invocation.getArgs()[1]);
            return invocation.proceed();
        }
    }

    public static class Decorator implements Interceptor {
        @Override
        public Object plugin(Object target) {
            if (!(target instanceof Executor))
                return target;
            return Proxy.newProxyInstance(Executor.class.getClassLoader(), new Class<?>[] { Executor.class },
                    (proxy, method, args) -> {
                        try {
                            return method.invoke(target, args);
                        } catch (Throwable failure) {
                            throw ExceptionUtil.unwrapThrowable(failure);
                        }
                    });
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }
}
