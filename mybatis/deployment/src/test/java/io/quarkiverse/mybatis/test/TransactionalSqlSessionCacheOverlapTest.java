package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

public class TransactionalSqlSessionCacheOverlapTest {
    @Test
    void configuredReuseCursorMustWorkWithoutCloseOnCompletion() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        javax.sql.DataSource compatibility = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { javax.sql.DataSource.class }, (proxy, method, args) -> {
                    Object result = callTarget(method, dataSource, args);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { Connection.class }, (connectionProxy, connectionMethod, connectionArgs) -> {
                                Object value = callTarget(connectionMethod, connection, connectionArgs);
                                if (!(value instanceof java.sql.PreparedStatement prepared)) {
                                    return value;
                                }
                                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { java.sql.PreparedStatement.class },
                                        (statementProxy, statementMethod, statementArgs) -> {
                                            if (statementMethod.getName().equals("closeOnCompletion")) {
                                                throw new java.sql.SQLFeatureNotSupportedException("closeOnCompletion");
                                            }
                                            return callTarget(statementMethod, prepared, statementArgs);
                                        });
                            });
                });
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("compatibility",
                configuration.getEnvironment().getTransactionFactory(), compatibility));
        transactionManager.begin();
        try (var direct = new SqlSessionFactoryBuilder().build(configuration).openSession(ExecutorType.REUSE, true);
                var cursor = direct.<Integer> selectCursor("read")) {
            assertEquals(1, cursor.iterator().next());
        }
        transactionManager.commit();
        transactionManager.begin();
        try (var cursor = reader.<Integer> selectCursor("read")) {
            assertEquals(1, cursor.iterator().next());
        }
    }

    private static Object callTarget(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void configuredReuseMustKeepReusingWithinANestedMapperCall() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        PrepareCounter counter = new PrepareCounter();
        configuration.addInterceptor(counter);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "childReuse",
                new StaticSqlSource(configuration, "SELECT CAST(? AS INTEGER)",
                        List.of(new org.apache.ibatis.mapping.ParameterMapping.Builder(configuration, "id", Integer.class)
                                .build())),
                SqlCommandType.SELECT)
                .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "parentReuse",
                new StaticSqlSource(configuration, "SELECT CAST(X AS INTEGER) AS id FROM SYSTEM_RANGE(1, 3)"),
                SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "parent-reuse", ParentRow.class,
                        List.of(new ResultMapping.Builder(configuration, "ids", "ID", List.class)
                                .nestedQueryId("childReuse").lazy(false).build()))
                        .build()))
                .build());
        transactionManager.begin();
        try (var direct = new SqlSessionFactoryBuilder().build(configuration).openSession(ExecutorType.REUSE, true)) {
            assertEquals(3, direct.selectList("parentReuse").size());
        }
        transactionManager.commit();
        assertEquals(2, counter.count);
        counter.count = 0;
        transactionManager.begin();
        assertEquals(3, reader.selectList("parentReuse").size());
        assertEquals(2, counter.count);
    }

    @Intercepts(@Signature(type = org.apache.ibatis.executor.statement.StatementHandler.class, method = "prepare", args = {
            Connection.class, Integer.class }))
    public static class PrepareCounter implements Interceptor {
        int count;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            count++;
            return invocation.proceed();
        }
    }

    @Test
    void lazyGetterInsideAnotherFacadeMustUseItsOriginSnapshot() throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "lazyParent",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "lazyParentResult", ParentRow.class,
                        List.of(new ResultMapping.Builder(configuration, "ids", "ID", List.class)
                                .nestedQueryId("read").lazy(true).build()))
                        .build()))
                .build());
        transactionManager.begin();
        ParentRow row;
        try (Connection c = dataSource.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, "establish",
                    new StaticSqlSource(configuration, "SELECT id FROM cache_overlap"), SqlCommandType.SELECT)
                    .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
            assertEquals(List.of(1), reader.selectList("establish"));
            row = reader.selectOne("lazyParent");
        }
        var old = transactionManager.suspend();
        try {
            transactionManager.begin();
            writer.insert("write");
            transactionManager.commit();
            assertEquals(List.of(1, 2), immediate.selectList("read"));
        } finally {
            transactionManager.resume(old);
        }
        immediate.select("slow", result -> assertEquals(List.of(1), row.getIds()));
        transactionManager.commit();
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(Interleaving.class, ParentRow.class, TransientRow.class, PrepareCounter.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:cache_overlap;DB_CLOSE_DELAY=-1")
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
    SqlSessionFactory factory;

    private Configuration configuration;
    private Cache cache;
    private TransactionalSqlSession reader;
    private TransactionalSqlSession writer;
    private TransactionalSqlSession immediate;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cache_overlap (id INTEGER PRIMARY KEY)");
            statement.execute("DELETE FROM cache_overlap");
            statement.execute("INSERT INTO cache_overlap VALUES (1)");
        }
        configuration = new io.quarkiverse.mybatis.runtime.MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        cache = new CacheBuilder("overlap-cache").readWrite(true).build();
        configuration.addCache(cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "read",
                new StaticSqlSource(configuration, "SELECT id FROM cache_overlap ORDER BY id"), SqlCommandType.SELECT)
                .cache(cache).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "read-result", Integer.class, List.of()).build()))
                .build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "write",
                new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (2)"), SqlCommandType.INSERT)
                .cache(cache).flushCacheRequired(true).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "slow",
                new StaticSqlSource(configuration, "SELECT 42"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "slow-result", Integer.class, List.of()).build()))
                .build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "parent",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "parent-result", ParentRow.class,
                        List.of(new ResultMapping.Builder(configuration, "ids", "ID", List.class)
                                .nestedQueryId("read").lazy(false).build()))
                        .build()))
                .build());
        SqlSessionFactory shared = new SqlSessionFactoryBuilder().build(configuration);
        reader = new TransactionalSqlSession(shared, transactionManager);
        writer = new TransactionalSqlSession(shared, transactionManager);
        immediate = new TransactionalSqlSession(shared, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (transactionManager.getTransaction() != null) {
            transactionManager.rollback();
        }
        assertEquals(0, dataSource.getMetrics().activeCount(), "All overlapping sessions must return their connections");
    }

    @Test
    void secondFacadeCannotPublishAnOlderDatabaseSnapshot() throws Exception {
        checkSecondFacade(false);
    }

    @Test
    void secondFacadeCannotReadACacheWarmerThanItsDatabaseSnapshot() throws Exception {
        checkSecondFacade(true);
    }

    private void checkSecondFacade(boolean warmCache) throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "firstRead",
                new StaticSqlSource(configuration, "SELECT id FROM cache_overlap"), SqlCommandType.SELECT)
                .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "change",
                new StaticSqlSource(configuration, "UPDATE cache_overlap SET id = 2 WHERE id = 1"), SqlCommandType.UPDATE)
                .cache(cache).flushCacheRequired(true).build());
        transactionManager.begin();
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertEquals(List.of(1), reader.selectList("firstRead"));
        }
        var original = transactionManager.suspend();
        try {
            transactionManager.begin();
            writer.update("change");
            transactionManager.commit();
            if (warmCache) {
                assertEquals(List.of(2), immediate.selectList("read"));
            }
        } finally {
            transactionManager.resume(original);
        }
        assertEquals(List.of(1), writer.selectList("read"));
        transactionManager.commit();
        assertEquals(List.of(2), immediate.selectList("read"));
    }

    @Test
    void failedJdbcReadCannotSurviveUntilTheNextSuccessfulCall() throws Exception {
        var ds = new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:cache_overlap;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("jdbc",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "three",
                new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (3)"), SqlCommandType.INSERT)
                .cache(cache).flushCacheRequired(true).build());
        immediate.insert("write");
        transactionManager.begin();
        org.junit.jupiter.api.Assertions.assertThrows(org.apache.ibatis.exceptions.TooManyResultsException.class,
                () -> reader.selectOne("read"));
        immediate.insert("three");
        assertEquals(List.of(42), reader.selectList("slow"));
        assertEquals(List.of(1, 2, 3), immediate.selectList("read"));
        transactionManager.rollback();
    }

    @Test
    void discriminatorFallsBackOnlyForUnregisteredCases() {
        ResultMap text = new ResultMap.Builder(configuration, "text", String.class, List.of()).build();
        configuration.addResultMap(text);
        var discriminator = new org.apache.ibatis.mapping.Discriminator.Builder(configuration,
                new ResultMapping.Builder(configuration, null, "id", Integer.class).build(),
                java.util.Map.of("2", "missing", "3", text.getId())).build();
        ResultMap base = new ResultMap.Builder(configuration, "base", Integer.class, List.of())
                .discriminator(discriminator).build();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "discriminated",
                new StaticSqlSource(configuration, "SELECT id FROM (VALUES (1), (2), (3)) AS t(id) ORDER BY id"),
                SqlCommandType.SELECT).resultMaps(List.of(base)).build());
        assertEquals(List.of(1, 2, "3"), immediate.selectList("discriminated"));
    }

    @Test
    void lazyNestedGetterMustUseTheEnlistedDatabaseSnapshot() throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "lazyParent",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "lazyParentResult", ParentRow.class,
                        List.of(new ResultMapping.Builder(configuration, "ids", "ID", List.class)
                                .nestedQueryId("read").lazy(true).build()))
                        .build()))
                .build());
        transactionManager.begin();
        ParentRow row;
        try (Connection c = dataSource.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, "establish",
                    new StaticSqlSource(configuration, "SELECT id FROM cache_overlap"), SqlCommandType.SELECT)
                    .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
            assertEquals(List.of(1), reader.selectList("establish"));
            row = reader.selectOne("lazyParent");
        }
        var old = transactionManager.suspend();
        try {
            transactionManager.begin();
            writer.insert("write");
            transactionManager.commit();
            assertEquals(List.of(1, 2), immediate.selectList("read"));
        } finally {
            transactionManager.resume(old);
        }
        assertEquals(List.of(1), row.getIds());
        transactionManager.commit();
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void reuseMustRespectTheStatementTypeOfTheSecondMapper() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        String sql = "SELECT id FROM cache_overlap ORDER BY id";
        for (var type : List.of(StatementType.STATEMENT,
                StatementType.PREPARED)) {
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, type.name(),
                    new StaticSqlSource(configuration, sql), SqlCommandType.SELECT)
                    .statementType(type).resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
        }
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("STATEMENT"));
        assertEquals(List.of(1), reader.selectList("PREPARED"));
        try (var cursor = reader.<Integer> selectCursor("STATEMENT")) {
            assertEquals(List.of(1), reader.selectList("PREPARED"));
            assertEquals(1, cursor.iterator().next());
        }
    }

    @Test
    void reuseCursorsMustKeepIndependentStatementsOnTheSameConnection() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        transactionManager.begin();
        reader.insert("write");
        long acquisitions = dataSource.getMetrics().acquireCount();
        try (var first = reader.<Integer> selectCursor("read");
                var second = reader.<Integer> selectCursor("read")) {
            assertEquals(acquisitions, dataSource.getMetrics().acquireCount());
            var firstRows = first.iterator();
            var secondRows = second.iterator();
            assertEquals(1, firstRows.next());
            assertEquals(1, secondRows.next());
            first.close();
            assertEquals(List.of(1, 2), reader.selectList("read"));
            assertEquals(2, secondRows.next());
            assertFalse(secondRows.hasNext());
            assertTrue(second.isConsumed());
            assertEquals(acquisitions, dataSource.getMetrics().acquireCount());
        }
        transactionManager.commit();
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void reuseCursorCompletionMustPreserveOtherCacheCandidates() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        var cursor = reader.<Integer> selectCursor("slow");
        assertEquals(42, cursor.iterator().next());
        transactionManager.commit();
        assertFalse(cursor.isOpen());
        assertEquals(1, cache.getSize());
        long acquisitions = dataSource.getMetrics().acquireCount();
        assertEquals(List.of(1), immediate.selectList("read"));
        assertEquals(acquisitions, dataSource.getMetrics().acquireCount());
    }

    @Test
    void reuseCursorNestedReadsMustObserveSuccessfulAndFailedFacadeCalls() throws Exception {
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "cursorParents",
                new StaticSqlSource(configuration, "SELECT CAST(X AS INTEGER) AS id FROM SYSTEM_RANGE(1, 3)"),
                SqlCommandType.SELECT).resultMaps(configuration.getMappedStatement("parent").getResultMaps()).build());
        transactionManager.begin();
        Connection connection = reader.getConnection();
        try (var cursor = reader.<ParentRow> selectCursor("cursorParents")) {
            var rows = cursor.iterator();
            assertEquals(List.of(1), rows.next().getIds());
            reader.insert("write");
            assertEquals(List.of(1, 2), rows.next().getIds());
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> reader.select("slow", result -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO cache_overlap VALUES (3)");
                } catch (java.sql.SQLException failure) {
                    throw new RuntimeException(failure);
                }
                throw new IllegalStateException("Fail after writing through the enlisted connection");
            }));
            assertEquals(List.of(1, 2, 3), rows.next().getIds());
        }
        transactionManager.rollback();
        assertEquals(List.of(1), immediate.selectList("read"));
    }

    @Test
    void managedRollbackMustNotEvictAnEntryItNeverRead() throws Exception {
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        var old = transactionManager.suspend();
        try {
            transactionManager.begin();
            writer.insert("write");
            transactionManager.commit();
            assertEquals(List.of(1, 2), immediate.selectList("read"));
            assertEquals(1, cache.getSize());
        } finally {
            transactionManager.resume(old);
        }
        reader.selectList("read");
        transactionManager.rollback();
        assertEquals(1, cache.getSize());
        long acquisitions = dataSource.getMetrics().acquireCount();
        assertEquals(List.of(1, 2), immediate.selectList("read"));
        assertEquals(acquisitions, dataSource.getMetrics().acquireCount());
    }

    @Test
    void nestedReadInASharedRegionCanHitCacheUntilAWrite() throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "sharedParent",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).resultMaps(configuration.getMappedStatement("parent").getResultMaps()).build());
        assertEquals(List.of(1), immediate.<ParentRow> selectOne("sharedParent").getIds());
        long acquisitions = dataSource.getMetrics().acquireCount();
        assertEquals(List.of(1), immediate.<ParentRow> selectOne("sharedParent").getIds());
        assertEquals(acquisitions, dataSource.getMetrics().acquireCount());

        transactionManager.begin();
        writer.insert("write");
        transactionManager.commit();
        assertEquals(List.of(1, 2), immediate.<ParentRow> selectOne("sharedParent").getIds());
    }

    @Test
    void warmParentResultCannotHideACommittedChildWrite() throws Exception {
        Cache parents = new CacheBuilder("parents").readWrite(false).build();
        configuration.addCache(parents);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "cachedParent",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .cache(parents).useCache(true).resultMaps(configuration.getMappedStatement("parent").getResultMaps()).build());
        // The provider may already contain a parent result, e.g. from a direct MyBatis session.
        try (var direct = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(List.of(1), direct.<ParentRow> selectOne("cachedParent").getIds());
        }
        assertEquals(1, parents.getSize());
        transactionManager.begin();
        writer.insert("write");
        transactionManager.commit();
        assertEquals(List.of(1, 2), immediate.<ParentRow> selectOne("cachedParent").getIds());
        parents.clear();
        assertEquals(List.of(1, 2), immediate.<ParentRow> selectOne("cachedParent").getIds());
        assertEquals(0, parents.getSize(), "A cross-region parent must not repopulate the provider on a miss");
    }

    @Test
    void uncachedWriteDiscardsItsOwnEarlierCachedQuery() throws Exception {
        addUncachedWrite();
        transactionManager.begin();
        assertEquals(List.of(1), writer.selectList("read"));
        writer.insert("uncachedWrite");
        transactionManager.commit();

        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void readerStartedDuringUncachedWriteCannotRefillAfterCommit() throws Exception {
        addUncachedWrite();
        transactionManager.begin();
        writer.insert("uncachedWrite");
        jakarta.transaction.Transaction pendingWrite = transactionManager.suspend();
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        jakarta.transaction.Transaction pendingRead = transactionManager.suspend();
        transactionManager.resume(pendingWrite);
        transactionManager.commit();
        transactionManager.resume(pendingRead);
        transactionManager.commit();

        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    private void addUncachedWrite() {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "uncachedWrite",
                new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (2)"), SqlCommandType.INSERT)
                .flushCacheRequired(true).build());
    }

    @Test
    void regionalWriteWithoutMyBatisFlushExpiresBothTransactionsReads() throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "noFlushWrite",
                new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (2)"), SqlCommandType.INSERT)
                .cache(cache).flushCacheRequired(false).build());
        transactionManager.begin();
        assertEquals(List.of(1), writer.selectList("read"));
        writer.insert("noFlushWrite");
        jakarta.transaction.Transaction pendingWrite = transactionManager.suspend();
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        jakarta.transaction.Transaction pendingRead = transactionManager.suspend();
        transactionManager.resume(pendingWrite);
        transactionManager.commit();
        transactionManager.resume(pendingRead);
        transactionManager.commit();

        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void complexResultPreservesMappedFieldsAndOtherCandidatesInTheSameRegion() throws Exception {
        Cache rows = new CacheBuilder("rows").readWrite(false).build();
        configuration.addCache(rows);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "scalar",
                new StaticSqlSource(configuration, "SELECT id FROM cache_overlap"), SqlCommandType.SELECT)
                .cache(rows).useCache(true)
                .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "row",
                new StaticSqlSource(configuration, "SELECT id FROM cache_overlap"), SqlCommandType.SELECT)
                .cache(rows).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "row-result", TransientRow.class,
                        List.of(new ResultMapping.Builder(configuration, "id", "ID", Integer.class).build())).build()))
                .build());
        transactionManager.begin();
        List<Integer> scalar = reader.selectList("scalar");
        scalar.clear();
        TransientRow row = reader.selectOne("row");
        assertEquals(1, row.id);
        row.id = 99;
        transactionManager.commit();
        assertEquals(1, rows.getSize(), "Only the scalar key should have been published");
        assertEquals(List.of(1), immediate.selectList("scalar"));
        assertEquals(1, immediate.<TransientRow> selectOne("row").id);
    }

    public static class TransientRow implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public transient int id;
    }

    @Test
    void preparationClosesIdleJdbcHandleButLateCallbacksCanUseTheTransaction() throws Exception {
        var opened = new java.util.concurrent.atomic.AtomicReference<Connection>();
        var tracking = new org.apache.ibatis.datasource.unpooled.UnpooledDataSource() {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                Connection connection = dataSource.getConnection();
                opened.set(connection);
                return connection;
            }
        };
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("tracked",
                configuration.getEnvironment().getTransactionFactory(), tracking));
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        Connection first = opened.get();
        transactionManager.getTransaction().registerSynchronization(new jakarta.transaction.Synchronization() {
            @Override
            public void beforeCompletion() {
                try {
                    assertTrue(first.isClosed());
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                assertEquals(0, cache.getSize());
                reader.insert("write");
                assertEquals(List.of(1, 2), reader.selectList("read"));
                assertEquals(0, cache.getSize());
            }

            @Override
            public void afterCompletion(int status) {
            }
        });
        transactionManager.commit();
        assertEquals(1, cache.getSize());
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void externalWriteExpiresOnlyItsRegionInAMultiRegionReader() throws Exception {
        Cache other = addUnrelatedCache();
        other.clear();
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        assertEquals(7, reader.<Integer> selectOne("other"));
        jakarta.transaction.Transaction pending = transactionManager.suspend();
        transactionManager.begin();
        writer.insert("write");
        transactionManager.commit();
        transactionManager.resume(pending);
        transactionManager.commit();
        assertEquals(0, cache.getSize());
        assertEquals(1, other.getSize());
    }

    @Test
    void ownRegionalWriteRetainsOtherCandidatesAndPublishesItsNewRead() throws Exception {
        Cache other = addUnrelatedCache();
        other.clear();
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        assertEquals(List.of(7), reader.selectList("other"));
        reader.insert("write");
        assertEquals(List.of(1, 2), reader.selectList("read"));
        transactionManager.commit();
        assertEquals(1, other.getSize());
        assertEquals(1, cache.getSize());
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void selectOnePreservesTheDifferenceBetweenNoRowsAndOneNullRow() throws Exception {
        for (String id : List.of("nullRow", "noRows")) {
            String sql = "SELECT CAST(NULL AS INTEGER)" + (id.equals("noRows") ? " WHERE 1 = 0" : "");
            configuration
                    .addMappedStatement(new MappedStatement.Builder(configuration, id, new StaticSqlSource(configuration, sql),
                            SqlCommandType.SELECT).cache(cache).useCache(true)
                            .resultMaps(configuration.getMappedStatement("read").getResultMaps()).build());
        }
        transactionManager.begin();
        org.junit.jupiter.api.Assertions.assertNull(reader.selectOne("nullRow"));
        org.junit.jupiter.api.Assertions.assertNull(reader.selectOne("noRows"));
        transactionManager.commit();
        assertEquals(2, cache.getSize());
        assertEquals(java.util.Collections.singletonList(null), immediate.selectList("nullRow"));
        assertEquals(List.of(), immediate.selectList("noRows"));
    }

    @Test
    void unknownWriteDiscardsEarlierKeysButAllowsNewQuery() throws Exception {
        Cache other = addUnrelatedCache();
        other.clear();
        addUncachedWrite();
        transactionManager.begin();
        assertEquals(List.of(7), reader.selectList("other"));
        assertEquals(List.of(1), reader.selectList("read"));
        reader.insert("uncachedWrite");
        assertEquals(List.of(1, 2), reader.selectList("read"));
        transactionManager.commit();
        assertEquals(0, other.getSize());
        assertEquals(1, cache.getSize());
        assertEquals(List.of(1, 2), immediate.selectList("read"));
    }

    @Test
    void committedWriterInvalidatesWarmCacheBeforeUnrelatedInvocationReturns() {
        assertEquals(List.of(1), reader.selectList("read"));
        assertEquals(1, cache.getSize());
        configuration.addInterceptor(new Interleaving("slow", () -> writeAndRead(true), () -> {
        }));

        assertEquals(List.of(42), immediate.selectList("slow"));
    }

    @Test
    void pendingWriterKeepsItsRegionEmptyUntilTheDatabaseOutcomeIsKnown() throws Exception {
        Cache other = addUnrelatedCache();
        assertEquals(List.of(1), reader.selectList("read"));
        assertEquals(1, cache.getSize());

        transactionManager.begin();
        writer.insert("write");

        assertEquals(0, cache.getSize(), "A write must invalidate shared values before JDBC commit");
        jakarta.transaction.Transaction pendingWriter = transactionManager.suspend();
        transactionManager.begin();
        assertEquals(List.of(7), reader.selectList("other"),
                "A regional write fence must not block an unrelated cache");
        assertEquals(List.of(1), reader.selectList("read"),
                "Another transaction still sees the pre-commit database state");
        jakarta.transaction.Transaction staleReader = transactionManager.suspend();
        assertEquals(0, cache.getSize(),
                "Readers must not repopulate the region while the writer outcome is pending");
        assertEquals(1, other.getSize());

        transactionManager.resume(pendingWriter);
        transactionManager.commit();
        transactionManager.resume(staleReader);
        transactionManager.commit();

        assertEquals(0, cache.getSize(), "A reader that overlapped the write must not publish its old result later");
        assertEquals(List.of(1, 2), immediate.selectList("read"));
        assertEquals(1, cache.getSize());
        assertEquals(1, other.getSize());
    }

    @Test
    void rollbackReleasesAWriteFenceWithoutPublishingItsResults() throws Exception {
        assertEquals(List.of(1), reader.selectList("read"));
        transactionManager.begin();
        writer.insert("write");
        assertEquals(0, cache.getSize());

        transactionManager.rollback();

        assertEquals(List.of(1), immediate.selectList("read"));
        assertEquals(1, cache.getSize());
    }

    @Test
    void overlappingWritersKeepTheRegionFencedUntilBothOutcomesAreKnown() throws Exception {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "writeThree",
                new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (3)"), SqlCommandType.INSERT)
                .cache(cache).flushCacheRequired(true).build());
        assertEquals(List.of(1), reader.selectList("read"));

        transactionManager.begin();
        writer.insert("write");
        jakarta.transaction.Transaction first = transactionManager.suspend();
        transactionManager.begin();
        writer.insert("writeThree");
        jakarta.transaction.Transaction second = transactionManager.suspend();

        transactionManager.resume(first);
        transactionManager.commit();
        assertEquals(0, cache.getSize());
        assertEquals(List.of(1, 2), immediate.selectList("read"));
        assertEquals(0, cache.getSize(), "The second writer must keep the region fenced");

        transactionManager.resume(second);
        transactionManager.commit();
        assertEquals(List.of(1, 2, 3), immediate.selectList("read"));
        assertEquals(1, cache.getSize());
    }

    @Test
    void lateImmediateReadCannotPublishOldResultsEvenBeforeItsFinallyBlock() {
        verifyLatePublication(true);
    }

    @Test
    void immediateWriterAlsoPreventsLateImmediateReadPublication() {
        verifyLatePublication(false);
    }

    private void verifyLatePublication(boolean jtaWriter) {
        configuration.addInterceptor(new Interleaving("read", () -> writeAndRead(jtaWriter), this::assertFreshRead));

        assertEquals(List.of(1), immediate.selectList("read"), "The older query legitimately ran before the write");
        assertFreshRead();
    }

    @Test
    void nestedQueryCannotPublishOldResultsAfterAnOverlappingWrite() {
        configuration.addInterceptor(new Interleaving("parent", () -> writeAndRead(true), this::assertFreshRead));

        ParentRow row = immediate.selectOne("parent");

        assertEquals(List.of(1), row.getIds());
        assertFreshRead();
    }

    @Test
    void lateRegisteredWriterCannotBeBypassedByAnOlderRead() {
        MappedStatement original = configuration.getMappedStatement("read");
        configuration.addInterceptor(new Interleaving("read", () -> {
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, "lateWrite",
                    new StaticSqlSource(configuration, "INSERT INTO cache_overlap VALUES (2)"), SqlCommandType.INSERT)
                    .cache(cache).flushCacheRequired(true).build());
            try {
                transactionManager.begin();
                writer.insert("lateWrite");
                transactionManager.commit();
                assertFreshRead();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }, this::assertFreshRead));

        assertEquals(List.of(1), immediate.selectList("read"));

        org.junit.jupiter.api.Assertions.assertSame(original, configuration.getMappedStatement("read"));
        assertFreshRead();
    }

    @Test
    void transactionStartedWithoutCachesStillDefersAndDetachesALateRegisteredQuery() throws Exception {
        Configuration late = new io.quarkiverse.mybatis.runtime.MyBatisConfiguration(configuration.getEnvironment());
        late.addMappedStatement(new MappedStatement.Builder(late, "uncached",
                new StaticSqlSource(late, "SELECT 1"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(late, "uncached-result", Integer.class, List.of()).build())).build());
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(late),
                transactionManager);
        transactionManager.begin();
        assertEquals(1, (Integer) session.selectOne("uncached"));
        Cache added = new CacheBuilder("late-cache").readWrite(true).build();
        late.addCache(added);
        late.addMappedStatement(new MappedStatement.Builder(late, "cached",
                new StaticSqlSource(late, "SELECT id FROM cache_overlap ORDER BY id"), SqlCommandType.SELECT)
                .cache(added).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(late, "cached-result", Integer.class, List.of()).build())).build());

        List<Integer> result = session.selectList("cached");
        result.add(999);
        assertEquals(0, added.getSize(), "New caches must not publish before the existing JTA transaction commits");
        transactionManager.commit();

        assertEquals(1, added.getSize());
        assertEquals(List.of(1), session.selectList("cached"), "Late registration must retain result isolation");
        assertEquals(0, dataSource.getMetrics().activeCount());
    }

    @Test
    void lateBlockingCacheIsRejectedForExistingHolderWithoutLeakingItsConnectionOrALock() throws Exception {
        transactionManager.begin();
        assertEquals(List.of(1), reader.selectList("read"));
        org.apache.ibatis.cache.decorators.BlockingCache added = new org.apache.ibatis.cache.decorators.BlockingCache(
                new org.apache.ibatis.cache.impl.PerpetualCache("late-blocking"));
        added.setTimeout(100);
        configuration.addCache(added);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "blocking",
                new StaticSqlSource(configuration, "SELECT id FROM cache_overlap ORDER BY id"), SqlCommandType.SELECT)
                .cache(added).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "blocking-result", Integer.class, List.of()).build()))
                .build());

        org.junit.jupiter.api.Assertions.assertThrows(io.quarkiverse.mybatis.runtime.BlockingCacheNotSupportedException.class,
                () -> reader.selectList("blocking"));
        assertEquals(jakarta.transaction.Status.STATUS_ACTIVE, transactionManager.getStatus());
        transactionManager.commit();

        assertEquals(0, dataSource.getMetrics().activeCount());
        assertEquals(List.of(1), immediate.selectList("blocking"), "A rejected call must not retain a miss lock");
    }

    public static class ParentRow implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private List<Integer> ids;

        public List<Integer> getIds() {
            return ids;
        }

        public void setIds(List<Integer> ids) {
            this.ids = ids;
        }
    }

    private void writeAndRead(boolean jtaWriter) {
        try {
            if (jtaWriter) {
                transactionManager.begin();
            }
            writer.insert("write");
            if (jtaWriter) {
                transactionManager.commit();
            }
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM cache_overlap")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1), "The writer has already committed to the database");
            }
            assertFreshRead();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void assertFreshRead() {
        try {
            transactionManager.begin();
            assertEquals(List.of(1, 2), reader.selectList("read"),
                    "A new transaction after the writer returned must not read the pre-write cache");
            transactionManager.commit();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private Cache addUnrelatedCache() {
        Cache other = new CacheBuilder("unrelated").readWrite(true).build();
        configuration.addCache(other);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "other",
                new StaticSqlSource(configuration, "SELECT 7"), SqlCommandType.SELECT).cache(other).useCache(true)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "other-result", Integer.class, List.of()).build()))
                .build());
        assertEquals(List.of(7), reader.selectList("other"));
        return other;
    }

    @Test
    void readOnlyCommitPreservesUnrelatedWarmCacheAndLaterReaders() throws Exception {
        Cache other = addUnrelatedCache();
        transactionManager.begin();
        assertEquals(List.of(7), reader.selectList("other"));
        jakarta.transaction.Transaction older = transactionManager.suspend();
        transactionManager.begin();
        writer.insert("write");
        transactionManager.commit();
        transactionManager.begin();
        assertEquals(List.of(7), reader.selectList("other"));
        jakarta.transaction.Transaction newer = transactionManager.suspend();
        transactionManager.resume(older);
        transactionManager.commit();
        assertEquals(1, other.getSize());
        transactionManager.resume(newer);
        transactionManager.commit();
        assertEquals(1, other.getSize(), "A stale reader must not trigger a second wave of invalidation");
    }

    @Test
    void pureReadSqlFailurePreservesOtherCaches() {
        Cache other = addUnrelatedCache();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "broken",
                new StaticSqlSource(configuration, "SELECT missing_column FROM cache_overlap"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).build());
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> immediate.selectList("broken"));
        assertEquals(1, other.getSize());
    }

    @Test
    void flushCacheSelectStillInvalidatesWhenItsTransactionIsStale() throws Exception {
        Cache other = addUnrelatedCache();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "flushRead",
                new StaticSqlSource(configuration, "SELECT 7"), SqlCommandType.SELECT).cache(other)
                .flushCacheRequired(true).useCache(false)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "flush-result", Integer.class, List.of()).build()))
                .build());
        transactionManager.begin();
        assertEquals(List.of(7), reader.selectList("flushRead"));
        jakarta.transaction.Transaction older = transactionManager.suspend();
        transactionManager.begin();
        writer.insert("write");
        transactionManager.commit();
        transactionManager.resume(older);
        transactionManager.commit();
        assertEquals(0, other.getSize(), "flushCache is an invalidation even for SELECT with useCache=false");
    }

    @Test
    void uncachedNonSerializableResultDoesNotDisableUnrelatedCache() throws Exception {
        Cache other = addUnrelatedCache();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "uncachedObject",
                new StaticSqlSource(configuration, "SELECT 1 AS id"), SqlCommandType.SELECT)
                .resultMaps(
                        List.of(new ResultMap.Builder(configuration, "uncached-object", ParentRow.class, List.of()).build()))
                .build());
        transactionManager.begin();
        reader.selectOne("uncachedObject");
        transactionManager.commit();
        assertEquals(1, other.getSize());
    }

    @Test
    void lazyResultFallbackDoesNotEvictUnrelatedCache() throws Exception {
        Cache other = addUnrelatedCache();
        configuration.setLazyLoadingEnabled(true);
        transactionManager.begin();
        List<Integer> result = reader.selectList("read");
        result.add(999);
        transactionManager.commit();
        assertEquals(1, other.getSize());
        assertEquals(List.of(1), immediate.selectList("read"));
    }

    // Deterministic overlapping sessions in one worker, including a read between cache commit and facade cleanup.
    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {
                    MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class }),
            @Signature(type = Executor.class, method = "commit", args = { boolean.class }) })
    public static class Interleaving implements Interceptor {
        private final String statement;
        private final Runnable afterQuery;
        private final Runnable afterCommit;
        private Object outerExecutor;

        public Interleaving(String statement, Runnable afterQuery, Runnable afterCommit) {
            this.statement = statement;
            this.afterQuery = afterQuery;
            this.afterCommit = afterCommit;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("query") && outerExecutor == null
                    && ((MappedStatement) invocation.getArgs()[0]).getId().equals(statement)) {
                outerExecutor = invocation.getTarget();
                Object result = invocation.proceed();
                afterQuery.run();
                return result;
            }
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("commit") && invocation.getTarget() == outerExecutor) {
                afterCommit.run();
            }
            return result;
        }
    }
}
