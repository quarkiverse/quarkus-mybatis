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
        private final Map<Transaction, SqlSession> sqlSessionMap = new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Transaction transaction = getActiveTransaction();
            SqlSession sqlSession = null;
            try {
                sqlSession = getSqlSession(transaction);
                Object result = method.invoke(sqlSession, args);
                sqlSession.commit();
                return result;
            } catch (Throwable t) {
                if (sqlSession != null) {
                    sqlSession.rollback();
                }
                throw ExceptionUtil.unwrapThrowable(t);
            } finally {
                // Transactional sessions are owned by their completion callbacks, even after a timeout.
                if (transaction == null && sqlSession != null) {
                    sqlSession.close();
                }
            }
        }

        private SqlSession getSqlSession(Transaction transaction) throws Exception {
            if (transaction != null) {
                SqlSession sqlSession = sqlSessionMap.computeIfAbsent(transaction,
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
                            return session;
                        });
                return sqlSession;
            } else {
                return sqlSessionFactory.openSession();
            }
        }

        private void closeSqlSession(Transaction transaction) {
            // Remove first so a failed close cannot retain the session or cause another callback to close it again.
            SqlSession session = sqlSessionMap.remove(transaction);
            if (session != null) {
                session.close();
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
}
