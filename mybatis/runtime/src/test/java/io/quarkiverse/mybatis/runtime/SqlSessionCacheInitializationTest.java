package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

class SqlSessionCacheInitializationTest {
    public interface Reader {
        Integer read();
    }

    @Test
    void firstMapperCallNeverExposesAMissingStatementToAnotherCaller() throws Exception {
        AtomicBoolean published = new AtomicBoolean();
        AtomicInteger registrations = new AtomicInteger();
        AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
        java.lang.reflect.Method mapperMethod = Reader.class.getMethod("read");
        Configuration configuration = new Configuration() {
            @Override
            public void addMappedStatement(MappedStatement statement) {
                registrations.incrementAndGet();
                if (published.get()) {
                    // Deterministic interleaving: a second MapperProxy resolves its SQL before entering the facade.
                    // Previously the first business call removed the statement before reaching this registration.
                    try {
                        new MapperMethod.SqlCommand(this, Reader.class, mapperMethod);
                    } catch (Throwable failure) {
                        resolutionFailure.set(failure);
                    }
                }
                super.addMappedStatement(statement);
            }
        };
        configuration.addMapper(Reader.class);
        addStatement(configuration, Reader.class.getName() + ".read", new PerpetualCache("cache"));
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        int beforeCalls = registrations.get();
        published.set(true);

        assertEquals(1, session.getMapper(Reader.class).read());
        assertEquals(1, session.getMapper(Reader.class).read());

        assertNull(resolutionFailure.get(), "Another caller must never observe a temporarily missing SQL mapping");
        assertEquals(beforeCalls, registrations.get(), "Business calls must not re-register mapped statements");
        assertEquals(2, opens.get());
    }

    @Test
    void constructorDecoratesBeforeOpeningAnySessionAndAdditionalFacadesReuseIt() {
        Configuration configuration = new Configuration();
        Cache originalCache = new PerpetualCache("cache");
        MappedStatement original = addStatement(configuration, "read", originalCache);
        AtomicInteger opens = new AtomicInteger();
        SqlSessionFactory factory = factory(configuration, opens);

        new TransactionalSqlSession(factory, null);

        MappedStatement decorated = configuration.getMappedStatement("read");
        assertNotSame(original, decorated, "Decoration must finish before the facade constructor returns");
        assertNotSame(originalCache, decorated.getCache());
        assertEquals(0, opens.get(), "Cache initialization must not acquire a session or database connection");
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        try (SqlSessionCacheCoordinator.Completion active = coordinator.begin(coordinator.snapshot(configuration))) {
            decorated.getCache().putObject("key", "committed");

            new TransactionalSqlSession(factory, null);

            assertSame(decorated, configuration.getMappedStatement("read"));
            assertTrue(active.canPublish(), "Constructing another facade must not advance the cache generation");
            assertEquals("committed", decorated.getCache().getObject("key"));
            assertEquals(0, opens.get());
            active.success();
        }
    }

    @Test
    void disabledCachingIsAlsoPreparedBeforePublication() {
        Configuration configuration = new Configuration();
        configuration.setCacheEnabled(false);
        Cache original = new PerpetualCache("cache");
        addStatement(configuration, "read", original);
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        MappedStatement decorated = configuration.getMappedStatement("read");
        assertNotSame(original, decorated.getCache());
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        assertFalse(coordinator.snapshot(configuration).hasCaches());

        configuration.setCacheEnabled(true);

        assertTrue(coordinator.snapshot(configuration).hasCaches());
        assertEquals(1, (Integer) session.selectOne("read"));
        assertSame(decorated, configuration.getMappedStatement("read"));
    }

