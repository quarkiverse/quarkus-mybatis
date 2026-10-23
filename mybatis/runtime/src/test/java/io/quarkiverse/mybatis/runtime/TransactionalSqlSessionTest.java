package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
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
    private volatile Thread closingThread;
    private boolean waitForTimeout;
    private RuntimeException closeFailure;
    private TransactionalSqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        transactionManager.setTransactionTimeout(0);
        SqlSession delegate = (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(),
                new Class<?>[] { SqlSession.class }, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        closingThread = Thread.currentThread();
                        closed.countDown();
                        if (closeFailure != null) {
                            throw closeFailure;
                        }
                    } else if (method.getName().equals("getConnection") && waitForTimeout) {
                        assertTrue(closed.await(10, TimeUnit.SECONDS), "Timed-out transaction did not close its session");
                    }
                    return null;
                });
        SqlSessionFactory factory = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
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
        waitForTimeout = true;
        transactionManager.setTransactionTimeout(1);
        transactionManager.begin();

        session.getConnection();

        assertNotSame(Thread.currentThread(), closingThread);
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

    private Map<?, ?> sessionMap() throws Exception {
        Field proxyField = TransactionalSqlSession.class.getDeclaredField("sqlSessionProxy");
        proxyField.setAccessible(true);
        Object interceptor = Proxy.getInvocationHandler(proxyField.get(session));
        Field mapField = interceptor.getClass().getDeclaredField("sqlSessionMap");
        mapField.setAccessible(true);
        return (Map<?, ?>) mapField.get(interceptor);
    }
}
