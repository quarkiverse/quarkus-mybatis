package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionalSqlSessionTest {
    private final TransactionManager transactionManager = com.arjuna.ats.jta.TransactionManager.transactionManager();
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private final AtomicBoolean sessionClosed = new AtomicBoolean();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Synchronization> synchronization = new AtomicReference<>();
    private Configuration configuration = new Configuration();
    private final List<Boolean> rollbackForces = new CopyOnWriteArrayList<>();
    private Boolean sessionAutoCommit;
    private volatile Thread closingThread;
    private volatile Thread completionThread;
    private boolean waitForTimeout;
    private RuntimeException invocationFailure;
    private RuntimeException openFailure;
    private RuntimeException commitFailure;
    private RuntimeException flushFailure;
    private RuntimeException rollbackFailure;
    private RuntimeException closeFailure;
    private RuntimeException markRollbackFailure;
    private Runnable duringInvocation;
    private Runnable duringOpen;
    private Runnable duringFlush;
    private TransactionalSqlSession session;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        transactionManager.setTransactionTimeout(0);
        SqlSession delegate = (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(),
                new Class<?>[] { SqlSession.class }, (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return "Instrumented SqlSession";
                    }
                    events.add(method.getName().equals("getConnection") ? "delegate" : method.getName());
                    if (method.getName().equals("close")) {
                        assertTrue(sessionClosed.compareAndSet(false, true), "Session closed twice");
                        closes.incrementAndGet();
                        closingThread = Thread.currentThread();
                        closed.countDown();
                        if (closeFailure != null) {
                            throw closeFailure;
                        }
                    } else {
                        assertTrue(!sessionClosed.get(), "Operation on closed session: " + method.getName());
                        if (method.getName().equals("commit") && commitFailure != null) {
                            throw commitFailure;
                        }
                        if (method.getName().equals("flushStatements")) {
                            if (duringFlush != null) {
                                duringFlush.run();
                            }
                            if (flushFailure != null) {
                                throw flushFailure;
                            }
                        }
                        if (method.getName().equals("rollback")) {
                            rollbackForces.add(args == null ? null : (Boolean) args[0]);
                        }
                        if (method.getName().equals("rollback") && rollbackFailure != null) {
                            throw rollbackFailure;
                        }
                        if (method.getName().equals("getConnection") && waitForTimeout) {
                            assertTrue(completed.await(10, TimeUnit.SECONDS), "Transaction Reaper did not complete cleanup");
                            assertTrue(sessionMap().isEmpty(), "Completion must remove the entry while the call is in flight");
                            assertEquals(0, closes.get(), "Session closed while an invocation was still using it");
                        }
                        if (method.getName().equals("getConnection") && invocationFailure != null) {
                            throw invocationFailure;
                        }
                        if (method.getName().equals("getConnection") && duringInvocation != null) {
                            duringInvocation.run();
                        }
                    }
                    return null;
                });
        factory = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getConfiguration")) {
                        return configuration;
                    }
                    if (!method.getName().equals("openSession")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    sessionAutoCommit = args == null ? null : (Boolean) args[0];
                    opens.incrementAndGet();
                    if (duringOpen != null) {
                        duringOpen.run();
                    }
                    if (openFailure != null) {
                        throw openFailure;
                    }
                    return delegate;
                });
        session = new TransactionalSqlSession(factory, transactionManager);
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
    void commitClosesSharedSessionOnceAndRemovesEntry() throws Exception {
        transactionManager.begin();
        session.getConnection();
        session.getConnection();
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        assertEquals(1, sessionMap().size());

        transactionManager.commit();

        assertCleanedUp();
        assertEquals(Boolean.TRUE, sessionAutoCommit);
        assertEquals(List.of("delegate", "clearCache", "delegate", "clearCache", "commit", "close"), events);
    }

    @Test
    void rollbackClosesSessionAndRemovesEntry() throws Exception {
        transactionManager.begin();
        session.getConnection();

        transactionManager.rollback();

        assertCleanedUp();
        assertEquals(List.of("delegate", "clearCache", "rollback", "close"), events);
        assertEquals(List.of(true), rollbackForces);
    }

    @Test
    void rollbackOnlyClosesSessionAndRemovesEntry() throws Exception {
        transactionManager.begin();
        session.getConnection();
        transactionManager.setRollbackOnly();

        assertThrows(RollbackException.class, transactionManager::commit);

        assertCleanedUp();
    }

    @Test
    void timeoutAfterInvocationClosesSessionAndRemovesEntry() throws Exception {
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();
        session.getConnection();

        assertTrue(closed.await(10, TimeUnit.SECONDS), "Transaction Reaper did not close the session");

        assertNotSame(Thread.currentThread(), closingThread);
        assertCleanedUp();
    }

    @Test
    void timeoutDuringInvocationClosesSessionOnceAndRemovesEntry() throws Exception {
        beginTimeoutDuringInvocation();

        session.getConnection();

        assertNotSame(Thread.currentThread(), completionThread);
        assertSame(Thread.currentThread(), closingThread);
        assertCleanedUp();
        assertEquals(List.of("delegate", "rollback", "close"), events);
    }

    @Test
    void timeoutDuringFailingInvocationClosesSessionAfterRollback() throws Exception {
        invocationFailure = new IllegalStateException("Invocation failed");
        beginTimeoutDuringInvocation();

        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertSame(Thread.currentThread(), closingThread);
        assertCleanedUp();
        assertEquals(List.of("delegate", "rollback", "close"), events);
    }

    @Test
    void deferredCloseFailureDoesNotRetainEntryOrCloseTwice() throws Exception {
        closeFailure = new IllegalStateException("Session close failed");
        beginTimeoutDuringInvocation();

        assertSame(closeFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertCleanedUp();
    }

    @Test
    void timeoutCleanupFailuresDoNotMaskInvocationFailure() throws Exception {
        invocationFailure = new IllegalStateException("Invocation failed");
        rollbackFailure = new IllegalStateException("Session rollback failed");
        closeFailure = new IllegalStateException("Session close failed");
        beginTimeoutDuringInvocation();

        RuntimeException failure = assertThrows(IllegalStateException.class, session::getConnection);

        assertSame(invocationFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(rollbackFailure, failure.getSuppressed()[0]);
        assertSame(closeFailure, rollbackFailure.getSuppressed()[0]);
        assertCleanedUp();
    }

    @Test
    void afterCompletionCloseFailureDoesNotVetoCommitOrRetainEntry() throws Exception {
        closeFailure = new IllegalStateException("Session close failed");
        transactionManager.begin();
        session.getConnection();

        transactionManager.commit();

        assertCleanedUp();
    }

    @Test
    void registrationFailureClosesUncachedSession() throws Exception {
        transactionManager.begin();
        transactionManager.setRollbackOnly();

        RuntimeException failure = assertThrows(RuntimeException.class, session::getConnection);

        assertTrue(failure.getCause() instanceof RollbackException);
        assertEquals(List.of("rollback", "close"), events);
        assertEquals(List.of(true), rollbackForces);
        assertCleanedUp();
    }

    @Test
    void registrationFailureStillClosesWhenRollbackFailsAndPreservesBothCleanupFailures() throws Exception {
        rollbackFailure = new IllegalStateException("Rollback cleanup failed");
        closeFailure = new IllegalStateException("Close cleanup failed");
        transactionManager.begin();
        transactionManager.setRollbackOnly();

        RuntimeException failure = assertThrows(RuntimeException.class, session::getConnection);

        assertTrue(failure.getCause() instanceof RollbackException);
        assertEquals(List.of(rollbackFailure, closeFailure), List.of(failure.getCause().getSuppressed()));
        assertEquals(List.of("rollback", "close"), events);
        assertEquals(List.of(true), rollbackForces);
        assertCleanedUp();
    }

    @Test
    void registrationFailurePreservesCloseFailureAsSuppressed() throws Exception {
        closeFailure = new IllegalStateException("Session close failed");
        transactionManager.begin();
        transactionManager.setRollbackOnly();

        RuntimeException failure = assertThrows(RuntimeException.class, session::getConnection);

        assertTrue(failure.getCause() instanceof RollbackException);
        assertEquals(closeFailure, failure.getCause().getSuppressed()[0]);
        assertCleanedUp();
    }

    @Test
    void nonTransactionalInvocationClosesSession() throws Exception {
        session.getConnection();

        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "close"), events);
    }

    @Test
    void openFailurePreservesOriginalException() throws Exception {
        openFailure = new IllegalStateException("Cannot open session");
        transactionManager.begin();

        assertSame(openFailure, assertThrows(IllegalStateException.class, session::getConnection));

        transactionManager.rollback();
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        assertTrue(sessionMap().isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void jtaOpenerFailureInvalidatesPartialCacheWorkAndRestoresScope() throws Exception {
        configuration = new MyBatisConfiguration();
        Cache cache = new PerpetualCache("opening-cache");
        configuration.addCache(cache);
        configuration.addMappedStatement(new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, "read",
                new org.apache.ibatis.builder.StaticSqlSource(configuration, "SELECT 1"),
                org.apache.ibatis.mapping.SqlCommandType.SELECT).cache(cache).build());
        session = new TransactionalSqlSession(factory, transactionManager);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot older = coordinator.snapshot(configuration);
        Cache guarded = configuration.getMappedStatement("read").getCache();
        cache.putObject("warm", "old");
        duringOpen = () -> guarded.putObject("partial", "uncommitted");
        openFailure = new IllegalStateException("Opening failed after cache access");
        transactionManager.begin();

        assertSame(openFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertEquals(0, cache.getSize());
        assertTrue(sessionMap().isEmpty());
        assertEquals(0, closes.get(), "An opener that returns no session owns its resource cleanup");
        assertEquals(Status.STATUS_MARKED_ROLLBACK, transactionManager.getStatus());
        transactionManager.rollback();
        guarded.putObject("outside", "committed");
        assertEquals("committed", cache.getObject("outside"), "Failed opening must restore the previous thread scope");
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(older)) {
            assertFalse(completion.canPublish());
            completion.success();
        }
    }

    @Test
    void customFactoryOpenFailureInvalidatesEvenWithoutCacheAccess() {
        Cache cache = new PerpetualCache("warm-cache");
        configuration.addCache(cache);
        cache.putObject("key", "committed");
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot older = coordinator.snapshot(configuration);
        openFailure = new IllegalStateException("Pool unavailable before any SQL");

        assertSame(openFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertNull(cache.getObject("key"));
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(older)) {
            assertFalse(completion.canPublish(), "A custom opener may have executed SQL before failing");
            completion.success();
        }
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void standardOpenerFailureBeforeAnySqlPreservesCacheAndGeneration() {
        Configuration standard = new MyBatisConfiguration();
        Cache cache = new PerpetualCache("warm-cache");
        standard.addCache(cache);
        cache.putObject("key", "committed");
        SqlSessionFactory standardFactory = new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(standard);
        TransactionalSqlSession facade = new TransactionalSqlSession(standardFactory, null);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(standard);
        SqlSessionCacheCoordinator.Snapshot older = coordinator.snapshot(standard);

        // The missing Environment fails in DefaultSqlSessionFactory before it can construct a transaction.
        assertTrue(TransactionalSqlSession.opensWithoutSql(standardFactory));
        assertThrows(RuntimeException.class, facade::getConnection);

        assertEquals("committed", cache.getObject("key"));
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(older)) {
            assertTrue(completion.canPublish());
            completion.success();
        }
        assertFalse(TransactionalSqlSession.opensWithoutSql(factory));
        assertFalse(TransactionalSqlSession.opensWithoutSql(new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(
                new MyBatisConfiguration() {
                })));
    }

    @Test
    void failureAfterInvocationStartsStillEvictsAndClosesTheSession() throws Exception {
        Cache cache = new PerpetualCache("warm-cache");
        configuration.addCache(cache);
        cache.putObject("key", "committed");
        invocationFailure = new IllegalStateException("Failure after connection exposure");

        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertEquals(0, cache.getSize());
        assertCleanedUp();
        assertEquals(List.of("delegate", "rollback", "close"), events);
    }

    @Test
    void nonTransactionalCommitFailureRollsBackAndCloses() throws Exception {
        commitFailure = new IllegalStateException("Session commit failed");

        assertSame(commitFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "rollback", "close"), events);
    }

    @Test
    void batchFlushFailureIsReportedByInvocationAndDoesNotForceJtaRollback() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        flushFailure = new IllegalStateException("Session flush failed");
        transactionManager.begin();

        assertSame(flushFailure, assertThrows(IllegalStateException.class, session::getConnection));
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(List.of(false), rollbackForces);
        assertEquals(List.of("delegate", "clearCache", "flushStatements", "rollback"), events);
        flushFailure = null;
        transactionManager.commit();

        assertCleanedUp();
    }

    @Test
    void cacheCommitFailureStillClosesAndRemovesEntry() throws Exception {
        commitFailure = new IllegalStateException("Cache commit failed");
        transactionManager.begin();
        session.getConnection();

        // JTA has already committed when MyBatis publishes its cache; the failure cannot veto JDBC commit.
        transactionManager.commit();

        assertCleanedUp();
        assertEquals(List.of("delegate", "clearCache", "commit", "close"), events);
    }

    @Test
    void unknownOutcomeDiscardsCacheInsteadOfCommitting() throws Exception {
        useControlledTransaction(null, null);
        session.getConnection();

        synchronization.get().afterCompletion(Status.STATUS_UNKNOWN);
        synchronization.get().afterCompletion(Status.STATUS_UNKNOWN);

        assertCleanedUp();
        assertEquals(List.of("delegate", "clearCache", "rollback", "close"), events);
        assertEquals(List.of(true), rollbackForces);
    }

    @Test
    void preparationAllowsLaterInvocationsWithoutPublishingCache() throws Exception {
        useControlledTransaction(null, null);
        session.getConnection();

        synchronization.get().beforeCompletion();
        assertEquals(List.of("delegate", "clearCache"), events);
        assertEquals(1, sessionMap().size());
        session.getConnection();
        assertEquals(List.of("delegate", "clearCache", "delegate", "clearCache"), events);
        synchronization.get().afterCompletion(Status.STATUS_COMMITTED);

        assertCleanedUp();
    }

    @Test
    void batchCallsFlushBeforeReturningEvenBeforePreparation() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        transactionManager.begin();
        session.getConnection();
        assertEquals(List.of("delegate", "clearCache", "flushStatements"), events);

        session.getConnection();
        assertEquals(List.of("delegate", "clearCache", "flushStatements",
                "delegate", "clearCache", "flushStatements"), events);
        transactionManager.commit();

        assertCleanedUp();
    }

    @Test
    void nestedBatchCallsKeepTheirOwnFlushBoundary() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        useControlledTransaction(null, null);
        duringInvocation = () -> {
            duringInvocation = null;
            session.getConnection();
            assertEquals(List.of("delegate", "delegate", "clearCache", "flushStatements"), events);
        };

        session.getConnection();

        assertEquals(List.of("delegate", "delegate", "clearCache", "flushStatements",
                "clearCache", "flushStatements"), events);
        synchronization.get().afterCompletion(Status.STATUS_COMMITTED);
        assertCleanedUp();
    }

    @Test
    void completionDuringBatchInvocationSkipsFlush() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        useControlledTransaction(null, null);
        duringInvocation = () -> synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK);

        session.getConnection();

        assertEquals(List.of("delegate", "rollback", "close"), events);
        assertEquals(List.of(true), rollbackForces);
        assertCleanedUp();
    }

    @Test
    void completionDuringFailingBatchFlushPreservesOriginalFailure() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        useControlledTransaction(null, null);
        flushFailure = new IllegalStateException("Batch flush failed");
        rollbackFailure = new IllegalStateException("Cleanup rollback failed");
        closeFailure = new IllegalStateException("Close failed");
        duringFlush = () -> {
            synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK);
            assertEquals(0, closes.get(), "Completion must not close the session during its flush");
        };

        RuntimeException failure = assertThrows(IllegalStateException.class, session::getConnection);

        assertSame(flushFailure, failure);
        assertEquals(List.of(rollbackFailure), List.of(failure.getSuppressed()));
        assertEquals(List.of(closeFailure), List.of(rollbackFailure.getSuppressed()));
        assertEquals(List.of("delegate", "clearCache", "flushStatements", "rollback", "close"), events);
        assertCleanedUp();
    }

    @Test
    void preparationDuringInvocationDoesNotFlushOrVetoCommit() throws Exception {
        useControlledTransaction(null, null);
        duringInvocation = () -> {
            synchronization.get().beforeCompletion();
            assertEquals(List.of("delegate"), events);
            synchronization.get().afterCompletion(Status.STATUS_COMMITTED);
            assertEquals(0, closes.get());
        };

        session.getConnection();

        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "close"), events);
    }

    @Test
    void failedBatchCleanupMarksRollbackOnlyAndPreservesOriginalFailure() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.BATCH);
        invocationFailure = new IllegalStateException("Mapper failed");
        rollbackFailure = new IllegalStateException("Cannot discard queued batch");
        transactionManager.begin();

        RuntimeException failure = assertThrows(IllegalStateException.class, session::getConnection);

        assertSame(invocationFailure, failure);
        assertEquals(List.of(rollbackFailure), List.of(failure.getSuppressed()));
        assertEquals(Status.STATUS_MARKED_ROLLBACK, transactionManager.getStatus());
        rollbackFailure = null;
        assertThrows(RollbackException.class, transactionManager::commit);
        assertCleanedUp();
    }

    @Test
    void nonTransactionalCleanupFailuresDoNotMaskInvocationFailure() throws Exception {
        invocationFailure = new IllegalStateException("Invocation failed");
        rollbackFailure = new IllegalStateException("Session rollback failed");
        closeFailure = new IllegalStateException("Session close failed");

        RuntimeException failure = assertThrows(IllegalStateException.class, session::getConnection);

        assertSame(invocationFailure, failure);
        assertEquals(List.of(rollbackFailure, closeFailure), List.of(failure.getSuppressed()));
        assertCleanedUp();
        assertEquals(List.of("delegate", "rollback", "close"), events);
    }

    @Test
    void completionBeforeAcquireRejectsInvocation() throws Exception {
        Cache cache = new PerpetualCache("acquire-race");
        configuration.addCache(cache);
        cache.putObject("warm", "committed");
        useControlledTransaction(null, null);
        PausingSessionMap map = installPausingMap();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> invocation = executor.submit(() -> session.getConnection());
            await(map.retrieved);

            synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK);
            assertCleanedUp();
            map.allowAcquire.countDown();

            assertRejected(invocation);
            assertEquals(List.of("rollback", "close"), events);
            assertCleanedUp();
            assertEquals("committed", cache.getObject("warm"), "A rejected use must not install a write fence");
            var coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
            try (var completion = coordinator.begin(coordinator.snapshot(configuration))) {
                assertTrue(completion.canPublish(), "A rejected use must not leave a pending invalidation");
                completion.success();
            }
        } finally {
            map.allowAcquire.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void completionDuringRegistrationDoesNotLoseUnpublishedSession() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        useControlledTransaction(registered, allowPublication);
        PausingSessionMap map = installPausingMap();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> invocation = executor.submit(() -> session.getConnection());
            await(registered);
            assertTrue(map.isEmpty(), "Session must not have been published yet");
            Future<?> completion = executor.submit(() -> synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK));
            await(map.removalStarted);

            // ConcurrentHashMap.remove may wait for the mapping function; never wait for it inside that function.
            allowPublication.countDown();
            await(map.retrieved);
            completion.get(10, TimeUnit.SECONDS);
            assertCleanedUp();
            map.allowAcquire.countDown();

            assertRejected(invocation);
            synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK);
            assertCleanedUp();
            assertEquals(List.of("rollback", "close"), events);
        } finally {
            allowPublication.countDown();
            map.allowAcquire.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertRejected(Future<?> invocation) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> invocation.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("Transaction completed before the SqlSession could be acquired", failure.getCause().getMessage());
    }

    @Test
    void caughtInvocationFailureKeepsJtaActiveAndRetainsPendingCacheState() throws Exception {
        invocationFailure = new IllegalStateException("Mapper failed");
        transactionManager.begin();

        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(List.of(false), rollbackForces);
        assertEquals(List.of("delegate", "rollback"), events);
        assertEquals(0, closes.get());
        transactionManager.commit();

        assertEquals(List.of("delegate", "rollback", "commit", "close"), events);
        assertCleanedUp();
    }

    @Test
    void nonManagedFactoryKeepsPerInvocationCommitAndPreparationClose() throws Exception {
        configuration = new Configuration();
        configuration.setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(), new UnpooledDataSource()));
        configuration.addCache(new BlockingCache(new PerpetualCache("nonManaged")));
        useControlledTransaction(null, null);

        session.getConnection();
        session.getConnection();

        assertNull(sessionAutoCommit, "Non-managed factories must keep the original openSession() behavior");
        assertEquals(List.of("delegate", "commit", "delegate", "commit"), events);
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        synchronization.get().beforeCompletion();
        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "delegate", "commit", "close"), events);

        synchronization.get().afterCompletion(Status.STATUS_COMMITTED);
        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "delegate", "commit", "close"), events);
    }

    @Test
    void nonManagedFactoryDefersPreparationCloseUntilInvocationExits() throws Exception {
        configuration.setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(), new UnpooledDataSource()));
        useControlledTransaction(null, null);
        duringInvocation = () -> {
            synchronization.get().beforeCompletion();
            assertEquals(0, closes.get());
            synchronization.get().afterCompletion(Status.STATUS_COMMITTED);
            assertEquals(0, closes.get());
        };

        session.getConnection();

        assertEquals(List.of("delegate", "commit", "close"), events);
        assertCleanedUp();
    }

    @Test
    void nonManagedFactoryKeepsPerInvocationRollbackWithoutMarkingJta() throws Exception {
        configuration.setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(), new UnpooledDataSource()));
        invocationFailure = new IllegalStateException("Mapper failed");
        transactionManager.begin();

        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertNull(sessionAutoCommit, "Non-managed factories must keep the original openSession() behavior");
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        assertEquals(List.of("delegate", "rollback"), events);
        assertEquals(List.of(true), rollbackForces, "Invocation cleanup must discard even read-only cache entries");
        transactionManager.commit();

        assertEquals(List.of("delegate", "rollback", "close"), events);
        assertCleanedUp();
    }

    @Test
    void failedJdbcRollbackRetiresSessionBeforeItCanCommitAgain() throws Exception {
        configuration.setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(), new UnpooledDataSource()));
        invocationFailure = new IllegalStateException("Mapper failed");
        rollbackFailure = new IllegalStateException("Rollback intercepted before reaching MyBatis");
        transactionManager.begin();
        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));
        assertEquals(List.of(rollbackFailure), List.of(invocationFailure.getSuppressed()));
        invocationFailure = null;
        assertThrows(IllegalStateException.class, session::getConnection);
        assertEquals(List.of("delegate", "rollback", "close"), events);
        assertEquals(Status.STATUS_ACTIVE, transactionManager.getStatus());
        transactionManager.commit();
        assertCleanedUp();
    }

    @Test
    void nonManagedFactoryClosesOnJtaRollbackWithoutCompletingSessionAgain() throws Exception {
        configuration.setEnvironment(new Environment("jdbc", new JdbcTransactionFactory(), new UnpooledDataSource()));
        transactionManager.begin();
        session.getConnection();

        transactionManager.rollback();

        assertEquals(List.of("delegate", "commit", "close"), events);
        assertTrue(rollbackForces.isEmpty());
        assertCleanedUp();
    }

    @Test
    void failureToMarkCleanupFailureRollbackOnlyPreservesAllExceptions() throws Exception {
        useControlledTransaction(null, null);
        invocationFailure = new IllegalStateException("Mapper failed");
        rollbackFailure = new IllegalStateException("Cannot discard queued batch");
        markRollbackFailure = new IllegalStateException("Cannot mark rollback-only");

        RuntimeException failure = assertThrows(IllegalStateException.class, session::getConnection);

        assertSame(invocationFailure, failure);
        assertEquals(List.of(rollbackFailure), List.of(failure.getSuppressed()));
        assertEquals(List.of(markRollbackFailure), List.of(rollbackFailure.getSuppressed()));
        rollbackFailure = null;
        synchronization.get().afterCompletion(Status.STATUS_ROLLEDBACK);
        assertCleanedUp();
    }

    private void useControlledTransaction(CountDownLatch registered, CountDownLatch allowPublication) {
        AtomicBoolean rollbackOnly = new AtomicBoolean();
        Transaction transaction = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                new Class<?>[] { Transaction.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getStatus":
                            return rollbackOnly.get() ? Status.STATUS_MARKED_ROLLBACK : Status.STATUS_ACTIVE;
                        case "setRollbackOnly":
                            if (markRollbackFailure != null) {
                                throw markRollbackFailure;
                            }
                            rollbackOnly.set(true);
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "registerSynchronization":
                            synchronization.set((Synchronization) args[0]);
                            if (registered != null) {
                                registered.countDown();
                                await(allowPublication);
                            }
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        TransactionManager manager = (TransactionManager) Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),
                new Class<?>[] { TransactionManager.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getTransaction")) {
                        return transaction;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        session = new TransactionalSqlSession(factory, manager);
    }

    private PausingSessionMap installPausingMap() throws Exception {
        PausingSessionMap map = new PausingSessionMap();
        sessionMapField().set(interceptor(), map);
        return map;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for controlled ordering");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class PausingSessionMap extends ConcurrentHashMap<Transaction, Object> {
        final CountDownLatch retrieved = new CountDownLatch(1);
        final CountDownLatch allowAcquire = new CountDownLatch(1);
        final CountDownLatch removalStarted = new CountDownLatch(1);

        @Override
        public Object computeIfAbsent(Transaction transaction, Function<? super Transaction, ?> mappingFunction) {
            Object holder = super.computeIfAbsent(transaction, mappingFunction);
            retrieved.countDown();
            await(allowAcquire);
            return holder;
        }

        @Override
        public Object remove(Object transaction) {
            removalStarted.countDown();
            return super.remove(transaction);
        }
    }

    private void assertCleanedUp() throws Exception {
        assertTrue(sessionMap().isEmpty(), "Completed transaction still retains its SqlSession");
        assertEquals(1, closes.get());
    }

    private void beginTimeoutDuringInvocation() throws Exception {
        waitForTimeout = true;
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();
        Transaction transaction = transactionManager.getTransaction();
        // Observe the real Reaper callback after cleanup returns, without waiting for session.close().
        Transaction observedTransaction = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
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
                                    completionThread = Thread.currentThread();
                                    completed.countDown();
                                }
                            }
                        });
                        return null;
                    }
                    return method.invoke(transaction, args);
                });
        TransactionManager observedManager = (TransactionManager) Proxy.newProxyInstance(
                TransactionManager.class.getClassLoader(), new Class<?>[] { TransactionManager.class },
                (proxy, method, args) -> method.getName().equals("getTransaction") ? observedTransaction
                        : method.invoke(transactionManager, args));
        session = new TransactionalSqlSession(factory, observedManager);
    }

    private Map<?, ?> sessionMap() throws Exception {
        return (Map<?, ?>) sessionMapField().get(interceptor());
    }

    private Object interceptor() throws Exception {
        Field proxyField = TransactionalSqlSession.class.getDeclaredField("sqlSessionProxy");
        proxyField.setAccessible(true);
        return Proxy.getInvocationHandler(proxyField.get(session));
    }

    private Field sessionMapField() throws Exception {
        Field mapField = interceptor().getClass().getDeclaredField("sqlSessionMap");
        mapField.setAccessible(true);
        return mapField;
    }
}
