package io.quarkiverse.it.mybatis;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.managed.ManagedTransaction;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;

@Path("/mybatis/deferred-query")
@Produces(MediaType.TEXT_PLAIN)
public class DeferredQueryResource {
    @Inject
    SqlSessionFactory factory;
    @Inject
    TransactionManager transactions;

    @GET
    @Path("cursor")
    public String cursor() throws Exception {
        Fixture fixture = fixture();
        transactions.begin();
        try {
            require(List.of(1).equals(fixture.session.selectList("parent")), "Parent query failed");
            try (Cursor<Integer> cursor = fixture.session.selectCursor("stream")) {
                var iterator = cursor.iterator();
                require(iterator.next() == 1, "First cursor row failed");
                require(List.of(1, 2).equals(fixture.session.selectList("streamStatement")),
                        "Mapper must use a separate statement while the cursor is open");
                require(iterator.next() == 2 && !iterator.hasNext(), "Mapper closed or changed the cursor's statement");
            }
            require(fixture.configuration.preparedTimeouts.equals(List.of(3, 3, 3)),
                    "Cursor must inherit the owning transaction's timeout");
            require(fixture.transactionFactory.owner.closes == 0, "Cursor closed the owning transaction");
            transactions.commit();
            require(fixture.transactionFactory.owner.closes == 1, "Owning transaction was not closed");
        } finally {
            rollbackIfActive();
        }
        return "ok";
    }

    @GET
    @Path("loader")
    public String loader() throws Exception {
        Fixture fixture = fixture();
        transactions.begin();
        try {
            require(List.of(1).equals(fixture.session.selectList("parent")), "Parent query failed");
            ResultLoader loader = loader(fixture);
            ResultLoader afterCompletion = loader(fixture);
            TimedTransaction owner = fixture.transactionFactory.owner;
            require(List.of(42).equals(loader.loadResult()), "Deferred query failed");
            require(fixture.configuration.executorTypes.equals(List.of(ExecutorType.REUSE, ExecutorType.SIMPLE)),
                    "Deferred query did not enter the owning session's isolated executor path");
            require(owner.closes == 0, "Deferred executor closed the owning transaction");
            require(List.of(42).equals(fixture.session.selectList("prepared")),
                    "Deferred statement contaminated the next prepared mapper query");
            require(fixture.configuration.preparedTimeouts.equals(List.of(3, 3, 3)),
                    "Deferred query lost the owning transaction's timeout");
            transactions.commit();
            require(owner.closes == 1, "Owning transaction was not closed");
            require(List.of(42).equals(afterCompletion.loadResult()), "Detached ResultLoader failed after completion");
            require(fixture.transactionFactory.owner != owner && fixture.transactionFactory.owner.closes == 1,
                    "Detached ResultLoader must create and close an independent transaction");
        } finally {
            rollbackIfActive();
        }
        return "ok";
    }

    private Fixture fixture() {
        TimedTransactionFactory transactionFactory = new TimedTransactionFactory();
        ObservingConfiguration configuration = new ObservingConfiguration(new Environment("deferred-native",
                transactionFactory, factory.getConfiguration().getEnvironment().getDataSource()));
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        CaptureLoaderExecutor capture = new CaptureLoaderExecutor();
        configuration.addInterceptor(capture);
        addRead(configuration, "parent", "SELECT 1", StatementType.PREPARED);
        addRead(configuration, "plain", "SELECT 42", StatementType.STATEMENT);
        addRead(configuration, "prepared", "SELECT 42", StatementType.PREPARED);
        addRead(configuration, "stream", "SELECT X FROM SYSTEM_RANGE(1, 2)", StatementType.PREPARED);
        addRead(configuration, "streamStatement", "SELECT X FROM SYSTEM_RANGE(1, 2)", StatementType.STATEMENT);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration),
                transactions);
        return new Fixture(configuration, transactionFactory, capture, session);
    }

    private static ResultLoader loader(Fixture fixture) {
        MappedStatement statement = fixture.configuration.getMappedStatement("plain");
        BoundSql sql = statement.getBoundSql(null);
        Executor executor = fixture.capture.loaderExecutor;
        // Use MyBatis' real loader and the wrapper retained by its result-set handler. This
        // exercises deferred execution without requiring runtime generation of a POJO proxy class.
        return new ResultLoader(fixture.configuration, executor, statement, null, List.class,
                executor.createCacheKey(statement, null, RowBounds.DEFAULT, sql), sql);
    }

    private static void addRead(ObservingConfiguration configuration, String id, String sql, StatementType type) {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, sql), SqlCommandType.SELECT).statementType(type)
                .resultMaps(List.of(new ResultMap.Builder(configuration, id + "-result", Integer.class, List.of()).build()))
                .build());
    }

    private void rollbackIfActive() throws Exception {
        if (transactions.getTransaction() != null) {
            transactions.rollback();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Fixture(ObservingConfiguration configuration, TimedTransactionFactory transactionFactory,
            CaptureLoaderExecutor capture, TransactionalSqlSession session) {
    }

    static final class CaptureLoaderExecutor implements Interceptor {
        Executor loaderExecutor;

        @Override
        public Object plugin(Object target) {
            if (loaderExecutor == null && target instanceof Executor) {
                try {
                    Object base = target;
                    if (target instanceof CachingExecutor) {
                        Field delegate = CachingExecutor.class.getDeclaredField("delegate");
                        delegate.setAccessible(true);
                        base = delegate.get(target);
                    }
                    Field wrapper = BaseExecutor.class.getDeclaredField("wrapper");
                    wrapper.setAccessible(true);
                    loaderExecutor = (Executor) wrapper.get(base);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not capture MyBatis' loader executor", failure);
                }
            }
            return target;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }

    static final class ObservingConfiguration extends MyBatisConfiguration {
        final List<Integer> preparedTimeouts = new ArrayList<>();
        final List<ExecutorType> executorTypes = new ArrayList<>();

        ObservingConfiguration(Environment environment) {
            super(environment);
        }

        @Override
        public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
            executorTypes.add(executorType);
            return super.newExecutor(transaction, executorType);
        }

        @Override
        public StatementHandler newStatementHandler(Executor executor, MappedStatement statement, Object parameter,
                RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
            StatementHandler handler = new RoutingStatementHandler(executor, statement, parameter, rowBounds, resultHandler,
                    boundSql) {
                @Override
                public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
                    Statement prepared = super.prepare(connection, transactionTimeout);
                    preparedTimeouts.add(prepared.getQueryTimeout());
                    return prepared;
                }
            };
            for (Interceptor interceptor : getInterceptors()) {
                handler = (StatementHandler) interceptor.plugin(handler);
            }
            return handler;
        }
    }

    static final class TimedTransactionFactory extends ManagedTransactionFactory {
        TimedTransaction owner;

        @Override
        public Transaction newTransaction(DataSource dataSource, TransactionIsolationLevel level, boolean autoCommit) {
            owner = new TimedTransaction(dataSource, level);
            return owner;
        }
    }

    static final class TimedTransaction extends ManagedTransaction {
        int closes;

        TimedTransaction(DataSource dataSource, TransactionIsolationLevel level) {
            super(dataSource, level, true);
        }

        @Override
        public Integer getTimeout() {
            return 3;
        }

        @Override
        public void close() throws SQLException {
            closes++;
            super.close();
        }
    }
}
