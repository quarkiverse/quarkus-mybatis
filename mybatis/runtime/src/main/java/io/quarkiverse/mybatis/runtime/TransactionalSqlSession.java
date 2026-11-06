package io.quarkiverse.mybatis.runtime;

import static java.lang.reflect.Proxy.newProxyInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

import io.quarkus.runtime.annotations.RegisterForProxy;

@RegisterForProxy(targets = Connection.class)
public class TransactionalSqlSession implements SqlSession {
    private final SqlSessionFactory sqlSessionFactory;
    private final TransactionManager transactionManager;
    private final SqlSessionCacheCoordinator cacheCoordinator;

    private final SqlSession sqlSessionProxy;

    public TransactionalSqlSession(SqlSessionFactory sqlSessionFactory, TransactionManager transactionManager) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.transactionManager = transactionManager;
        DeferredQuerySupport.install(sqlSessionFactory.getConfiguration());
        // Install cache decorators before this facade (and its mapper proxies) can serve application calls.
        this.cacheCoordinator = SqlSessionCacheCoordinator.forConfiguration(sqlSessionFactory.getConfiguration());
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

    static boolean opensWithoutSql(SqlSessionFactory factory) {
        // Only these exact implementations construct lazy transactions/executors without acquiring
        // JDBC connections. Subclasses, plugins and custom transaction factories may execute SQL.
        if (factory.getClass() != DefaultSqlSessionFactory.class) {
            return false;
        }
        Configuration configuration = factory.getConfiguration();
        if ((configuration.getClass() != Configuration.class && configuration.getClass() != MyBatisConfiguration.class)
                || configuration.getInterceptors().stream().anyMatch(i -> !DeferredQuerySupport.isBindingInterceptor(i))) {
            return false;
        }
        if (configuration.getEnvironment() == null || configuration.getEnvironment().getTransactionFactory() == null) {
            return true;
        }
        Class<?> transactionFactory = configuration.getEnvironment().getTransactionFactory().getClass();
        return transactionFactory == ManagedTransactionFactory.class || transactionFactory == JdbcTransactionFactory.class;
    }