    @Test
    void uncoordinatedLateCachedStatementDoesNotDisableExistingMappings() {
        Configuration configuration = new Configuration();
        Cache cache = new PerpetualCache("cache");
        addStatement(configuration, "read", cache);
        AtomicInteger opens = new AtomicInteger();
        SqlSessionFactory factory = factory(configuration, opens);
        TransactionalSqlSession session = new TransactionalSqlSession(factory, null);
        MappedStatement existing = configuration.getMappedStatement("read");
        MappedStatement added = addStatement(configuration, "late", cache);

        assertEquals(1, (Integer) session.selectOne("read"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> session.selectOne("late"));

        assertTrue(failure.getMessage().contains("CacheRegistrationSupport.prepare"));
        new TransactionalSqlSession(factory, null);
        assertEquals(1, opens.get(), "Only the unsupported statement must fail before opening a session");
        assertSame(existing, configuration.getMappedStatement("read"));
        assertSame(added, configuration.getMappedStatement("late"));
        assertSame(cache, added.getCache(), "Rejected changes must not trigger live cache decoration");
    }

    @Test
    void lateRegistrationRemainsUsableWhenCachingIsDisabled() {
        Configuration configuration = new Configuration();
        configuration.setCacheEnabled(false);
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        Cache late = new PerpetualCache("late");
        configuration.addCache(late);
        addStatement(configuration, "read", late);

        assertEquals(1, (Integer) session.selectOne("read"));
        assertEquals(1, opens.get());
    }

    @Test
    void registrationHookProtectsNewStatementsBeforePublicationAndReusesSharedCacheGuard() {
        Configuration configuration = new MyBatisConfiguration();
        Cache cache = new PerpetualCache("shared");
        addStatement(configuration, "existing.read", cache);
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        MappedStatement original = configuration.getMappedStatement("existing.read");
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        try (SqlSessionCacheCoordinator.Completion active = coordinator.begin(coordinator.snapshot(configuration))) {
            configuration.addMapper(Reader.class);
            addStatement(configuration, Reader.class.getName() + ".read", cache);
            assertSame(original, configuration.getMappedStatement("existing.read"));
            assertSame(original.getCache(), configuration.getMappedStatement(Reader.class.getName() + ".read").getCache());
            assertTrue(active.canPublish(), "Adding a statement must not invalidate existing transactions");
            assertEquals(1, session.getMapper(Reader.class).read());
            active.success();
        }
        assertEquals(1, (Integer) session.selectOne("existing.read"));
    }

    @Test
    void customConfigurationAllowsLateUncachedStatementsWithoutRewritingOldStatements() {
        Configuration configuration = new Configuration();
        addStatement(configuration, "read", new PerpetualCache("cache"));
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        MappedStatement original = configuration.getMappedStatement("read");

        addStatement(configuration, "late", null);

        assertEquals(1, (Integer) session.selectOne("late"));
        assertEquals(1, (Integer) session.selectOne("read"));
        assertSame(original, configuration.getMappedStatement("read"));
    }

    @Test
    void lateReplacementWithUnchangedCountIsRejectedOnlyWhenUsed() {
        Configuration configuration = new Configuration();
        Cache cache = new PerpetualCache("cache");
        addStatement(configuration, "read", cache);
        addStatement(configuration, "other", cache);
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        MappedStatement previous = configuration.getMappedStatement("read");
        configuration.getMappedStatements().removeIf(statement -> statement == previous);
        addStatement(configuration, "read", cache);

        assertThrows(IllegalStateException.class, () -> session.selectOne("read"));
        assertEquals(1, (Integer) session.selectOne("other"));
        assertEquals(1, opens.get());
    }

    @Test
    void unguardedLateNestedQueryIsRejectedBeforeOpeningASession() {
        Configuration configuration = new Configuration();
        addStatement(configuration, "existing", null);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "parent",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .resultMaps(java.util.List.of(new org.apache.ibatis.mapping.ResultMap.Builder(configuration,
                        "parent-result", Object.class, java.util.List.of(new org.apache.ibatis.mapping.ResultMapping.Builder(
                                configuration, "detail", "ID", Integer.class).nestedQueryId("late").lazy(true).build()))
                        .build()))
                .build());
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        addStatement(configuration, "late", new PerpetualCache("late-cache"));

        assertThrows(IllegalStateException.class, () -> session.selectOne("parent"));
        assertEquals(0, opens.get(), "Nested and lazy queries must not bypass validation");
        assertEquals(1, (Integer) session.selectOne("existing"));
    }

