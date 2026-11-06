package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.test.QuarkusUnitTest;

class TransactionalSqlSessionCacheRetentionTest {
    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(CloseFailure.class, FailingCache.class, QueryCompletion.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:cache_retention;DB_CLOSE_DELAY=-1")
            .overrideConfigKey("quarkus.datasource.username", "sa")
            .overrideConfigKey("quarkus.datasource.jdbc.transactions", "enabled")
            .overrideConfigKey("quarkus.mybatis.transaction-factory", "MANAGED");

    @Inject
    SqlSessionFactory factory;
    @Inject
    TransactionManager manager;

    @AfterEach
    void cleanup() throws Exception {
        if (manager.getTransaction() != null) {
            int status = manager.getStatus();
            if (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK) {
                manager.rollback();
            } else {
                manager.suspend();
            }
        }
    }

    @Test
    void commitPublishesResultsThenReleasesCandidatesRetainedByClosedCursor() throws Exception {
        RetainedCursor retained = openCursor(null);
        manager.commit();
        assertEquals(1, retained.cache.getSize(), "Releasing private results must preserve successful L2 publication");
        retained.cache.clear();
        assertReleased(retained.cursor);
    }

    @Test
    void rollbackReleasesCandidatesRetainedByClosedCursor() throws Exception {
        RetainedCursor retained = openCursor(null);
        manager.rollback();
        assertEquals(0, retained.cache.getSize(), "Rolled-back results must not be published");
        assertReleased(retained.cursor);
    }

    @Test
    void sessionCloseFailureStillReleasesCandidates() throws Exception {
        CloseFailure failure = new CloseFailure();
        RetainedCursor retained = openCursor(failure);
        failure.enabled = true;
        // JTA logs exceptions from afterCompletion; it does not propagate them to commit().
        manager.commit();
        assertEquals(1, failure.failures, "The owning executor's close must actually have failed");
        assertEquals(0, retained.cache.getSize(), "Failed cleanup must discard shared-cache publication");
        assertReleased(retained.cursor);
    }

    @Test
    void cachePublicationFailureStillReleasesCandidates() throws Exception {
        RetainedCursor retained = openCursor(null);
        retained.cache.failOnPut = true;
        manager.commit();
        assertEquals(1, retained.cache.failures, "The completed transaction must reach cache publication");
        assertEquals(0, retained.cache.getSize());
        assertReleased(retained.cursor);
    }

    @Test
    void sessionAndCacheEvictionFailuresStillReleaseCandidates() throws Exception {
        CloseFailure failure = new CloseFailure();
        RetainedCursor retained = openCursor(failure);
        failure.enabled = true;
        retained.cache.failOnClear = true;
        manager.commit();
        assertEquals(1, failure.failures);
        assertTrue(retained.cache.failures > 0, "Completion cleanup itself must fail while evicting the cache");
        assertReleased(retained.cursor);
    }

    @Test
    void completionDuringMapperCallWaitsForTheLastUseAndDoesNotRestoreReleasedEntries() throws Exception {
        QueryCompletion completion = new QueryCompletion();
        RetainedCursor retained = openCursor(completion);
        completion.action = () -> {
            assertDoesNotThrow(manager::commit);
            assertNotNull(assertDoesNotThrow(() -> entriesRetainedBy(retained.cursor)),
                    "The active facade invocation still needs its private query context");
        };
        assertEquals(32, retained.session.selectList("cached").size());
        assertEquals(1, completion.completions);
        assertEquals(1, retained.cache.getSize(), "Publication must finish before private results are released");
        retained.cache.clear();
        assertReleased(retained.cursor);
    }

    private RetainedCursor openCursor(Interceptor interceptor) throws Exception {
        Configuration configuration = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        FailingCache cache = new FailingCache();
        configuration.addCache(cache);
        ResultMap integers = new ResultMap.Builder(configuration, "integers", Integer.class, List.of()).build();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "cached",
                new StaticSqlSource(configuration, "SELECT X FROM SYSTEM_RANGE(1, 32)"), SqlCommandType.SELECT)
                .resultMaps(List.of(integers)).cache(cache).useCache(true).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "cursor",
                new StaticSqlSource(configuration, "SELECT 42"), SqlCommandType.SELECT)
                .resultMaps(List.of(integers)).build());
        if (interceptor != null) {
            configuration.addInterceptor(interceptor);
        }
        TransactionalSqlSession session = new TransactionalSqlSession(
                new SqlSessionFactoryBuilder().build(configuration), manager);
        manager.begin();
        assertEquals(32, session.selectList("cached").size());
        Cursor<Integer> cursor = session.selectCursor("cursor");
        cursor.close();
        Object entries = entriesRetainedBy(cursor);
        assertNotNull(entries);
        Map<?, ?> regions = (Map<?, ?>) field(entries, "regions");
        int candidates = 0;
        for (Object region : regions.values()) {
            candidates += ((Map<?, ?>) field(region, "candidates")).size();
        }
        assertEquals(1, candidates, "Exercise real private cache candidates, not an empty transaction");
        assertEquals(0, cache.getSize(), "Candidates must remain private before JTA completion");
        return new RetainedCursor(cursor, cache, session);
    }

    private static void assertReleased(Cursor<?> cursor) throws Exception {
        assertNull(entriesRetainedBy(cursor), "A retained closed cursor must not retain completed transaction results");
        cursor.close();
    }

    private static Object entriesRetainedBy(Cursor<?> cursor) throws Exception {
        Object holder = field(cursor, "holder");
        return field(field(holder, "cacheSnapshot"), "entries");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private record RetainedCursor(Cursor<Integer> cursor, FailingCache cache, TransactionalSqlSession session) {
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
            RowBounds.class, ResultHandler.class }))
    public static class QueryCompletion implements Interceptor {
        Runnable action;
        int completions;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            Runnable completing = action;
            if (completing != null) {
                action = null;
                completions++;
                completing.run();
            }
            return result;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "close", args = boolean.class))
    public static class CloseFailure implements Interceptor {
        boolean enabled;
        int failures;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (enabled) {
                failures++;
                throw new IllegalStateException("Executor close failure for retention regression");
            }
            return result;
        }
    }

    public static class FailingCache extends PerpetualCache {
        boolean failOnPut;
        boolean failOnClear;
        int failures;

        public FailingCache() {
            super("retention");
        }

        @Override
        public void putObject(Object key, Object value) {
            if (failOnPut) {
                failures++;
                throw new IllegalStateException("Cache publication failure for retention regression");
            }
            super.putObject(key, value);
        }

        @Override
        public void clear() {
            if (failOnClear) {
                failures++;
                throw new IllegalStateException("Cache eviction failure for retention regression");
            }
            super.clear();
        }
    }
}
