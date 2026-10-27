package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
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
    private volatile Thread closingThread;
    private volatile Thread completionThread;
    private boolean waitForTimeout;
    private RuntimeException invocationFailure;
    private RuntimeException openFailure;
    private RuntimeException commitFailure;
    private RuntimeException rollbackFailure;
    private RuntimeException closeFailure;
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
                    }
                    return null;
                });
        factory = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("openSession")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    opens.incrementAndGet();
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
        assertEquals(List.of("delegate", "commit", "delegate", "commit", "close"), events);
    }

    @Test
    void rollbackClosesSessionAndRemovesEntry() throws Exception {
        transactionManager.begin();
        session.getConnection();

        transactionManager.rollback();

        assertCleanedUp();
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
        assertEquals(List.of("delegate", "commit", "close"), events);
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
        assertEquals(2, failure.getSuppressed().length);
        assertSame(rollbackFailure, failure.getSuppressed()[0]);
        assertSame(closeFailure, failure.getSuppressed()[1]);
        assertCleanedUp();
    }

    @Test
    void closeFailureDoesNotRetainEntryOrCloseTwice() throws Exception {
        closeFailure = new IllegalStateException("Session close failed");
        transactionManager.begin();
        session.getConnection();

        assertThrows(RollbackException.class, transactionManager::commit);

        assertCleanedUp();
    }

    @Test
    void registrationFailureClosesUncachedSession() throws Exception {
        transactionManager.begin();
        transactionManager.setRollbackOnly();

        RuntimeException failure = assertThrows(RuntimeException.class, session::getConnection);

        assertTrue(failure.getCause() instanceof RollbackException);
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
    void nonTransactionalCommitFailureRollsBackAndCloses() throws Exception {
        commitFailure = new IllegalStateException("Session commit failed");

        assertSame(commitFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "rollback", "close"), events);
    }

    @Test
    void transactionalCommitFailureReleasesSessionForCompletion() throws Exception {
        commitFailure = new IllegalStateException("Session commit failed");
        transactionManager.begin();

        assertSame(commitFailure, assertThrows(IllegalStateException.class, session::getConnection));
        assertEquals(0, closes.get());
        assertEquals(1, sessionMap().size());
        transactionManager.rollback();

        assertCleanedUp();
        assertEquals(List.of("delegate", "commit", "rollback", "close"), events);
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
            assertEquals(List.of("close"), events);
            assertCleanedUp();
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
            assertEquals(List.of("close"), events);
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

    private void useControlledTransaction(CountDownLatch registered, CountDownLatch allowPublication) {
        Transaction transaction = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                new Class<?>[] { Transaction.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getStatus":
                            return Status.STATUS_ACTIVE;
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
