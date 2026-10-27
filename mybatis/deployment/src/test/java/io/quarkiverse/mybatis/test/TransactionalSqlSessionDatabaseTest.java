package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

public class TransactionalSqlSessionDatabaseTest {
    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClass(TimeoutAfterWrite.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:session_lifecycle;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.datasource.username", "sa")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "enabled")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.jdbc.max-size", "1")
            .overrideConfigKey("quarkus.datasource.jdbc.acquisition-timeout", "2S")
            .overrideConfigKey("quarkus.datasource.jdbc.metrics.enabled", "true")
            .overrideConfigKey("quarkus.mybatis.transaction-factory", "MANAGED");

    @Inject
    AgroalDataSource dataSource;

    @Inject
    TransactionManager transactionManager;

    @Inject
    SqlSessionFactory sqlSessionFactory;

    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile Thread completionThread;
    private volatile int completionStatus;
    private TransactionalSqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        transactionManager.setTransactionTimeout(0);
        assertTrue(dataSource.getConfiguration().metricsEnabled(), "Pool metrics must be enabled");
        assertInstanceOf(ManagedTransactionFactory.class,
                sqlSessionFactory.getConfiguration().getEnvironment().getTransactionFactory());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS session_lifecycle (id INTEGER PRIMARY KEY)");
            statement.executeUpdate("DELETE FROM session_lifecycle");
        }
        assertEquals(0, dataSource.getMetrics().activeCount());
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (transactionManager.getTransaction() != null) {
                int status = transactionManager.getStatus();
                if (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK) {
                    transactionManager.rollback();
                } else {
                    transactionManager.suspend();
                }
            }
        } finally {
            transactionManager.setTransactionTimeout(0);
        }
    }

    @Test
    void commitPersistsDataAndReturnsConnection() throws Exception {
        session = newSession(transactionManager, false);
        transactionManager.begin();
        assertEquals(1, session.insert("write", Map.of("id", 1)));
        assertEquals(1, dataSource.getMetrics().activeCount());

        transactionManager.commit();

        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void rollbackDiscardsDataAndReturnsConnection() throws Exception {
        session = newSession(transactionManager, false);
        transactionManager.begin();
        assertEquals(1, session.insert("write", Map.of("id", 1)));
        assertEquals(1, dataSource.getMetrics().activeCount());

        transactionManager.rollback();

        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void timeoutDuringMyBatisInvocationRollsBackAndReturnsConnection() throws Exception {
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();
        session = newSession(observeCompletion(), true);

        // Execute a real insert, then hold the MyBatis executor invocation until the Reaper callback returns.
        assertEquals(1, session.insert("write", Map.of("id", 1)));

        assertEquals(Status.STATUS_ROLLEDBACK, completionStatus);
        assertNotSame(Thread.currentThread(), completionThread);
        transactionManager.suspend();
        transactionManager.setTransactionTimeout(0);
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    private TransactionalSqlSession newSession(TransactionManager manager, boolean waitForTimeout) {
        Configuration configuration = new Configuration(sqlSessionFactory.getConfiguration().getEnvironment());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "write",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (?)",
                        List.of(new ParameterMapping.Builder(configuration, "id", Integer.class).build())),
                SqlCommandType.INSERT).build());
        if (waitForTimeout) {
            configuration.addInterceptor(new TimeoutAfterWrite(completed, dataSource));
        }
        return new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), manager);
    }

    private TransactionManager observeCompletion() throws Exception {
        Transaction transaction = transactionManager.getTransaction();
        Transaction observed = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                new Class<?>[] { Transaction.class }, (proxy, method, args) -> {
                    if (method.getName().equals("registerSynchronization")) {
                        Synchronization synchronization = (Synchronization) args[0];
                        transaction.registerSynchronization(new Synchronization() {
                            @Override
                            public void beforeCompletion() {
                                synchronization.beforeCompletion();
                            }

                            @Override
                            public void afterCompletion(int status) {
                                try {
                                    synchronization.afterCompletion(status);
                                } finally {
                                    completionStatus = status;
                                    completionThread = Thread.currentThread();
                                    completed.countDown();
                                }
                            }
                        });
                        return null;
                    }
                    return method.invoke(transaction, args);
                });
        return (TransactionManager) Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),
                new Class<?>[] { TransactionManager.class },
                (proxy, method, args) -> method.getName().equals("getTransaction") ? observed
                        : method.invoke(transactionManager, args));
    }

    private void assertResourcesReturned() throws Exception {
        Field proxyField = TransactionalSqlSession.class.getDeclaredField("sqlSessionProxy");
        proxyField.setAccessible(true);
        Object interceptor = Proxy.getInvocationHandler(proxyField.get(session));
        Field mapField = interceptor.getClass().getDeclaredField("sqlSessionMap");
        mapField.setAccessible(true);
        assertTrue(((Map<?, ?>) mapField.get(interceptor)).isEmpty());
        assertEquals(0, dataSource.getMetrics().activeCount());
        // A one-connection pool cannot satisfy this acquisition if the previous invocation still owns its connection.
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(1));
            assertEquals(1, dataSource.getMetrics().activeCount());
        }
        assertEquals(0, dataSource.getMetrics().activeCount());
    }

    private void assertRowCountInNewTransaction(int expected) throws Exception {
        transactionManager.begin();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM session_lifecycle")) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
        }
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }))
    public static class TimeoutAfterWrite implements Interceptor {
        private final CountDownLatch completed;
        private final AgroalDataSource dataSource;

        public TimeoutAfterWrite(CountDownLatch completed, AgroalDataSource dataSource) {
            this.completed = completed;
            this.dataSource = dataSource;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            assertEquals(1, dataSource.getMetrics().activeCount(), "The insert must have acquired a real pooled connection");
            assertTrue(completed.await(10, TimeUnit.SECONDS), "Reaper completion did not return during the invocation");
            return result;
        }
    }
}
