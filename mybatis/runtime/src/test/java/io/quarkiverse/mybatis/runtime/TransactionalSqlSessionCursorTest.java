package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

class TransactionalSqlSessionCursorTest {
    @Test
    void simpleCursorReadKeepsSessionAliveUntilItReturns() throws Exception {
        checkReadLifetime(ExecutorType.SIMPLE);
    }

    @Test
    void reuseCursorReadKeepsSessionAliveUntilItReturns() throws Exception {
        checkReadLifetime(ExecutorType.REUSE);
    }

    private void checkReadLifetime(ExecutorType type) throws Exception {
        Fixture f = new Fixture(type);
        try (Cursor<Integer> cursor = f.facade.selectCursor("cursor")) {
            f.read = () -> {
                f.complete();
                assertEquals(0, f.mainCloses, "Completion must defer close while the cursor reads");
                assertEquals(0, f.cursorCloses);
            };
            assertFalse(cursor.iterator().hasNext());
        }
        assertEquals(1, f.mainCloses);
        assertEquals(type == ExecutorType.REUSE ? 1 : 0, f.cursorCloses);
    }

    @Test
    void cursorCloseKeepsConnectionAliveUntilItReturns() throws Exception {
        Fixture f = new Fixture(ExecutorType.REUSE);
        Cursor<Integer> cursor = f.facade.selectCursor("cursor");
        f.closing = () -> {
            f.complete();
            assertEquals(0, f.mainCloses, "Cursor.close also uses the connection");
        };
        cursor.close();
        assertEquals(1, f.mainCloses);
        assertEquals(1, f.cursorCloses);
        cursor.close();
        assertEquals(1, f.mainCloses);
    }

    @Test
    void cursorConnectionAcquisitionDoesNotBlockCompletion() throws Exception {
        Fixture f = new Fixture(ExecutorType.REUSE);
        var executor = Executors.newSingleThreadExecutor();
        try {
            f.connecting = () -> {
                try {
                    executor.submit(f::complete).get(2, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("Completion must not wait for connection acquisition", failure);
                }
                assertEquals(0, f.mainCloses);
            };
            f.facade.selectCursor("cursor");
            assertEquals(1, f.mainCloses);
            assertEquals(1, f.cursorCloses);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedCursorOpeningStillReleasesItsSessionUse() {
        Fixture f = new Fixture(ExecutorType.REUSE);
        f.connecting = f::complete;
        f.openFailure = new IllegalStateException("Cursor opener failed");
        assertSame(f.openFailure, assertThrows(IllegalStateException.class, () -> f.facade.selectCursor("cursor")));
        assertEquals(1, f.mainCloses);
    }

    @Test
    void cursorReadFailureKeepsCleanupFailureSuppressed() {
        Fixture f = new Fixture(ExecutorType.SIMPLE);
        Cursor<Integer> cursor = f.facade.selectCursor("cursor");
        var original = new IllegalStateException("Row mapping failed");
        f.closeFailure = new IllegalStateException("Session cleanup failed");
        f.read = () -> {
            f.complete();
            throw original;
        };
        var failure = assertThrows(IllegalStateException.class, () -> cursor.iterator().hasNext());
        assertSame(original, failure);
        assertEquals(List.of(f.closeFailure), List.of(failure.getSuppressed()));
        assertEquals(1, f.mainCloses);
    }

    @Test
    void cursorCacheAccessUsesItsOwnerAndRestoresTheOuterScope() throws Exception {
        Fixture f = new Fixture(ExecutorType.REUSE);
        var coordinator = SqlSessionCacheCoordinator.forConfiguration(f.configuration);
        Cache guarded = f.configuration.getMappedStatement("cursor").getCache();
        f.cache.putObject("key", new ArrayList<>(List.of(2)));
        try (Cursor<Integer> cursor = f.facade.selectCursor("cursor");
                var outer = coordinator.enter(coordinator.snapshot(f.configuration))) {
            f.read = () -> assertNull(guarded.getObject("key"), "Deferred reads must not borrow the outer scope");
            assertFalse(cursor.iterator().hasNext());
            assertEquals(List.of(2), guarded.getObject("key"), "The outer scope must be restored");
        } finally {
            f.complete();
        }
    }

    private static final class Fixture {
        final Configuration configuration = new MyBatisConfiguration();
        final Cache cache = new PerpetualCache("cursor-cache");
        final TransactionalSqlSession facade;
        Synchronization synchronization;
        Runnable connecting = () -> {
        };
        Runnable read = () -> {
        };
        Runnable closing = () -> {
        };
        RuntimeException openFailure;
        RuntimeException closeFailure;
        int mainCloses;
        int cursorCloses;
        boolean consumed;
        boolean closed;

        Fixture(ExecutorType type) {
            configuration.setDefaultExecutorType(type);
            configuration.addCache(cache);
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, "cursor",
                    new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
            Transaction tx = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                    new Class<?>[] { Transaction.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getStatus" -> Status.STATUS_ACTIVE;
                        case "registerSynchronization" -> {
                            synchronization = (Synchronization) args[0];
                            yield null;
                        }
                        case "setRollbackOnly" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            TransactionManager manager = (TransactionManager) Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),
                    new Class<?>[] { TransactionManager.class }, (proxy, method, args) -> tx);
            SqlSessionFactory factory = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                    new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getConfiguration"))
                            return configuration;
                        boolean borrowed = args[0] instanceof ExecutorType;
                        if (borrowed && openFailure != null)
                            throw openFailure;
                        return session(borrowed);
                    });
            facade = new TransactionalSqlSession(factory, manager);
        }

        void complete() {
            synchronization.afterCompletion(Status.STATUS_ROLLEDBACK);
        }

        SqlSession session(boolean borrowed) {
            return (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(), new Class<?>[] { SqlSession.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "getConnection":
                                connecting.run();
                                return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                        new Class<?>[] { Connection.class },
                                        (connection, call, parameters) -> null);
                            case "selectCursor":
                                return cursor;
                            case "close":
                                if (borrowed)
                                    cursorCloses++;
                                else
                                    mainCloses++;
                                if (borrowed || configuration.getDefaultExecutorType() != ExecutorType.REUSE)
                                    cursor.close();
                                if (!borrowed && closeFailure != null)
                                    throw closeFailure;
                                return null;
                            default:
                                return null;
                        }
                    });
        }

        final Cursor<Integer> cursor = new Cursor<>() {
            public boolean isOpen() {
                return !closed;
            }

            public boolean isConsumed() {
                return consumed;
            }

            public int getCurrentIndex() {
                return -1;
            }

            public void close() {
                if (!closed) {
                    closed = true;
                    closing.run();
                }
            }

            public Iterator<Integer> iterator() {
                return new Iterator<>() {
                    public boolean hasNext() {
                        read.run();
                        consumed = true;
                        return false;
                    }

                    public Integer next() {
                        throw new java.util.NoSuchElementException();
                    }
                };
            }
        };
    }
}
