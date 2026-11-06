package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mybatis.runtime.CacheRegistrationSupport;
import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

public class CacheRegistrationReviewTest {
    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(DynamicConfiguration.class, OpeningConfiguration.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:registration_review;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.datasource.username", "sa")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "enabled")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.jdbc.max-size", "3")
            .overrideConfigKey("quarkus.datasource.jdbc.metrics.enabled", "true")
            .overrideConfigKey("quarkus.mybatis.transaction-factory", "MANAGED");

    @Inject
    AgroalDataSource dataSource;
    @Inject
    TransactionManager transactions;
    @Inject
    SqlSessionFactory factory;

    @BeforeEach
    void prepare() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS registration_review (id INTEGER PRIMARY KEY)");
            s.execute("DELETE FROM registration_review");
            s.execute("INSERT INTO registration_review VALUES (1)");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (transactions.getTransaction() != null) {
            transactions.rollback();
        }
        assertEquals(0, dataSource.getMetrics().activeCount(), "Registration scenarios must return all connections");
    }

    @Test
    void failedDuplicateCacheRegistrationMustNotDisableExistingJtaCalls() throws Exception {
        MyBatisConfiguration cfg = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        Cache cache = new PerpetualCache("shared");
        cfg.addCache(cache);
        addRead(cfg, cache);
        TransactionalSqlSession session = facade(cfg);

        assertThrows(IllegalArgumentException.class,
                () -> cfg.addCache(new BlockingCache(new PerpetualCache("shared"))));
        assertSame(cache, cfg.getCache("shared"), "Rejected cache was never installed in MyBatis");
        transactions.begin();
        assertEquals(List.of(1), session.selectList("read"), "A failed registration must not poison old statements");
        transactions.commit();
    }

    @Test
    void dynamicallyResolvedStatementMustNotBypassGenerationAndResurrectOldRows() throws Exception {
        Cache cache = new PerpetualCache("shared");
        DynamicConfiguration cfg = new DynamicConfiguration(factory.getConfiguration().getEnvironment(), cache);
        cfg.addCache(cache);
        addRead(cfg, cache);
        TransactionalSqlSession session = facade(cfg);
        assertFalse(cfg.hasStatement("generatedWrite"));

        transactions.begin();
        assertThrows(IllegalStateException.class, () -> session.insert("generatedWrite"));
        assertEquals(0, dataSource.getMetrics().activeCount(), "Reject before opening a session or acquiring a connection");
        assertEquals(List.of(1), session.selectList("read"));
        transactions.commit();

        // A custom resolver can explicitly prepare a stable statement before exposing it to callers.
        cfg.generated = CacheRegistrationSupport.prepare(cfg.generated);

        transactions.begin();
        assertEquals(List.of(1), session.selectList("read"));
        Transaction older = transactions.suspend();
        try {
            transactions.begin();
            session.insert("generatedWrite");
            transactions.commit();
            assertEquals(List.of(1, 2), session.selectList("read"));
        } finally {
            if (transactions.getTransaction() != null) {
                transactions.rollback();
            }
            transactions.resume(older);
        }
        transactions.commit();

        assertDatabaseRows();
        assertEquals(List.of(1, 2), session.selectList("read"),
                "The older guarded reader must not overwrite results after a raw dynamically resolved write");
    }

    @Test
    void openerFailureMustNotAssumeNoSqlFromAbsenceOfCacheAccess() throws Exception {
        OpeningConfiguration cfg = new OpeningConfiguration(factory.getConfiguration().getEnvironment());
        Cache cache = new PerpetualCache("shared");
        cfg.addCache(cache);
        addRead(cfg, cache);
        TransactionalSqlSession session = facade(cfg);
        assertEquals(List.of(1), session.selectList("read"));
        cfg.failAfterWrite.set(true);

        assertThrows(RuntimeException.class, () -> session.selectList("read"));
        assertEquals(0, dataSource.getMetrics().activeCount());
        assertDatabaseRows();
        assertEquals(List.of(1, 2), session.selectList("read"),
                "A custom opener can execute SQL before throwing without touching a cache");
    }

    @Test
    void executorPluginFailureAfterSqlAlsoInvalidatesAndReleasesConnection() throws Exception {
        MyBatisConfiguration cfg = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        AtomicBoolean failAfterWrite = new AtomicBoolean();
        cfg.addInterceptor(new org.apache.ibatis.plugin.Interceptor() {
            @Override
            public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
                return invocation.proceed();
            }

            @Override
            public Object plugin(Object target) {
                if (target instanceof Executor executor && failAfterWrite.compareAndSet(true, false)) {
                    try (Statement statement = executor.getTransaction().getConnection().createStatement()) {
                        statement.executeUpdate("INSERT INTO registration_review VALUES (2)");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    throw new IllegalStateException("Executor plugin failed after autocommitted SQL");
                }
                return target;
            }
        });
        Cache cache = new PerpetualCache("shared");
        cfg.addCache(cache);
        addRead(cfg, cache);
        TransactionalSqlSession session = facade(cfg);
        assertEquals(List.of(1), session.selectList("read"));
        failAfterWrite.set(true);

        assertThrows(RuntimeException.class, () -> session.selectList("read"));
        assertEquals(0, dataSource.getMetrics().activeCount());
        assertDatabaseRows();
        assertEquals(List.of(1, 2), session.selectList("read"));
    }

    @Test
    void connectionExposedBeforeFirstCacheRegistrationStillInvalidatesAtCommit() throws Exception {
        MyBatisConfiguration cfg = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        TransactionalSqlSession session = facade(cfg);
        transactions.begin();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("INSERT INTO registration_review VALUES (2)");
        }
        Cache cache = new PerpetualCache("late");
        cfg.addCache(cache);
        addRead(cfg, cache);
        Transaction older = transactions.suspend();
        try {
            transactions.begin();
            assertEquals(List.of(1), session.selectList("read"));
            transactions.commit();
            assertEquals(0, cache.getSize(),
                    "A cache registered behind an active JDBC-write fence must not publish old data");
        } finally {
            if (transactions.getTransaction() != null) {
                transactions.rollback();
            }
            transactions.resume(older);
        }
        transactions.commit();

        assertEquals(List.of(1, 2), session.selectList("read"));
    }

    private TransactionalSqlSession facade(Configuration cfg) {
        return new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(cfg), transactions);
    }

    @Test
    void synchronizationRegistrationFailureDiscardsOpenerResultsBeforeClose() throws Exception {
        verifyRegistrationFailure(false);
    }

    @Test
    void failedRollbackDuringRegistrationCleanupCannotPublishOnClose() throws Exception {
        verifyRegistrationFailure(true);
    }

    private void verifyRegistrationFailure(boolean failRollback) throws Exception {
        MyBatisConfiguration cfg = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        Cache cache = new PerpetualCache("registration-failure");
        cfg.addCache(cache);
        addRead(cfg, cache);
        cfg.addMappedStatement(new MappedStatement.Builder(cfg, "write",
                new StaticSqlSource(cfg, "INSERT INTO registration_review VALUES (2)"), SqlCommandType.INSERT)
                .cache(cache).flushCacheRequired(true).build());
        SqlSessionFactory delegateFactory = new SqlSessionFactoryBuilder().build(cfg);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger entriesAtClose = new java.util.concurrent.atomic.AtomicInteger(-1);
        AtomicBoolean forcedRollback = new AtomicBoolean();
        IllegalStateException rollbackFailure = new IllegalStateException("Rollback cleanup failed");
        jakarta.transaction.RollbackException registrationFailure = new jakarta.transaction.RollbackException(
                "Registration failed");
        SqlSessionFactory opener = (SqlSessionFactory) java.lang.reflect.Proxy.newProxyInstance(
                SqlSessionFactory.class.getClassLoader(), new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getConfiguration")) {
                        return cfg;
                    }
                    if (!method.getName().equals("openSession")) {
                        throw new AssertionError(method.getName());
                    }
                    org.apache.ibatis.session.SqlSession opened = delegateFactory.openSession((Boolean) args[0]);
                    opened.insert("write");
                    assertEquals(List.of(1, 2), opened.selectList("read"));
                    return java.lang.reflect.Proxy.newProxyInstance(org.apache.ibatis.session.SqlSession.class.getClassLoader(),
                            new Class<?>[] { org.apache.ibatis.session.SqlSession.class }, (p, operation, arguments) -> {
                                if (operation.getName().equals("rollback")) {
                                    forcedRollback.set(arguments != null && Boolean.TRUE.equals(arguments[0]));
                                    if (failRollback) {
                                        throw rollbackFailure;
                                    }
                                }
                                try {
                                    return operation.invoke(opened, arguments);
                                } catch (java.lang.reflect.InvocationTargetException failure) {
                                    throw failure.getCause();
                                } finally {
                                    if (operation.getName().equals("close")) {
                                        closes.incrementAndGet();
                                        entriesAtClose.set(cache.getSize());
                                    }
                                }
                            });
                });
        transactions.begin();
        Transaction actual = transactions.getTransaction();
        Transaction rejected = (Transaction) java.lang.reflect.Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                new Class<?>[] { Transaction.class }, (proxy, method, args) -> {
                    if (method.getName().equals("registerSynchronization")) {
                        throw registrationFailure;
                    }
                    try {
                        return method.invoke(actual, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        TransactionManager rejectingManager = (TransactionManager) java.lang.reflect.Proxy.newProxyInstance(
                TransactionManager.class.getClassLoader(), new Class<?>[] { TransactionManager.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getTransaction")) {
                        return rejected;
                    }
                    throw new AssertionError(method.getName());
                });
        TransactionalSqlSession session = new TransactionalSqlSession(opener, rejectingManager);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> session.selectList("read"));

        assertSame(registrationFailure, failure.getCause());
        assertEquals(jakarta.transaction.Status.STATUS_MARKED_ROLLBACK, transactions.getStatus(),
                "Uncoordinated opener writes must not be allowed to commit if the caller catches this failure");
        assertEquals(1, closes.get());
        assertTrue(forcedRollback.get());
        assertEquals(0, entriesAtClose.get(), "Even failed rollback must not allow close to publish pending results");
        if (failRollback) {
            assertEquals(List.of(rollbackFailure), List.of(registrationFailure.getSuppressed()));
        }
        assertThrows(jakarta.transaction.RollbackException.class, transactions::commit);
        assertEquals(0, dataSource.getMetrics().activeCount());
        assertEquals(List.of(1), facade(cfg).selectList("read"));
    }

    private static void addRead(Configuration cfg, Cache cache) {
        cfg.addMappedStatement(new MappedStatement.Builder(cfg, "read",
                new StaticSqlSource(cfg, "SELECT id FROM registration_review ORDER BY id"), SqlCommandType.SELECT)
                .cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(cfg, "read-result", Integer.class, List.of()).build())).build());
    }

    private void assertDatabaseRows() throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM registration_review")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "Database must contain the committed second row");
        }
    }

    public static class DynamicConfiguration extends Configuration {
        private MappedStatement generated;

        public DynamicConfiguration(Environment environment, Cache cache) {
            super(environment);
            generated = new MappedStatement.Builder(this, "generatedWrite",
                    new StaticSqlSource(this, "INSERT INTO registration_review VALUES (2)"), SqlCommandType.INSERT)
                    .cache(cache).flushCacheRequired(true).build();
        }

        @Override
        public MappedStatement getMappedStatement(String id) {
            return id.equals("generatedWrite") ? generated : super.getMappedStatement(id);
        }
    }

    public static class OpeningConfiguration extends MyBatisConfiguration {
        public final AtomicBoolean failAfterWrite = new AtomicBoolean();

        public OpeningConfiguration(Environment environment) {
            super(environment);
        }

        @Override
        public Executor newExecutor(org.apache.ibatis.transaction.Transaction transaction, ExecutorType type) {
            if (failAfterWrite.compareAndSet(true, false)) {
                try (Statement statement = transaction.getConnection().createStatement()) {
                    statement.executeUpdate("INSERT INTO registration_review VALUES (2)");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                throw new IllegalStateException("Custom executor setup failed after autocommitted SQL");
            }
            return super.newExecutor(transaction, type);
        }
    }
}
