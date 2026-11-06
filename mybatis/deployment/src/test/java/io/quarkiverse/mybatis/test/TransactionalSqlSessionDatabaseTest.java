package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TimeoutAfterWrite.class, TimeoutAfterRead.class, FailAfterQueuedWrite.class,
                            CachedMapper.class, CachedRow.class, NonSerializableRow.class, LazyRow.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:session_lifecycle;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.datasource.username", "sa")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "enabled")
            .overrideConfigKey("quarkus.datasource.jdbc.min-size", "0")
            .overrideConfigKey("quarkus.datasource.jdbc.max-size", "2")
            .overrideConfigKey("quarkus.datasource.jdbc.acquisition-timeout", "2S")
            .overrideConfigKey("quarkus.datasource.jdbc.metrics.enabled", "true")
            .overrideConfigKey("quarkus.mybatis.transaction-factory", "MANAGED");

    @Inject
    AgroalDataSource dataSource;

    @Inject
    TransactionManager transactionManager;

    @Inject
    TransactionSynchronizationRegistry synchronizationRegistry;

    @Inject
    SqlSessionFactory sqlSessionFactory;

    private CountDownLatch completed;
    private volatile Thread completionThread;
    private volatile int completionStatus;
    private TransactionalSqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        completed = new CountDownLatch(1);
        completionThread = null;
        completionStatus = Status.STATUS_UNKNOWN;
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
    void jdbcFactoryStillCommitsEachCallBeforeOuterJtaCompletion() throws Exception {
        useJdbcTransactionFactory();
        transactionManager.begin();

        assertEquals(1, session.insert("write", Map.of("id", 1)));
        assertRowCountInCurrentTransaction(1);
        transactionManager.rollback();

        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void jdbcFactoryCaughtFailureKeepsEarlierCommitsAndJtaActive() throws Exception {
        useJdbcTransactionFactory();
        transactionManager.begin();
        session.insert("write", Map.of("id", 1));

        assertThrows(PersistenceException.class, () -> session.insert("write", Map.of("id", 1)));
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(1, session.insert("write", Map.of("id", 2)));
        assertRowCountInCurrentTransaction(2);
        transactionManager.commit();

        assertResourcesReturned();
        assertRowCountInNewTransaction(2);
    }

    private void useJdbcTransactionFactory() {
        session = newSession(transactionManager, false);
        // This datasource deliberately does not enlist its connections in JTA.
        session.getConfiguration().setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:session_lifecycle;DB_CLOSE_DELAY=-1", "sa", "")));
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

    @Test
    void rolledBackWritesAndReadsNeverReachSecondLevelCache() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.begin();
        session.insert("write");
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.rollback();

        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
        transactionManager.begin();
        assertTrue(session.selectList("read").isEmpty(), "A new transaction must not see rolled-back data");
        transactionManager.commit();
    }

    @Test
    void concurrentMapperReadDoesNotSeeUncommittedOrRolledBackUpdate() throws Exception {
        Cache cache = new CacheBuilder("session-lifecycle-cache").readWrite(true).build();
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "updateId",
                new StaticSqlSource(configuration, "UPDATE session_lifecycle SET id = 2 WHERE id = 1"),
                SqlCommandType.UPDATE).cache(cache).flushCacheRequired(true).build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        CachedMapper mapper = session.getMapper(CachedMapper.class);

        transactionManager.begin();
        mapper.write();
        assertEquals(List.of(1), mapper.read());
        transactionManager.commit();

        transactionManager.begin();
        assertEquals(1, session.update("updateId"));
        assertEquals(List.of(2), mapper.read(), "The writer must see its own update");
        Transaction writer = transactionManager.suspend();
        List<Integer> beforeRollback;
        try {
            transactionManager.begin();
            beforeRollback = mapper.read();
            transactionManager.commit();
        } finally {
            if (transactionManager.getTransaction() != null) {
                transactionManager.rollback();
            }
            transactionManager.resume(writer);
            transactionManager.rollback();
        }

        transactionManager.begin();
        List<Integer> afterRollback = mapper.read();
        transactionManager.commit();

        assertAll(
                () -> assertEquals(List.of(1), beforeRollback, "Another transaction must not see the uncommitted update"),
                () -> assertEquals(List.of(1), afterRollback, "A fresh transaction must not see the rolled-back update"));
        assertResourcesReturned();
    }

    @Test
    void readOnlyRollbackDiscardsPendingCacheEntries() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.begin();
        assertTrue(session.selectList("read").isEmpty());
        transactionManager.rollback();

        assertEquals(0, cache.getSize(), "Rollback must discard cached reads even when the session is not dirty");
        assertResourcesReturned();
    }

    @Test
    void nonTransactionalReadFailureDiscardsPendingCacheEntries() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO session_lifecycle (id) VALUES (1), (2)");
        }

        assertThrows(TooManyResultsException.class, () -> session.selectOne("read"));

        assertEquals(0, cache.getSize(), "A failed read must not publish its pending result when the session closes");
        assertResourcesReturned();
    }

    @Test
    void cachePublicationWaitsForSuccessfulJtaCompletion() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.begin();
        session.insert("write");
        assertEquals(List.of(1), session.selectList("read"));
        assertEquals(0, cache.getSize(), "Mapper calls must not publish uncommitted data");
        transactionManager.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                assertEquals(0, cache.getSize(), "Flushing SQL must not publish cache entries");
            }

            @Override
            public void afterCompletion(int status) {
            }
        });
        transactionManager.commit();

        assertEquals(1, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        assertEquals(0, dataSource.getMetrics().activeCount(), "The committed result should come from the cache");
        transactionManager.commit();
    }

    @Test
    void vetoAfterSessionFlushDoesNotPublishCache() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.begin();
        session.insert("write");
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                throw new IllegalStateException("Another participant vetoed the commit");
            }

            @Override
            public void afterCompletion(int status) {
            }
        });

        assertThrows(RollbackException.class, transactionManager::commit);

        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void timeoutDiscardsPendingCacheEntries() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(session.getConfiguration()),
                observeCompletion());
        session.insert("write");
        assertEquals(List.of(1), session.selectList("read"));

        assertTrue(completed.await(10, TimeUnit.SECONDS));

        assertEquals(Status.STATUS_ROLLEDBACK, completionStatus);
        assertEquals(0, cache.getSize());
        transactionManager.suspend();
        transactionManager.setTransactionTimeout(0);
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void timeoutDuringCachedReadDiscardsCacheWhenInvocationReturns() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        Configuration configuration = session.getConfiguration();
        configuration.addInterceptor(new TimeoutAfterRead(completed, dataSource));
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), observeCompletion());
        session.insert("write");

        // The query produces a pending cache entry, then waits for the Reaper before returning to the wrapper.
        assertEquals(List.of(1), session.selectList("read"));

        assertEquals(Status.STATUS_ROLLEDBACK, completionStatus);
        assertNotSame(Thread.currentThread(), completionThread);
        assertEquals(0, cache.getSize());
        transactionManager.suspend();
        transactionManager.setTransactionTimeout(0);
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void cacheInvalidationFollowsCommitOrRollback() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        transactionManager.begin();
        assertTrue(session.selectList("read").isEmpty());
        transactionManager.commit();
        assertEquals(1, cache.getSize());

        transactionManager.begin();
        session.insert("write");
        assertEquals(0, cache.getSize(), "A pending write must clear shared values before JDBC commit");
        transactionManager.rollback();
        assertEquals(0, cache.getSize(), "Rollback may retain the safe early invalidation");

        transactionManager.begin();
        session.insert("write");
        transactionManager.commit();
        assertEquals(0, cache.getSize(), "Committed writes must invalidate the old result");
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void reuseCursorRemainsReadableUntilConsumedWithinTransaction() throws Exception {
        newCachedSession(ExecutorType.REUSE);
        transactionManager.begin();
        session.insert("write");
        try (Cursor<Integer> cursor = session.selectCursor("read")) {
            // A subsequent mapper invocation must not flush and close the cursor's statement either.
            session.getConnection();
            List<Integer> values = new ArrayList<>();
            cursor.forEach(values::add);
            assertEquals(List.of(1), values);
            assertTrue(cursor.isConsumed());
        }
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void transactionCompletionClosesUnconsumedReuseCursor() throws Exception {
        newCachedSession(ExecutorType.REUSE);
        transactionManager.begin();
        session.insert("write");
        Cursor<Integer> cursor = session.selectCursor("read");
        assertTrue(cursor.iterator().hasNext());
        assertTrue(cursor.isOpen());

        transactionManager.rollback();

        assertFalse(cursor.isOpen());
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void batchStatementsFlushBeforeInvocationReturns() throws Exception {
        newCachedSession(ExecutorType.BATCH);
        transactionManager.begin();
        session.insert("write");
        assertRowCountInCurrentTransaction(1);
        transactionManager.commit();

        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void batchFailureIsReportedByInsertAndCallerCanRollBack() throws Exception {
        Cache cache = newCachedSession(ExecutorType.BATCH);
        transactionManager.begin();
        session.insert("write");
        assertThrows(PersistenceException.class, () -> session.insert("write"));
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());

        transactionManager.rollback();

        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void laterBeforeCompletionCanReadAndWriteThroughMapper() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE);
        CachedMapper mapper = session.getMapper(CachedMapper.class);
        transactionManager.begin();
        assertTrue(mapper.read().isEmpty());
        transactionManager.getTransaction().registerSynchronization(beforeCompletion(() -> {
            assertTrue(mapper.read().isEmpty());
            mapper.write();
            assertEquals(List.of(1), mapper.read());
            assertEquals(0, cache.getSize(), "Callback reads must remain pending until JTA commits");
        }));

        transactionManager.commit();

        assertEquals(1, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void laterBeforeCompletionFlushesBatchWritesBeforeJdbcCommit() throws Exception {
        assertLateBatchWriteIsFlushed(false);
    }

    @Test
    void interposedBeforeCompletionFlushesBatchWritesBeforeJdbcCommit() throws Exception {
        assertLateBatchWriteIsFlushed(true);
    }

    private void assertLateBatchWriteIsFlushed(boolean interposed) throws Exception {
        Cache cache = newCachedSession(ExecutorType.BATCH);
        CachedMapper mapper = session.getMapper(CachedMapper.class);
        transactionManager.begin();
        assertTrue(mapper.read().isEmpty());
        transactionManager.commit();
        assertEquals(1, cache.getSize());

        transactionManager.begin();
        session.getConnection(); // Register the session's synchronization before the application callback.
        Synchronization callback = beforeCompletion(() -> {
            mapper.write();
            // Raw JDBC must already see the insert, without a MyBatis query or explicit flush helping it.
            assertRowCountInCurrentTransaction(1);
            assertEquals(0, cache.getSize(), "Callback writes must invalidate before JTA can commit");
        });
        if (interposed) {
            synchronizationRegistry.registerInterposedSynchronization(callback);
        } else {
            transactionManager.getTransaction().registerSynchronization(callback);
        }

        transactionManager.commit();

        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void caughtLateBatchFailureDoesNotForceRollback() throws Exception {
        Cache cache = newCachedSession(ExecutorType.BATCH);
        CachedMapper mapper = session.getMapper(CachedMapper.class);
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        transactionManager.begin();
        mapper.write();
        transactionManager.getTransaction().registerSynchronization(beforeCompletion(() -> {
            assertThrows(PersistenceException.class, mapper::write);
            assertEquals(Status.STATUS_ACTIVE, synchronizationRegistry.getTransactionStatus());
            callbackCompleted.set(true);
        }));

        transactionManager.commit();

        assertTrue(callbackCompleted.get(), "The callback must catch the actual batch failure");
        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void vetoAfterLateBatchWriteRollsBackSqlAndDiscardsCache() throws Exception {
        Cache cache = newCachedSession(ExecutorType.BATCH);
        CachedMapper mapper = session.getMapper(CachedMapper.class);
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        transactionManager.begin();
        assertTrue(mapper.read().isEmpty());
        transactionManager.getTransaction().registerSynchronization(beforeCompletion(() -> {
            mapper.write();
            assertRowCountInCurrentTransaction(1);
            assertEquals(List.of(1), mapper.read());
            assertEquals(0, cache.getSize());
            callbackCompleted.set(true);
            throw new IllegalStateException("Veto after the callback's write has been flushed");
        }));

        assertThrows(RollbackException.class, transactionManager::commit);

        assertTrue(callbackCompleted.get(), "The callback must reach the veto after executing SQL");
        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(0);
    }

    @Test
    void laterBeforeCompletionKeepsReuseCursorReadable() throws Exception {
        newCachedSession(ExecutorType.REUSE);
        transactionManager.begin();
        session.insert("write");
        transactionManager.getTransaction().registerSynchronization(beforeCompletion(() -> {
            try (Cursor<Integer> cursor = session.selectCursor("read")) {
                session.getConnection();
                List<Integer> values = new ArrayList<>();
                cursor.forEach(values::add);
                assertEquals(List.of(1), values);
                assertTrue(cursor.isConsumed());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }));

        transactionManager.commit();

        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void batchGeneratedKeyIsAvailableImmediatelyAndJdbcStillRollsBack() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS session_generated "
                    + "(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, note VARCHAR(64))");
        }
        Configuration configuration = new Configuration(sqlSessionFactory.getConfiguration().getEnvironment());
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "generated",
                new StaticSqlSource(configuration, "INSERT INTO session_generated (note) VALUES (?)",
                        List.of(new ParameterMapping.Builder(configuration, "note", String.class).build())),
                SqlCommandType.INSERT).keyGenerator(Jdbc3KeyGenerator.INSTANCE).keyProperty("id").keyColumn("id").build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("note", "generated-key");

        transactionManager.begin();
        session.insert("generated", parameter);
        assertNotNull(parameter.get("id"), "The generated key must be populated before insert returns");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM session_generated")) {
            assertFalse(connection.getAutoCommit(), "The MyBatis session flag must not enable JDBC auto-commit");
            assertTrue(rows.next());
            assertEquals(((Number) parameter.get("id")).longValue(), rows.getLong(1));
        }
        transactionManager.rollback();

        assertResourcesReturned();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM session_generated")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void eachMapperCallClearsLocalCacheSoJdbcWritesRemainVisible() throws Exception {
        newCachedSession(ExecutorType.SIMPLE);
        session.getConfiguration().setCacheEnabled(false);
        assertEquals(LocalCacheScope.SESSION, session.getConfiguration().getLocalCacheScope());
        CachedMapper mapper = session.getMapper(CachedMapper.class);
        transactionManager.begin();
        assertTrue(mapper.read().isEmpty());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO session_lifecycle (id) VALUES (1)");
        }

        assertEquals(List.of(1), mapper.read(), "A new invocation must not reuse the earlier empty local result");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void mutatingQueryResultDoesNotAffectTheNextInvocation() throws Exception {
        newCachedSession(ExecutorType.SIMPLE);
        session.getConfiguration().setCacheEnabled(false);
        transactionManager.begin();
        session.insert("write");
        List<Integer> first = session.selectList("read");
        first.clear();

        assertEquals(List.of(1), session.selectList("read"));

        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void mutatingReturnedListBeforeCommitDoesNotChangeTheSharedCache() throws Exception {
        newCachedSession(ExecutorType.SIMPLE, new CacheBuilder("mutable-list-cache").readWrite(true).build());
        session.insert("write");
        transactionManager.begin();
        List<Integer> result = session.selectList("read");
        assertEquals(List.of(1), result);
        result.clear();

        // Do not read the same key again: that would replace the pending entry and hide the mutation.
        transactionManager.commit();

        assertStoredIds(List.of(1));
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"),
                "A caller's list mutation must not become a shared query result");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void mutatingReturnedBeanBeforeCommitDoesNotChangeTheSharedCache() throws Exception {
        Cache cache = new CacheBuilder("mutable-bean-cache").readWrite(true).build();
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "readRow",
                new StaticSqlSource(configuration, "SELECT id FROM session_lifecycle"), SqlCommandType.SELECT)
                .cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "bean-result", CachedRow.class,
                        List.of(new ResultMapping.Builder(configuration, "id", "ID", Integer.class).build())).build()))
                .build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        session.insert("write");
        transactionManager.begin();
        CachedRow result = session.selectOne("readRow");
        assertEquals(1, result.getId());
        result.setId(99);
        transactionManager.commit();

        assertStoredIds(List.of(1));
        transactionManager.begin();
        CachedRow nextResult = session.selectOne("readRow");
        assertEquals(1, nextResult.getId(),
                "A caller's in-memory bean change must not replace database content in the cache");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void olderReadTransactionCannotRepublishDataAfterACommittedWrite() throws Exception {
        Cache cache = new CacheBuilder("concurrent-read-cache").readWrite(true).build();
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "writeTwo",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (2)"),
                SqlCommandType.INSERT).cache(cache).flushCacheRequired(true).build());
        SqlSessionFactory sharedFactory = new SqlSessionFactoryBuilder().build(configuration);
        session = new TransactionalSqlSession(sharedFactory, transactionManager);
        TransactionalSqlSession writer = new TransactionalSqlSession(sharedFactory, transactionManager);
        session.insert("write");
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        Transaction reader = transactionManager.suspend();
        try {
            transactionManager.begin();
            writer.insert("writeTwo");
            transactionManager.commit();
        } finally {
            if (transactionManager.getTransaction() != null) {
                transactionManager.rollback();
            }
            transactionManager.resume(reader);
        }
        transactionManager.commit();

        assertStoredIds(List.of(1, 2));
        transactionManager.begin();
        assertEquals(List.of(1, 2), session.selectList("read"),
                "An older transaction must not republish its result after another transaction invalidated it");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void olderReadTransactionCannotRepublishDataAfterANonTransactionalWrite() throws Exception {
        Cache cache = new CacheBuilder("non-transactional-write-cache").readWrite(true).build();
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "writeTwo",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (2)"),
                SqlCommandType.INSERT).cache(cache).flushCacheRequired(true).build());
        SqlSessionFactory sharedFactory = new SqlSessionFactoryBuilder().build(configuration);
        session = new TransactionalSqlSession(sharedFactory, transactionManager);
        TransactionalSqlSession writer = new TransactionalSqlSession(sharedFactory, transactionManager);
        session.insert("write");
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        Transaction reader = transactionManager.suspend();
        try {
            writer.insert("writeTwo");
        } finally {
            transactionManager.resume(reader);
        }
        transactionManager.commit();

        assertStoredIds(List.of(1, 2));
        transactionManager.begin();
        assertEquals(List.of(1, 2), session.selectList("read"),
                "An older transaction must not republish data invalidated by a non-JTA facade call");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void nonSerializableResultsRemainUsableWithoutPublishingMutableReferences() throws Exception {
        Cache cache = new PerpetualCache("non-serializable-cache");
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "writeTwo",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (2)"),
                SqlCommandType.INSERT).cache(cache).flushCacheRequired(true).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "readMutableRow",
                new StaticSqlSource(configuration, "SELECT id FROM session_lifecycle WHERE id = 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "mutable-bean-result", NonSerializableRow.class,
                        List.of(new ResultMapping.Builder(configuration, "id", "ID", Integer.class).build())).build()))
                .build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        session.insert("write");
        transactionManager.begin();
        session.insert("writeTwo");
        NonSerializableRow result = session.selectOne("readMutableRow");
        assertEquals(1, result.getId());
        result.setId(99);
        transactionManager.commit();

        assertEquals(0, cache.getSize(), "A result that cannot be copied must not publish its mutable reference");
        assertStoredIds(List.of(1, 2));
        transactionManager.begin();
        NonSerializableRow nextResult = session.selectOne("readMutableRow");
        assertEquals(1, nextResult.getId(), "A non-serializable result must remain readable without cache corruption");
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void directJdbcWriteThroughSessionConnectionInvalidatesCacheAtCompletion() throws Exception {
        Cache cache = newCachedSession(ExecutorType.SIMPLE,
                new CacheBuilder("direct-jdbc-cache").readWrite(true).build());
        session.insert("write");
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.commit();
        assertEquals(1, cache.getSize());

        transactionManager.begin();
        try (Connection connection = session.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO session_lifecycle (id) VALUES (2)");
        }
        transactionManager.commit();

        assertEquals(0, cache.getSize(), "A direct JDBC write must invalidate the factory's cached results at completion");
        assertStoredIds(List.of(1, 2));
        transactionManager.begin();
        assertEquals(List.of(1, 2), session.selectList("read"));
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void lazyResultsKeepTheirLoaderAndDoNotPublishMutableReferences() throws Exception {
        Cache cache = new CacheBuilder("lazy-row-cache").readWrite(true).build();
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE, cache);
        configuration.setLazyLoadingEnabled(true);
        configuration.setAggressiveLazyLoading(false);
        configuration.setLazyLoadTriggerMethods(java.util.Set.of());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "lazyValue",
                new StaticSqlSource(configuration, "SELECT id FROM session_lifecycle WHERE id = 1"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "lazy-value", Integer.class, List.of()).build()))
                .build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "lazyRow",
                new StaticSqlSource(configuration, "SELECT id FROM session_lifecycle WHERE id = 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "lazy-row", LazyRow.class,
                        List.of(new ResultMapping.Builder(configuration, "id", "ID", Integer.class).build(),
                                new ResultMapping.Builder(configuration, "detail", "ID", Integer.class)
                                        .nestedQueryId("lazyValue").lazy(true).build()))
                        .build()))
                .build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        session.insert("write");

        transactionManager.begin();
        LazyRow result = session.selectOne("lazyRow");
        assertNull(result.detail, "Returning the result must not force lazy loading");
        assertEquals(1, result.getDetail());
        result.setDetail(99);
        transactionManager.commit();

        assertEquals(0, cache.getSize(), "A lazy result must not publish the caller's mutable proxy");
        transactionManager.begin();
        LazyRow nextResult = session.selectOne("lazyRow");
        assertEquals(1, nextResult.getDetail());
        transactionManager.commit();
        assertResourcesReturned();
    }

    public static class LazyRow extends CachedRow {
        private static final long serialVersionUID = 1L;
        private Integer detail;

        public Integer getDetail() {
            return detail;
        }

        public void setDetail(Integer detail) {
            this.detail = detail;
        }
    }

    public static class CachedRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private Integer id;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }
    }

    public static class NonSerializableRow {
        private Integer id;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }
    }

    private void assertStoredIds(List<Integer> expected) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM session_lifecycle ORDER BY id")) {
            List<Integer> stored = new ArrayList<>();
            while (rows.next()) {
                stored.add(rows.getInt(1));
            }
            assertEquals(expected, stored, "Raw JDBC must confirm the committed database content");
        }
    }

    @Test
    void failedInvocationDiscardsUnexecutedBatchButRetainsCacheInvalidation() throws Exception {
        Cache cache = new PerpetualCache("session-lifecycle-cache");
        Configuration configuration = newCachedConfiguration(ExecutorType.BATCH, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "writeTwo",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (2)"),
                SqlCommandType.INSERT).cache(cache).flushCacheRequired(true).build());
        configuration.addInterceptor(new FailAfterQueuedWrite());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        transactionManager.begin();
        assertTrue(session.selectList("read").isEmpty());
        transactionManager.commit();
        assertEquals(1, cache.getSize());

        transactionManager.begin();
        session.insert("write");
        PersistenceException failure = assertThrows(PersistenceException.class, () -> session.insert("writeTwo"));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(List.of(1), session.selectList("read"),
                "The failed invocation's queued row must not be flushed by a later query");
        transactionManager.commit();

        assertRowCountInNewTransaction(1);
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }))
    public static class FailAfterQueuedWrite implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().equals("writeTwo")) {
                throw new IllegalStateException("Failure after enqueueing SQL but before flushing it");
            }
            return result;
        }
    }

    private static Synchronization beforeCompletion(Runnable callback) {
        return new Synchronization() {
            @Override
            public void beforeCompletion() {
                callback.run();
            }

            @Override
            public void afterCompletion(int status) {
            }
        };
    }

    private void assertRowCountInCurrentTransaction(int expected) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM session_lifecycle")) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1));
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public interface CachedMapper {
        void write();

        List<Integer> read();
    }

    private Cache newCachedSession(ExecutorType executorType) {
        return newCachedSession(executorType, new PerpetualCache("session-lifecycle-cache"));
    }

    private Cache newCachedSession(ExecutorType executorType, Cache cache) {
        Configuration configuration = newCachedConfiguration(executorType, cache);
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        return cache;
    }

    private Configuration newCachedConfiguration(ExecutorType executorType, Cache cache) {
        Configuration configuration = new Configuration(sqlSessionFactory.getConfiguration().getEnvironment());
        configuration.setDefaultExecutorType(executorType);
        configuration.addCache(cache);
        configuration.addMapper(CachedMapper.class);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, CachedMapper.class.getName() + ".write",
                new StaticSqlSource(configuration, "INSERT INTO session_lifecycle (id) VALUES (1)"),
                SqlCommandType.INSERT).cache(cache).flushCacheRequired(true).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, CachedMapper.class.getName() + ".read",
                new StaticSqlSource(configuration, "SELECT id FROM session_lifecycle ORDER BY id"),
                SqlCommandType.SELECT).cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "id-result", Integer.class, List.of()).build()))
                .build());
        return configuration;
    }

    @Test
    void caughtMapperFailureCanCommitWritesWithCacheInvalidation() throws Exception {
        assertCaughtMapperFailureCanCommit(ExecutorType.SIMPLE);
    }

    @Test
    void caughtMapperFailureKeepsAlreadyFlushedBatchWritesCommittable() throws Exception {
        assertCaughtMapperFailureCanCommit(ExecutorType.BATCH);
    }

    private void assertCaughtMapperFailureCanCommit(ExecutorType executorType) throws Exception {
        Cache cache = new PerpetualCache("session-lifecycle-cache");
        Configuration configuration = newCachedConfiguration(executorType, cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "twoRows",
                new StaticSqlSource(configuration, "SELECT 1 UNION ALL SELECT 2"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "two-result", Integer.class, List.of()).build()))
                .build());
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        transactionManager.begin();
        assertTrue(session.selectList("read").isEmpty());
        transactionManager.commit();

        transactionManager.begin();
        session.insert("write");
        assertThrows(TooManyResultsException.class, () -> session.selectOne("twoRows"));
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(List.of(1), session.selectList("read"),
                "A caught failure must retain pending invalidations and not expose the old shared cache");
        transactionManager.commit();

        assertRowCountInNewTransaction(1);
        assertEquals(1, cache.getSize());
        transactionManager.begin();
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.commit();
        assertResourcesReturned();
    }

    @Test
    void jtaRejectsBlockingCacheBeforeAcquiringAnyCacheLockOrConnection() throws Exception {
        BlockingCache cache = new BlockingCache(new PerpetualCache("blocking-cache"));
        cache.setTimeout(100);
        newCachedSession(ExecutorType.SIMPLE, cache);
        transactionManager.begin();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> session.selectList("read"));
        assertTrue(failure.getMessage().contains("blocking-cache"));
        assertTrue(failure.getMessage().contains("blocking=false"));
        assertThrows(IllegalStateException.class, () -> session.selectList("read"));
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(0, dataSource.getMetrics().activeCount());
        transactionManager.commit();
        assertResourcesReturned();

        // A subsequent non-JTA call can acquire the same key, proving the rejected call held no cache lock.
        assertTrue(session.selectList("read").isEmpty());
        assertEquals(1, cache.getSize());
        assertResourcesReturned();
    }

    @Test
    void nonTransactionalCallsStillSupportBlockingCache() throws Exception {
        BlockingCache cache = new BlockingCache(new PerpetualCache("blocking-cache"));
        cache.setTimeout(100);
        newCachedSession(ExecutorType.SIMPLE, cache);

        assertTrue(session.selectList("read").isEmpty());
        assertTrue(session.selectList("read").isEmpty());

        assertEquals(1, cache.getSize());
        assertResourcesReturned();
    }

    @Test
    void disabledSecondLevelCacheAllowsRepeatedQueriesWithBlockingCacheConfigured() throws Exception {
        BlockingCache cache = new BlockingCache(new PerpetualCache("blocking-cache"));
        cache.setTimeout(100);
        newCachedSession(ExecutorType.SIMPLE, cache);
        session.getConfiguration().setCacheEnabled(false);
        transactionManager.begin();
        session.insert("write");
        assertEquals(List.of(1), session.selectList("read"));
        assertEquals(List.of(1), session.selectList("read"));
        transactionManager.commit();

        assertEquals(0, cache.getSize());
        assertResourcesReturned();
        assertRowCountInNewTransaction(1);
    }

    @Test
    void jtaValidatesAllFactoryCachesIncludingThoseUsedByNestedQueries() throws Exception {
        Configuration configuration = newCachedConfiguration(ExecutorType.SIMPLE,
                new PerpetualCache("session-lifecycle-cache"));
        configuration.addCache(new BlockingCache(new PerpetualCache("nested-query-cache")));
        session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), transactionManager);
        transactionManager.begin();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> session.selectList("read"));

        assertTrue(failure.getMessage().contains("nested-query-cache"));
        transactionManager.commit();
        assertResourcesReturned();
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
        // Verify that a fresh caller can use the pool after all session-owned connections have been returned.
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
            assertEquals(1, dataSource.getMetrics().activeCount(),
                    "The invocation must have acquired a real pooled connection");
            assertTrue(completed.await(10, TimeUnit.SECONDS), "Reaper completion did not return during the invocation");
            return result;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }))
    public static class TimeoutAfterRead extends TimeoutAfterWrite {
        public TimeoutAfterRead(CountDownLatch completed, AgroalDataSource dataSource) {
            super(completed, dataSource);
        }
    }
}