    @Test
    void xmlBuilderUsesConfigurationWithRegistrationHooks() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
                  "https://mybatis.org/dtd/mybatis-3-config.dtd">
                <configuration/>
                """;
        Configuration configuration = new org.apache.ibatis.builder.xml.XMLConfigBuilder(MyBatisConfiguration.class,
                new java.io.StringReader(xml), null, null).parse();
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        Cache cache = new PerpetualCache("late");
        addStatement(configuration, "read", cache);

        assertNotSame(cache, configuration.getMappedStatement("read").getCache());
        assertEquals(1, (Integer) session.selectOne("read"));
    }

    @Test
    void pendingLateCachedStatementCannotAppearAfterValidationAndBypassTheGuard() {
        AtomicBoolean pending = new AtomicBoolean();
        Cache cache = new PerpetualCache("pending-cache");
        Configuration configuration = new Configuration() {
            @Override
            protected void buildAllStatements() {
                super.buildAllStatements();
                if (pending.compareAndSet(true, false)) {
                    addStatement(this, "pending", cache);
                }
            }
        };
        addStatement(configuration, "existing", null);
        AtomicInteger opens = new AtomicInteger();
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, opens), null);
        pending.set(true);

        assertThrows(IllegalStateException.class, () -> session.selectOne("pending"));
        assertEquals(0, opens.get());
        assertEquals(1, (Integer) session.selectOne("existing"));
    }

    private static MappedStatement addStatement(Configuration configuration, String id, Cache cache) {
        MappedStatement statement = new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build();
        configuration.addMappedStatement(statement);
        return statement;
    }

    @Test
    void failedDuplicateStatementDoesNotInstallItsBlockingCache() {
        Configuration configuration = new MyBatisConfiguration();
        Cache cache = new PerpetualCache("existing");
        addStatement(configuration, "read", cache);
        TransactionalSqlSession session = new TransactionalSqlSession(factory(configuration, new AtomicInteger()), null);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        Object inventory = coordinator.snapshot(configuration).inventory();
        Cache rejected = new org.apache.ibatis.cache.decorators.BlockingCache(new PerpetualCache("rejected"));

        assertThrows(IllegalArgumentException.class, () -> addStatement(configuration, "read", rejected));

        assertSame(inventory, coordinator.snapshot(configuration).inventory());
        coordinator.snapshot(configuration).requireNonBlocking();
        assertEquals(1, (Integer) session.selectOne("read"));
    }

    @Test
    void failedDuplicateCacheDoesNotInstallMetadata() {
        Configuration configuration = new MyBatisConfiguration();
        Cache cache = new PerpetualCache("shared");
        configuration.addCache(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        Object inventory = coordinator.snapshot(configuration).inventory();

        assertThrows(IllegalArgumentException.class, () -> configuration.addCache(
                new org.apache.ibatis.cache.decorators.BlockingCache(new PerpetualCache("shared"))));

        assertSame(inventory, coordinator.snapshot(configuration).inventory());
        assertEquals(java.util.List.of(cache), coordinator.snapshot(configuration).caches());
        coordinator.snapshot(configuration).requireNonBlocking();
    }

    @Test
    void ignoredDuplicateStatementDoesNotInstallMetadata() {
        Configuration configuration = new MyBatisConfiguration();
        addStatement(configuration, "read", new PerpetualCache("shared"));
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        MappedStatement original = configuration.getMappedStatement("read");
        Object inventory = coordinator.snapshot(configuration).inventory();
        MappedStatement ignored = new MappedStatement.Builder(configuration, "read",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT)
                .cache(new org.apache.ibatis.cache.decorators.BlockingCache(new PerpetualCache("ignored"))).build();

        CacheRegistrationSupport.addMappedStatement(ignored, statement -> {
            // MyBatis-Plus may return normally without accepting a duplicate statement.
        });

        assertSame(original, configuration.getMappedStatement("read"));
        assertSame(inventory, coordinator.snapshot(configuration).inventory());
        coordinator.snapshot(configuration).requireNonBlocking();
    }

    @Test
    void concurrentSnapshotCannotObservePublicationWithoutItsCacheInventory() throws Exception {
        Configuration configuration = new Configuration();
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        Cache cache = new PerpetualCache("late");
        java.util.concurrent.CountDownLatch published = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch snapshotStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> registration = executor
                    .submit(() -> CacheRegistrationSupport.addCache(configuration, cache, added -> {
                        configuration.addCache(added);
                        published.countDown();
                        try {
                            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }));
            assertTrue(published.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<SqlSessionCacheCoordinator.Snapshot> snapshot = executor.submit(() -> {
                snapshotStarted.countDown();
                return coordinator.snapshot(configuration);
            });
            assertTrue(snapshotStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(snapshot.isDone());
            release.countDown();
            registration.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.List.of(cache), snapshot.get(10, java.util.concurrent.TimeUnit.SECONDS).caches());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static SqlSessionFactory factory(Configuration configuration, AtomicInteger opens) {
        SqlSession delegate = (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(),
                new Class<?>[] { SqlSession.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> {
                        configuration.getMappedStatement((String) args[0]);
                        yield 1;
                    }
                    case "commit", "close" -> null;
                    default -> throw new AssertionError("Unexpected session operation " + method.getName());
                });
        return (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getConfiguration" -> configuration;
                    case "openSession" -> {
                        opens.incrementAndGet();
                        yield delegate;
                    }
                    default -> throw new AssertionError("Unexpected factory operation " + method.getName());
                });
    }
}