    private static Connection borrowConnection(Connection connection) {
        return (Connection) newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> connection.toString();
                        };
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (Throwable failure) {
                        throw ExceptionUtil.unwrapThrowable(failure);
                    }
                });
    }

    private static void markInitializationRollbackOnly(Transaction transaction, boolean managed, boolean noSqlPossible,
            Throwable failure) {
        if (managed && !noSqlPossible) {
            // An opener may already have written through the JTA connection before throwing.
            // Its partially constructed executor cannot safely participate in normal completion.
            try {
                transaction.setRollbackOnly();
            } catch (Exception | Error markFailure) {
                if (markFailure != failure) {
                    failure.addSuppressed(markFailure);
                }
            }
        }
    }

    private class TransactionalSqlSessionInterceptor implements InvocationHandler {
        private final Map<Transaction, SqlSessionHolder> sqlSessionMap = new ConcurrentHashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Transaction transaction = getActiveTransaction();
            SqlSessionHolder holder = null;
            SessionUse sessionUse = null;
            SqlSession sqlSession = null;
            SqlSessionCacheCoordinator.Snapshot snapshot = null;
            SqlSessionCacheCoordinator.Completion immediateCompletion = null;
            SqlSessionCacheCoordinator.Scope managedScope = null;
            Throwable failure = null;
            boolean openingWithoutSql = false;
            boolean pureRead = false;
            boolean invocationCompleted = false;
            boolean rolledBack = false;
            try {
                // Capture before any cache lookup or SQL, including work performed while opening a session.
                snapshot = cacheCoordinator.snapshot(getConfiguration());
                SqlSessionCacheCoordinator.StatementAccess access = null;
                if (args != null && args.length > 0 && args[0] instanceof String statement) {
                    access = cacheCoordinator.validateStatement(getConfiguration(), statement);
                }
                snapshot.invocation(access);
                pureRead = access != null && access.readOnly && opensWithoutSql(sqlSessionFactory)
                        && (method.getName().equals("selectOne") || method.getName().equals("selectList")
                                || method.getName().equals("selectMap"));
                if (transaction == null) {
                    immediateCompletion = cacheCoordinator.begin(snapshot);
                    openingWithoutSql = opensWithoutSql(sqlSessionFactory);
                    sqlSession = sqlSessionFactory.openSession();
                } else {
                    holder = getSessionHolder(transaction, snapshot);
                    sessionUse = holder.use();
                    if (!holder.managed) {
                        immediateCompletion = cacheCoordinator.begin(snapshot);
                        holder.cacheSnapshot.trackDependencies(access);
                    } else {
                        managedScope = cacheCoordinator.enter(holder.cacheSnapshot);
                        managedScope.trackQuery(holder.cacheSnapshot, access, method.getName());
                        if (!holder.deferredReadsTracked && access != null
                                && (access.lazy || access.nested && method.getName().equals("selectCursor"))) {
                            cacheCoordinator.trackDeferredReads(transactionManager, transaction);
                            holder.deferredReadsTracked = true;
                        }
                    }
                    holder.mayInvalidate |= !pureRead;
                    if (holder.managed) {
                        holder.cacheSnapshot.beforePotentialWrite(access, method.getName().equals("getConnection"));
                    }
                    sqlSession = holder.acquire(method.getName(), transaction);
                }
                if (holder == null || !holder.managed) {
                    snapshot.beforePotentialWrite(access, method.getName().equals("getConnection"));
                }
                Object result;
                try (DeferredQuerySupport.Scope ignored = DeferredQuerySupport.executing(sqlSession)) {
                    result = method.invoke(sqlSession, args);
                }
                invocationCompleted = true;
                if (holder == null) {
                    sqlSession.commit();
                } else {
                    holder.finishInvocation(sqlSession);
                    if (result instanceof Cursor<?> cursor && holder.managed) {
                        result = new SessionCursor<>(cursor, holder, sqlSession);
                    }
                    if (managedScope != null) {
                        managedScope.returned(result);
                        // A mapped LOB, stream or custom container can retain the JDBC handle.
                        if (holder.reopenFactory != null && !holder.jdbcExposed) {
                            holder.jdbcExposed = !ManagedCacheEntries.immutableScalar(result)
                                    && !ManagedCacheEntries.plainScalarRows(result);
                        }
                    }
                }
                return result;
            } catch (Throwable t) {
                failure = ExceptionUtil.unwrapThrowable(t);
                if (holder != null && sqlSession != null) {
                    try {
                        holder.rollbackInvocation(transaction, snapshot, sqlSession);
                    } catch (Throwable rollbackFailure) {
                        if (rollbackFailure != failure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                } else if (sqlSession != null) {
                    try {
                        sqlSession.rollback(true);
                        rolledBack = true;
                    } catch (Throwable rollbackFailure) {
                        immediateCompletion.discard();
                        if (rollbackFailure != failure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                }
                throw failure;
            } finally {
                Throwable cleanupFailure = null;
                if (sqlSession != null || sessionUse != null) {
                    try {
                        if (holder == null) {
                            sqlSession.close();
                        } else {
                            try (SessionUse use = sessionUse) {
                                if (failure != null && sqlSession != null) {
                                    holder.closeCursorSession(sqlSession);
                                }
                            }
                        }
                    } catch (Throwable closeFailure) {
                        cleanupFailure = closeFailure;
                    }
                }
                if (immediateCompletion != null) {
                    // JDBC completion has already happened. Drop the write fence before checking whether
                    // this invocation may publish; write calls kept the cache unavailable until this point.
                    try {
                        immediateCompletion.releaseInvalidations();
                    } catch (Throwable releaseFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = releaseFailure;
                        } else if (releaseFailure != cleanupFailure) {
                            cleanupFailure.addSuppressed(releaseFailure);
                        }
                    }
                    if (sqlSession == null) {
                        immediateCompletion.openingFailed(openingWithoutSql);
                    }
                    if (failure == null && cleanupFailure == null) {
                        if (!pureRead && !immediateCompletion.canPublish()) {
                            // Immediate sessions must still commit JDBC. Evict any conflicting cache publication.
                            immediateCompletion.invalidate();
                        }
                        immediateCompletion.success();
                    }
                    if (pureRead && !invocationCompleted && rolledBack && cleanupFailure == null) {
                        immediateCompletion.success();
                    }
                    try {
                        immediateCompletion.close();
                    } catch (Throwable cacheFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = cacheFailure;
                        } else if (cacheFailure != cleanupFailure) {
                            cleanupFailure.addSuppressed(cacheFailure);
                        }
                    }
                }
                if (managedScope != null) {
                    managedScope.close();
                }
                if (cleanupFailure != null) {
                    if (failure == null) {
                        throw cleanupFailure;
                    }
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }

        private SqlSessionHolder getSessionHolder(Transaction transaction, SqlSessionCacheCoordinator.Snapshot snapshot) {
            Configuration configuration = sqlSessionFactory.getConfiguration();
            boolean managed = configuration.getEnvironment() == null
                    || configuration.getEnvironment().getTransactionFactory() == null
                    || configuration.getEnvironment().getTransactionFactory() instanceof ManagedTransactionFactory;
            // Repeat this O(1) check even for an existing holder: registration can occur between calls.
            if (managed) {
                snapshot.requireNonBlocking();
            }
            return sqlSessionMap.computeIfAbsent(transaction,
                    (tx) -> {
                        try (SqlSessionCacheCoordinator.Completion opening = cacheCoordinator.begin(snapshot)) {
                            boolean batchExecutor = configuration.getDefaultExecutorType() == ExecutorType.BATCH;
                            boolean noSqlPossible = opensWithoutSql(sqlSessionFactory);
                            SqlSession session;
                            DeferredQuerySupport.Binding deferredBinding = managed
                                    ? new DeferredQuerySupport.Binding(configuration)
                                    : null;
                            if (managed) {
                                snapshot.manageEntries(tx);
                            }
                            try (DeferredQuerySupport.Scope ignored = DeferredQuerySupport.creating(deferredBinding)) {
                                // ManagedTransaction leaves JDBC completion to JTA. The autoCommit session flag
                                // preserves pending cache invalidations during rollback(false) after mapper failures.
                                session = managed ? sqlSessionFactory.openSession(true) : sqlSessionFactory.openSession();
                            } catch (RuntimeException | Error failure) {
                                opening.openingFailed(noSqlPossible);
                                markInitializationRollbackOnly(tx, managed, noSqlPossible, failure);
                                throw failure;
                            }
                            SqlSessionHolder created;
                            try {
                                SqlSessionFactory reopenFactory = managed && noSqlPossible
                                        && !configuration.isLazyLoadingEnabled()
                                                ? sqlSessionFactory
                                                : null;
                                created = new SqlSessionHolder(session, batchExecutor, managed, reopenFactory,
                                        cacheCoordinator, snapshot, deferredBinding,
                                        managed && configuration.getDefaultExecutorType() == ExecutorType.REUSE
                                                ? sqlSessionFactory
                                                : null);
                                tx.registerSynchronization(new Synchronization() {
                                    @Override
                                    public void beforeCompletion() {
                                        if (managed) {
                                            created.prepareJdbc();
                                        } else {
                                            completeSqlSession(tx, Status.STATUS_UNKNOWN);
                                        }
                                    }

                                    @Override
                                    public void afterCompletion(int status) {
                                        if (managed) {
                                            cacheCoordinator.transactionCompleted(tx);
                                        }
                                        completeSqlSession(tx, status);
                                    }
                                });
                            } catch (Exception | Error failure) {
                                // close() may commit a TCM when the session uses autoCommit=true. Disable
                                // publication first, including when rollback itself fails, then always close.
                                opening.discard();
                                markInitializationRollbackOnly(tx, managed, noSqlPossible, failure);
                                try {
                                    session.rollback(true);
                                } catch (RuntimeException | Error rollbackFailure) {
                                    if (rollbackFailure != failure) {
                                        failure.addSuppressed(rollbackFailure);
                                    }
                                }
                                try {
                                    session.close();
                                } catch (RuntimeException | Error closeFailure) {
                                    if (closeFailure != failure) {
                                        failure.addSuppressed(closeFailure);
                                    }
                                }
                                if (failure instanceof Error error) {
                                    throw error;
                                }
                                throw new RuntimeException("Could not register SqlSession synchronization in transaction",
                                        failure);
                            }
                            opening.success();
                            return created;
                        }
                    });
        }

        private void completeSqlSession(Transaction transaction, int status) {
            // Detach immediately; a Reaper callback must not close a session still in use or wait for the invocation.
            SqlSessionHolder holder = sqlSessionMap.remove(transaction);
            if (holder != null) {
                holder.requestCompletion(status);
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

    private static final class SessionUse implements AutoCloseable {
        private final SqlSessionHolder holder;

        private SessionUse(SqlSessionHolder holder) {
            this.holder = holder;
        }

        @Override
        public void close() {
            holder.release();
        }
    }

    private static final class SessionCursor<T> implements Cursor<T> {
        private final Cursor<T> cursor;
        private final SqlSessionHolder holder;
        private final SqlSession session;

        private SessionCursor(Cursor<T> cursor, SqlSessionHolder holder, SqlSession session) {
            this.cursor = cursor;
            this.holder = holder;
            this.session = session;
        }

        @Override
        public boolean isOpen() {
            return cursor.isOpen();
        }

        @Override
        public boolean isConsumed() {
            return cursor.isConsumed();
        }

        @Override
        public int getCurrentIndex() {
            return cursor.getCurrentIndex();
        }

        @Override
        public void close() throws IOException {
            SessionUse use = holder.tryUse();
            if (use == null) {
                // Completion already owns cleanup, or is waiting for an operation still in progress.
                return;
            }
            try (use;
                    SqlSessionCacheCoordinator.Scope ignored = holder.cacheSnapshot.drainEntries();
                    SqlSession closing = holder.detachCursorSession(session)) {
                cursor.close();
            }
        }

        @Override
        public Iterator<T> iterator() {
            Iterator<T> iterator = read(cursor::iterator);
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return read(iterator::hasNext);
                }

                @Override
                public T next() {
                    return read(iterator::next);
                }
            };
        }

        private <R> R read(Supplier<R> operation) {
            try (SessionUse use = holder.use();
                    DeferredQuerySupport.Scope loading = DeferredQuerySupport.executing(session);
                    SqlSessionCacheCoordinator.Scope ignored = holder.cacheSnapshot.deferredRead()) {
                R result;
                try {
                    result = operation.get();
                } catch (RuntimeException | Error failure) {
                    try {
                        closeIfConsumed();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != failure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    throw failure;
                }
                closeIfConsumed();
                return result;
            }
        }

        private void closeIfConsumed() {
            if (cursor.isConsumed()) {
                try {
                    close();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }
    }

    private static class SqlSessionHolder implements DeferredQuerySupport.Owner {
        private SqlSession session;
        private final SqlSessionFactory reopenFactory;
        private final SqlSessionFactory cursorFactory;
        private final DeferredQuerySupport.Binding deferredBinding;
        private Set<SqlSession> cursorSessions = Set.of();
        private boolean jdbcExposed;
        private boolean deferredReadsTracked;
        private final boolean batchExecutor;
        private final boolean managed;
        private final SqlSessionCacheCoordinator cacheCoordinator;
        private final SqlSessionCacheCoordinator.Snapshot cacheSnapshot;
        private SqlSessionCacheCoordinator.Snapshot failedJdbcRollback;
        private boolean mayInvalidate;
        private int invocations;
        private boolean closeRequested;
        private int completionStatus;

        private SqlSessionHolder(SqlSession session, boolean batchExecutor, boolean managed, SqlSessionFactory reopenFactory,
                SqlSessionCacheCoordinator cacheCoordinator, SqlSessionCacheCoordinator.Snapshot cacheSnapshot,
                DeferredQuerySupport.Binding deferredBinding,
                SqlSessionFactory cursorFactory) {
            this.session = session;
            this.reopenFactory = reopenFactory;
            this.batchExecutor = batchExecutor;
            this.managed = managed;
            this.cacheCoordinator = cacheCoordinator;
            this.cacheSnapshot = cacheSnapshot;
            this.cursorFactory = cursorFactory;
            this.deferredBinding = deferredBinding;
            if (deferredBinding != null) {
                deferredBinding.target(session);
                deferredBinding.attach(this);
            }
        }

        @Override
        public synchronized boolean isClosed() {
            return closeRequested;
        }

        @Override
        public Object query(org.apache.ibatis.transaction.Transaction transaction, Method method, Object[] args)
                throws Throwable {
            try (SessionUse use = use();
                    SqlSessionCacheCoordinator.Scope ignored = cacheSnapshot.deferredRead()) {
                // A deferred query must not reuse or flush statements belonging to a still-open
                // cursor or an enclosing mapper. It borrows the same enlisted JDBC connection.
                Executor executor;
                DeferredQuerySupport.Binding queryBinding = deferredBinding.fork();
                try (DeferredQuerySupport.Scope creating = DeferredQuerySupport.creating(queryBinding)) {
                    executor = session.getConfiguration().newExecutor(
                            new BorrowedTransaction(transaction), ExecutorType.SIMPLE);
                }
                queryBinding.target(executor);
                Throwable failure = null;
                try (DeferredQuerySupport.Scope executing = DeferredQuerySupport.executing(executor)) {
                    return method.invoke(executor, args);
                } catch (Throwable queryFailure) {
                    failure = ExceptionUtil.unwrapThrowable(queryFailure);
                    throw failure;
                } finally {
                    try {
                        executor.close(failure != null);
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

        private SessionUse use() {
            SessionUse use = tryUse();
            if (use == null) {
                if (failedJdbcRollback != null) {
                    throw new IllegalStateException("Cannot reuse a SqlSession after its JDBC rollback failed");
                }
                throw new IllegalStateException("Transaction completed before the SqlSession could be acquired");
            }
            return use;
        }

        private synchronized SessionUse tryUse() {
            if (closeRequested) {
                return null;
            }
            invocations++;
            return new SessionUse(this);
        }

        private SqlSession acquire(String method, Transaction transaction) {
            // The caller already holds a use. Opening JDBC resources must not hold the lifecycle
            // monitor: completion records its outcome and leaves cleanup to the last active use.
            if (session == null) {
                // A later JTA callback can still use the enlisted transaction after preparation.
                try (DeferredQuerySupport.Scope ignored = DeferredQuerySupport.creating(deferredBinding)) {
                    session = reopenFactory.openSession(true);
                    deferredBinding.target(session);
                }
            }
            jdbcExposed |= method.equals("selectCursor") || method.equals("getConnection") || method.equals("select");
            SqlSession invoked = session;
            if (cursorFactory != null && method.equals("selectCursor")) {
                // Keep the connection and transaction owned by the main session. The cursor executor
                // owns only its own statements and may outlive ordinary facade invocations.
                DeferredQuerySupport.Binding cursorBinding = deferredBinding.forkForCursor();
                try (DeferredQuerySupport.Scope ignored = DeferredQuerySupport.creating(cursorBinding)) {
                    invoked = cursorFactory.openSession(ExecutorType.REUSE, borrowConnection(session.getConnection()));
                    cursorBinding.target(invoked);
                } catch (RuntimeException | Error failure) {
                    markInitializationRollbackOnly(transaction, true, opensWithoutSql(cursorFactory), failure);
                    throw failure;
                }
                if (cursorSessions.isEmpty()) {
                    cursorSessions = Collections.newSetFromMap(new IdentityHashMap<>());
                }
                cursorSessions.add(invoked);
            }
            return invoked;
        }

        private void finishInvocation(SqlSession invoked) {
            if (!managed) {
                if (failedJdbcRollback != null) {
                    throw new IllegalStateException("A nested invocation failed to roll back this SqlSession");
                }
                session.commit();
                return;
            }
            synchronized (this) {
                if (closeRequested) {
                    return;
                }
            }
            // Preserve the old per-call local-cache and BATCH boundaries, without publishing shared caches.
            invoked.clearCache();
            clearOtherLocalCaches(invoked);
            if (batchExecutor || cursorFactory != null && invoked == session) {
                invoked.flushStatements();
            }
        }

        private void clearOtherLocalCaches(SqlSession invoked) {
            if (cursorFactory == null) {
                return;
            }
            if (invoked != session) {
                session.clearCache();
            }
            for (SqlSession cursor : cursorSessionsSnapshot()) {
                if (cursor != invoked) {
                    cursor.clearCache();
                }
            }
        }

        private synchronized List<SqlSession> cursorSessionsSnapshot() {
            return cursorSessions.isEmpty() ? List.of() : new ArrayList<>(cursorSessions);
        }

        private synchronized SqlSession detachCursorSession(SqlSession invoked) {
            return !cursorSessions.isEmpty() && cursorSessions.remove(invoked) ? invoked : null;
        }

        private void closeCursorSession(SqlSession invoked) {
            SqlSession cursor = detachCursorSession(invoked);
            if (cursor != null) {
                try (SqlSessionCacheCoordinator.Scope ignored = cacheSnapshot.drainEntries(); cursor) {
                    // The borrowed connection stays owned by the main session.
                }
            }
        }

        private void closeCursorSessions() {
            Throwable failure = null;
            for (SqlSession cursor : cursorSessionsSnapshot()) {
                try {
                    closeCursorSession(cursor);
                } catch (RuntimeException | Error closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
        }

        private void rollbackInvocation(Transaction transaction, SqlSessionCacheCoordinator.Snapshot snapshot,
                SqlSession invoked)
                throws Exception {
            if (!managed) {
                // A failed SELECT can have pending TCM entries even when the session isn't dirty.
                if (failedJdbcRollback == null) {
                    try {
                        session.rollback(true);
                    } catch (RuntimeException | Error failure) {
                        // A plugin can fail before MyBatis rolls back its TCM. Retire this session and
                        // close it under the failed invocation's scope (which owns any cache misses).
                        failedJdbcRollback = snapshot;
                        requestCompletion(Status.STATUS_UNKNOWN);
                        throw failure;
                    }
                }
                return;
            }
            synchronized (this) {
                if (closeRequested) {
                    return;
                }
            }
            try {
                invoked.rollback(false);
                clearOtherLocalCaches(invoked);
            } catch (RuntimeException | Error failure) {
                // If failed work cannot be discarded, it must not be flushed after JDBC has committed.
                try {
                    transaction.setRollbackOnly();
                } catch (Exception markFailure) {
                    if (markFailure != failure) {
                        failure.addSuppressed(markFailure);
                    }
                }
                throw failure;
            }
        }

        private void release() {
            boolean close;
            synchronized (this) {
                close = --invocations == 0 && closeRequested;
            }
            if (close) {
                complete();
            }
        }

        private void prepareJdbc() {
            SqlSession idle;
            synchronized (this) {
                if (reopenFactory == null || jdbcExposed || closeRequested || invocations != 0 || session == null) {
                    return;
                }
                idle = session;
                session = null;
                invocations++;
            }
            // close() drains MyBatis' TCM into private entries. JTA can still veto the transaction.
            try (SqlSessionCacheCoordinator.Scope ignored = cacheSnapshot.drainEntries()) {
                idle.close();
            } finally {
                release();
            }
        }

        private void requestCompletion(int status) {
            boolean close;
            synchronized (this) {
                if (closeRequested) {
                    return;
                }
                closeRequested = true;
                completionStatus = status;
                close = invocations == 0;
            }
            if (close) {
                complete();
            }
        }

        private void complete() {
            try (SqlSessionCacheCoordinator.Completion completion = cacheCoordinator.begin(
                    failedJdbcRollback == null ? cacheSnapshot : failedJdbcRollback)) {
                if (failedJdbcRollback != null) {
                    completion.discard();
                }
                try (SqlSessionCacheCoordinator.Scope ignored = managed ? cacheSnapshot.drainEntries() : null;
                        SqlSession session = this.session) {
                    closeCursorSessions();
                    if (managed && session != null) {
                        if (completionStatus == Status.STATUS_COMMITTED) {
                            // Drain MyBatis' TCM into private candidates. Close JDBC before shared publication.
                            session.commit();
                        } else {
                            session.rollback(true);
                        }
                    }
                }
                // JDBC and MyBatis cleanup are finished. Readers can resume before individual publications.
                completion.releaseInvalidations();
                if (managed && completionStatus == Status.STATUS_COMMITTED) {
                    cacheSnapshot.publishEntries();
                } else if (managed && completionStatus != Status.STATUS_ROLLEDBACK && mayInvalidate) {
                    completion.invalidate();
                }
                if (!managed && !completion.canPublish()) {
                    completion.invalidate();
                }
                completion.success();
            } finally {
                cacheSnapshot.releaseEntries();
                if (deferredBinding != null) {
                    deferredBinding.detach();
                }
            }
        }
    }
}
