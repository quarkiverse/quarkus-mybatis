package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private volatile Thread closingThread;
    private volatile Thread completionThread;
    private boolean waitForTimeout;
    private RuntimeException invocationFailure;
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
                        if (method.getName().equals("rollback") && rollbackFailure != null) {
                            throw rollbackFailure;
                        }
                        if (method.getName().equals("getConnection") && waitForTimeout) {
                            assertTrue(completed.await(10, TimeUnit.SECONDS), "Transaction Reaper did not complete cleanup");
                            assertTrue(sessionMap().isEmpty(), "Completion must remove the entry while the call is in flight");
                            assertEquals(0, closes.get(), "Session closed while an invocation was still using it");
                            if (invocationFailure != null) {
                                throw invocationFailure;
                            }
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
    }

    @Test
    void timeoutDuringFailingInvocationClosesSessionAfterRollback() throws Exception {
        invocationFailure = new IllegalStateException("Invocation failed");
        beginTimeoutDuringInvocation();

        assertSame(invocationFailure, assertThrows(IllegalStateException.class, session::getConnection));

        assertSame(Thread.currentThread(), closingThread);
        assertCleanedUp();
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
        Field proxyField = TransactionalSqlSession.class.getDeclaredField("sqlSessionProxy");
        proxyField.setAccessible(true);
        Object interceptor = Proxy.getInvocationHandler(proxyField.get(session));
        Field mapField = interceptor.getClass().getDeclaredField("sqlSessionMap");
        mapField.setAccessible(true);
        return (Map<?, ?>) mapField.get(interceptor);
    }
}
