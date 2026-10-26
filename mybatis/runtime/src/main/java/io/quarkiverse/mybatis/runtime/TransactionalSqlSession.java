package io.quarkiverse.mybatis.runtime;

import static java.lang.reflect.Proxy.newProxyInstance;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

public class TransactionalSqlSession implements SqlSession {
    private final SqlSessionFactory sqlSessionFactory;
    private final TransactionManager transactionManager;

    private final SqlSession sqlSessionProxy;

    public TransactionalSqlSession(SqlSessionFactory sqlSessionFactory, TransactionManager transactionManager) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.transactionManager = transactionManager;
        this.sqlSessionProxy = (SqlSession) newProxyInstance(SqlSession.class.getClassLoader(),
                new Class[] { SqlSession.class }, new TransactionalSqlSessionInterceptor());
    }

    @Override
    public <T> T selectOne(String statement) {
        return this.sqlSessionProxy.selectOne(statement);
    }

    @Override
    public <T> T selectOne(String statement, Object parameter) {
        return this.sqlSessionProxy.selectOne(statement, parameter);
    }

    @Override
    public <E> List<E> selectList(String statement) {
        return this.sqlSessionProxy.selectList(statement);
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter) {
        return this.sqlSessionProxy.selectList(statement, parameter);
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
        return this.sqlSessionProxy.selectList(statement, parameter, rowBounds);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
        return this.sqlSessionProxy.selectMap(statement, mapKey);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
        return this.sqlSessionProxy.selectMap(statement, parameter, mapKey);
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
        return this.sqlSessionProxy.selectMap(statement, parameter, mapKey, rowBounds);
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement) {
        return this.sqlSessionProxy.selectCursor(statement);
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement, Object parameter) {
        return this.sqlSessionProxy.selectCursor(statement, parameter);
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
        return this.sqlSessionProxy.selectCursor(statement, parameter, rowBounds);
    }

    @Override
    public void select(String statement, Object parameter, ResultHandler handler) {
        this.sqlSessionProxy.select(statement, parameter, handler);
    }

    @Override
    public void select(String statement, ResultHandler handler) {
        this.sqlSessionProxy.select(statement, handler);
    }

    @Override
    public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
        this.sqlSessionProxy.select(statement, parameter, rowBounds, handler);
    }

    @Override
    public int insert(String statement) {
        return this.sqlSessionProxy.insert(statement);
    }

    @Override
    public int insert(String statement, Object parameter) {
        return this.sqlSessionProxy.insert(statement, parameter);
    }

    @Override
    public int update(String statement) {
        return this.sqlSessionProxy.update(statement);
    }

    @Override
    public int update(String statement, Object parameter) {
        return this.sqlSessionProxy.update(statement, parameter);
    }

    @Override
    public int delete(String statement) {
        return this.sqlSessionProxy.delete(statement);
    }

    @Override
    public int delete(String statement, Object parameter) {
        return this.sqlSessionProxy.delete(statement, parameter);
    }

    @Override
    public void commit() {
        throw new UnsupportedOperationException("Manual close is not allowed over a Transactional SqlSession");
    }

    @Override
    public void commit(boolean force) {
        throw new UnsupportedOperationException("Manual close is not allowed over a Transactional SqlSession");
    }

    @Override
    public void rollback() {
        throw new UnsupportedOperationException("Manual close is not allowed over a Transactional SqlSession");
    }

    @Override
    public void rollback(boolean force) {
        throw new UnsupportedOperationException("Manual close is not allowed over a Transactional SqlSession");
    }

    @Override
    public List<BatchResult> flushStatements() {
        return this.sqlSessionProxy.flushStatements();
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("Manual close is not allowed over a Transactional SqlSession");
    }

    @Override
    public void clearCache() {
        this.sqlSessionProxy.clearCache();
    }

    @Override
    public Configuration getConfiguration() {
        return this.sqlSessionFactory.getConfiguration();
    }

    @Override
    public <T> T getMapper(Class<T> type) {
        return getConfiguration().getMapper(type, this);
    }

    @Override
    public Connection getConnection() {
        return this.sqlSessionProxy.getConnection();
    }

    private class TransactionalSqlSessionInterceptor implements InvocationHandler {
        private final Map<Transaction, SqlSessionHolder> sqlSessionMap = new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Transaction transaction = getActiveTransaction();
            SqlSessionHolder holder = null;
            SqlSession sqlSession = null;
            Throwable failure = null;
            try {
                if (transaction == null) {
                    sqlSession = sqlSessionFactory.openSession();
                } else {
                    holder = getSessionHolder(transaction);
                    sqlSession = holder.acquire();
                }
                Object result = method.invoke(sqlSession, args);
                sqlSession.commit();
                return result;
            } catch (Throwable t) {
                failure = ExceptionUtil.unwrapThrowable(t);
                if (sqlSession != null) {
                    try {
                        sqlSession.rollback();
                    } catch (Throwable rollbackFailure) {
                        if (rollbackFailure != failure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                }
                throw failure;
            } finally {
                if (sqlSession != null) {
                    try {
                        if (holder == null) {
                            sqlSession.close();
                        } else {
                            holder.release();
                        }
                    } catch (Throwable closeFailure) {
                        if (failure == null) {
                            throw closeFailure;
                        }
                        if (closeFailure != failure) {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
            }
        }

        private SqlSessionHolder getSessionHolder(Transaction transaction) {
            return sqlSessionMap.computeIfAbsent(transaction,
                    (tx) -> {
                        SqlSession session = sqlSessionFactory.openSession();
                        try {
                            tx.registerSynchronization(new Synchronization() {
                                @Override
                                public void beforeCompletion() {
                                    closeSqlSession(tx);
                                }

                                @Override
                                public void afterCompletion(int status) {
                                    closeSqlSession(tx);
                                }
                            });
                        } catch (Exception e) {
                            try {
                                session.close();
                            } catch (Exception closeException) {
                                e.addSuppressed(closeException);
                            }
                            throw new RuntimeException(
                                    "Session " + session + " can not register synchronization in transaction ", e);
                        }
                        return new SqlSessionHolder(session);
                    });
        }

        private void closeSqlSession(Transaction transaction) {
            // Detach immediately; a Reaper callback must not close a session still in use or wait for the invocation.
            SqlSessionHolder holder = sqlSessionMap.remove(transaction);
            if (holder != null) {
                holder.requestClose();
            }
        }

        private Transaction getActiveTransaction() {
            try {
                if (transactionManager == null) {
                    return null;
                }
                Transaction tx = transactionManager.getTransaction();
                if (tx != null && (tx.getStatus() == Status.STATUS_ACTIVE || tx.getStatus() == Status.STATUS_MARKED_ROLLBACK)) {
                    return tx;
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        }
    }

    private static class SqlSessionHolder {
        private final SqlSession session;
        private int invocations;
        private boolean closeRequested;

        private SqlSessionHolder(SqlSession session) {
            this.session = session;
        }

        private synchronized SqlSession acquire() {
            if (closeRequested) {
                throw new IllegalStateException("Transaction completed before the SqlSession could be acquired");
            }
            invocations++;
            return session;
        }

        private void release() {
            boolean close;
            synchronized (this) {
                close = --invocations == 0 && closeRequested;
            }
            if (close) {
                session.close();
            }
        }

        private void requestClose() {
            boolean close;
            synchronized (this) {
                if (closeRequested) {
                    return;
                }
                closeRequested = true;
                close = invocations == 0;
            }
            if (close) {
                session.close();
            }
        }
    }
}
