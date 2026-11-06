package io.quarkiverse.mybatis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

class SqlSessionCacheCoordinatorTest {
    @Test
    void immediateSqlCommitAndCloseDoNotHoldTheCoordinatorMonitor() {
        Configuration configuration = configurationWith(new PerpetualCache("test.cache"));
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), new UnpooledDataSource()));
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        java.util.ArrayList<String> operations = new java.util.ArrayList<>();
        SqlSession delegate = (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(),
                new Class<?>[] { SqlSession.class }, (proxy, method, args) -> {
                    assertFalse(Thread.holdsLock(coordinator), method.getName() + " must run outside the cache monitor");
                    operations.add(method.getName());
                    return switch (method.getName()) {
                        case "selectList" -> List.of("row");
                        case "commit", "close" -> null;
                        default -> throw new AssertionError("Unexpected session operation " + method.getName());
                    };
                });
        SqlSessionFactory factory = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                new Class<?>[] { SqlSessionFactory.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getConfiguration" -> configuration;
                    case "openSession" -> {
                        assertFalse(Thread.holdsLock(coordinator), "Opening a session must run outside the cache monitor");
                        yield delegate;
                    }
                    default -> throw new AssertionError("Unexpected factory operation " + method.getName());
                });

        TransactionalSqlSession session = new TransactionalSqlSession(factory, null);

        assertEquals(List.of("row"), session.selectList("test.select"));
        assertEquals(List.of("selectList", "commit", "close"), operations);
    }

    @Test
    void overlappingWriterInvalidatesImmediatelyAndRejectsLatePublication() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot firstSnapshot = coordinator.snapshot(configuration);
        SqlSessionCacheCoordinator.Snapshot secondSnapshot = coordinator.snapshot(configuration);
        Cache guarded = configuration.getMappedStatement("test.select").getCache();
        cache.putObject("value", "old value");

        try (SqlSessionCacheCoordinator.Completion first = coordinator.begin(firstSnapshot)) {
            assertTrue(first.canPublish());
            try (SqlSessionCacheCoordinator.Completion second = coordinator.begin(secondSnapshot)) {
                second.invalidate();
                second.success();
            }
            assertEquals(1, cache.clears, "The writer must invalidate before returning, while the old call is active");
            assertEquals(0, cache.getSize());
            assertFalse(first.canPublish());
            guarded.putObject("value", "late old value");
            assertEquals(0, cache.getSize(), "An old call must not refill the cleared cache");

            try (SqlSessionCacheCoordinator.Completion fresh = coordinator.begin(coordinator.snapshot(configuration))) {
                guarded.putObject("value", "new value");
                assertEquals("new value", guarded.getObject("value"));
                fresh.success();
            }
            assertEquals(null, guarded.getObject("value"), "Nested completion must restore the older caller's scope");
            guarded.putObject("value", "another late old value");
            first.success();
        }

        assertEquals(1, cache.clears);
        assertEquals("new value", cache.getObject("value"));
        try (SqlSessionCacheCoordinator.Completion next = coordinator.begin(coordinator.snapshot(configuration))) {
            assertTrue(next.canPublish(), "A completed eviction must allow later uncontended publications");
            next.success();
        }
    }

    @Test
    void aSnapshotTakenBeforeAnotherCompletionCannotRepublishOldResults() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot oldRead = coordinator.snapshot(configuration);
        Cache guarded = configuration.getMappedStatement("test.select").getCache();

        try (SqlSessionCacheCoordinator.Completion write = coordinator.begin(coordinator.snapshot(configuration))) {
            guarded.clear();
            guarded.putObject("value", "new value");
            write.success();
        }
        assertEquals(1, cache.clears);

        try (SqlSessionCacheCoordinator.Completion lateRead = coordinator.begin(oldRead)) {
            assertFalse(lateRead.canPublish());
            guarded.putObject("value", "old value");
            lateRead.success();
        }

        assertEquals(1, cache.clears);
        assertEquals("new value", cache.getObject("value"));
    }

    @Test
    void invalidationFailureStillClearsOtherCachesAndRetriesBeforeTheNextRead() {
        TrackingCache failing = new TrackingCache("test.failing");
        TrackingCache healthy = new TrackingCache("test.healthy");
        RuntimeException providerFailure = new IllegalStateException("cache provider unavailable");
        failing.failure = providerFailure;
        // Keep the failing cache first so the test verifies that clearing continues after the exception.
        Configuration configuration = new Configuration() {
            @Override
            public Collection<Cache> getCaches() {
                return List.of(failing, healthy);
            }
        };
        failing.putObject("value", "stale");
        healthy.putObject("value", "stale");
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Completion completion = coordinator.begin(coordinator.snapshot(configuration));
        completion.invalidate();
        completion.success();

        IllegalStateException failure = assertThrows(IllegalStateException.class, completion::close);

        assertSame(providerFailure, failure.getCause());
        assertEquals(1, failing.clears);
        assertEquals(1, healthy.clears);
        assertEquals(0, healthy.getSize());
        failing.failure = null;

        SqlSessionCacheCoordinator.Snapshot retried = coordinator.snapshot(configuration);

        assertEquals(2, failing.clears);
        assertEquals(2, healthy.clears);
        assertEquals(0, failing.getSize());
        try (SqlSessionCacheCoordinator.Completion next = coordinator.begin(retried)) {
            assertTrue(next.canPublish());
            next.success();
        }
    }

    @Test
    void repeatedInvalidationFailureStopsSnapshotAcquisitionUntilTheCacheRecovers() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Completion completion = coordinator.begin(coordinator.snapshot(configuration));
        cache.failure = new IllegalStateException("cache provider unavailable");
        completion.invalidate();
        completion.success();
        assertThrows(IllegalStateException.class, completion::close);

        assertThrows(IllegalStateException.class, () -> coordinator.snapshot(configuration));
        assertEquals(2, cache.clears);

        cache.failure = null;
        assertTrue(coordinator.snapshot(configuration).hasCaches());
        assertEquals(3, cache.clears);
    }

    @Test
    void cachesAttachedOnlyToMappedStatementsAreIncludedAndEvictedOnce() {
        TrackingCache cache = new TrackingCache("unregistered.cache");
        Configuration configuration = new Configuration();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.select",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).build());
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.otherSelect",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).build());
        assertFalse(configuration.hasCache(cache.getId()));
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);

        assertTrue(snapshot.hasCaches());
        assertEquals(List.of(cache), snapshot.caches());
        cache.putObject("value", "stale");
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(snapshot)) {
            completion.invalidate();
            completion.success();
        }

        assertEquals(1, cache.clears);
        assertEquals(0, cache.getSize());
    }

    @Test
    void unsuccessfulCompletionEvictsPotentialPartialPublication() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);

        try (SqlSessionCacheCoordinator.Completion ignored = coordinator.begin(coordinator.snapshot(configuration))) {
            cache.putObject("value", "partially published");
        }

        assertEquals(1, cache.clears);
        assertEquals(0, cache.getSize());
    }

    @Test
    void failedCustomOpenerThatAccessedCacheStillInvalidates() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        cache.putObject("key", "warm");
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(coordinator.snapshot(configuration))) {
            configuration.getMappedStatement("test.select").getCache().putObject("partial", "value");
            completion.openingFailed(true);
        }
        assertEquals(1, cache.clears);
        assertEquals(0, cache.getSize());
    }

    @Test
    void unchangedConfigurationDoesNotRescanMappedStatementsEvenWithoutSecondLevelCaches() {
        int[] iterations = { 0 };
        Configuration configuration = new Configuration() {
            @Override
            public Collection<MappedStatement> getMappedStatements() {
                Collection<MappedStatement> statements = super.getMappedStatements();
                return new java.util.AbstractCollection<>() {
                    @Override
                    public java.util.Iterator<MappedStatement> iterator() {
                        iterations[0]++;
                        return statements.iterator();
                    }

                    @Override
                    public int size() {
                        return statements.size();
                    }
                };
            }
        };
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.uncached",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).build());
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        assertFalse(coordinator.snapshot(configuration).hasCaches());
        int firstScan = iterations[0];
        assertFalse(coordinator.snapshot(configuration).hasCaches());
        assertFalse(coordinator.snapshot(configuration).hasCaches());
        assertEquals(firstScan, iterations[0]);

        Cache cache = new PerpetualCache("late.cache");
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.cached",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
        MappedStatement added = configuration.getMappedStatement("test.cached");
        coordinator.snapshot(configuration);
        assertSame(coordinator, SqlSessionCacheCoordinator.forConfiguration(configuration));
        assertThrows(IllegalStateException.class, () -> coordinator.validateStatement(configuration, "test.cached"));
        assertEquals(firstScan, iterations[0], "Late registration must not rescan or rewrite live mappings");
        assertSame(added, configuration.getMappedStatement("test.cached"));
        assertSame(cache, added.getCache());
    }

    @Test
    void cacheDecorationPreservesStatementOptionsAndAmbiguousAliases() {
        Configuration configuration = new Configuration();
        Cache cache = new PerpetualCache("test.cache");
        MappedStatement original = new MappedStatement.Builder(configuration, "one.read",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).flushCacheRequired(true).dirtySelect(true).resultOrdered(true)
                .resource("mapper.xml").timeout(5).fetchSize(7).databaseId("h2")
                .statementType(org.apache.ibatis.mapping.StatementType.CALLABLE)
                .resultSetType(org.apache.ibatis.mapping.ResultSetType.SCROLL_INSENSITIVE)
                .keyGenerator(org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator.INSTANCE)
                .keyProperty("id,other").keyColumn("ID,OTHER").resultSets("first,second").build();
        configuration.addMappedStatement(original);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "two.read",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT).cache(cache).useCache(true).build());

        SqlSessionCacheCoordinator.forConfiguration(configuration).snapshot(configuration);

        MappedStatement decorated = configuration.getMappedStatement("one.read");
        assertSame(original.getConfiguration(), decorated.getConfiguration());
        assertSame(original.getSqlSource(), decorated.getSqlSource());
        assertSame(original.getParameterMap(), decorated.getParameterMap());
        assertEquals(original.getResultMaps(), decorated.getResultMaps());
        assertSame(original.getKeyGenerator(), decorated.getKeyGenerator());
        assertSame(original.getLang(), decorated.getLang());
        assertEquals(original.getId(), decorated.getId());
        assertEquals(original.getResource(), decorated.getResource());
        assertEquals(original.getTimeout(), decorated.getTimeout());
        assertEquals(original.getFetchSize(), decorated.getFetchSize());
        assertEquals(original.getDatabaseId(), decorated.getDatabaseId());
        assertEquals(original.getStatementType(), decorated.getStatementType());
        assertEquals(original.getResultSetType(), decorated.getResultSetType());
        assertEquals(original.getSqlCommandType(), decorated.getSqlCommandType());
        assertTrue(decorated.isUseCache());
        assertTrue(decorated.isFlushCacheRequired());
        assertTrue(decorated.isDirtySelect());
        assertTrue(decorated.isResultOrdered());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original.getKeyProperties(), decorated.getKeyProperties());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original.getKeyColumns(), decorated.getKeyColumns());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original.getResultSets(), decorated.getResultSets());
        assertSame(decorated.getCache(), configuration.getMappedStatement("two.read").getCache());
        assertThrows(IllegalArgumentException.class, () -> configuration.getMappedStatement("read"));
    }

    @Test
    void invalidationFailureBlocksOldScopeReadsAndPublicationUntilRetry() {
        TrackingCache cache = new TrackingCache("test.cache");
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);
        Cache guarded = configuration.getMappedStatement("test.select").getCache();
        cache.putObject("value", "stale");
        try (SqlSessionCacheCoordinator.Scope ignored = coordinator.enter(snapshot)) {
            cache.failure = new IllegalStateException("cache unavailable");
            assertThrows(IllegalStateException.class, guarded::clear);
            assertEquals(null, guarded.getObject("value"));
            guarded.putObject("other", "must not publish");
            assertEquals(null, cache.getObject("other"));
            cache.failure = null;
            coordinator.snapshot(configuration);
            guarded.putObject("value", "late old value");
            assertEquals(0, cache.getSize());
        }
    }

    @Test
    void staleBlockingCacheScopeReleasesOnlyItsOwnMissesWithoutWaitingUnderMonitor() {
        SqlSessionCacheCoordinator[] owner = new SqlSessionCacheCoordinator[1];
        int[] removals = { 0 };
        Cache cache = new org.apache.ibatis.cache.decorators.BlockingCache(new PerpetualCache("test.cache")) {
            @Override
            public Object getObject(Object key) {
                assertFalse(Thread.holdsLock(owner[0]), "A cache-miss lock must be acquired outside the monitor");
                return super.getObject(key);
            }

            @Override
            public Object removeObject(Object key) {
                removals[0]++;
                return super.removeObject(key);
            }
        };
        Configuration configuration = configurationWith(cache);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        owner[0] = coordinator;
        SqlSessionCacheCoordinator.Snapshot old = coordinator.snapshot(configuration);
        Cache guarded = configuration.getMappedStatement("test.select").getCache();
        try (SqlSessionCacheCoordinator.Scope oldScope = coordinator.enter(old)) {
            assertEquals(null, guarded.getObject("old"));
            try (SqlSessionCacheCoordinator.Completion writer = coordinator.begin(coordinator.snapshot(configuration))) {
                writer.invalidate();
                writer.success();
            }
            try (SqlSessionCacheCoordinator.Scope fresh = coordinator.enter(coordinator.snapshot(configuration))) {
                assertEquals(null, guarded.getObject("fresh"));
                try (SqlSessionCacheCoordinator.Scope stale = coordinator.enter(old)) {
                    assertEquals(null, guarded.getObject("fresh"));
                    guarded.putObject("fresh", "stale");
                    guarded.removeObject("fresh");
                    assertEquals(0, removals[0], "A bypassed lookup must not release another scope's cache-miss lock");
                    guarded.putObject("old", "stale");
                    assertEquals(1, removals[0], "A discarded publication must release its own cache-miss lock");
                }
                guarded.putObject("fresh", "current");
                assertEquals("current", guarded.getObject("fresh"));
            }
        }
    }

    @Test
    void snapshotsShareOnlyImmutableInventoryAndObserveCachesAddedDuringATransaction() {
        Configuration configuration = new MyBatisConfiguration();
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot before = coordinator.snapshot(configuration);
        SqlSessionCacheCoordinator.Snapshot another = coordinator.snapshot(configuration);
        assertSame(before.inventory(), another.inventory());
        assertFalse(before.hasCaches());

        Cache cache = new PerpetualCache("late");
        configuration.addCache(cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.late",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
        assertTrue(before.hasCaches(), "Existing transactions must complete caches registered after their first call");
        assertSame(coordinator.snapshot(configuration).inventory(), coordinator.snapshot(configuration).inventory());
        Cache guarded = configuration.getMappedStatement("test.late").getCache();
        try (SqlSessionCacheCoordinator.Completion old = coordinator.begin(before)) {
            try (SqlSessionCacheCoordinator.Completion writer = coordinator.begin(another)) {
                guarded.clear();
                assertTrue(writer.canPublish());
                writer.success();
            }
            assertFalse(old.canPublish(), "A writer must not update another snapshot's mutable generation");
            guarded.putObject("key", "stale");
            assertEquals(0, cache.getSize());
            old.success();
        }
    }

    @Test
    void blockingCacheRegisteredAfterValidationIsRejectedAtAccessBeforeAcquiringALock() {
        Configuration configuration = new MyBatisConfiguration();
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);
        snapshot.requireNonBlocking();
        BlockingCache cache = new BlockingCache(new PerpetualCache("late-blocking"));
        cache.setTimeout(100);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.late",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
        Cache guarded = configuration.getMappedStatement("test.late").getCache();
        try (SqlSessionCacheCoordinator.Scope ignored = coordinator.enter(snapshot)) {
            assertThrows(BlockingCacheNotSupportedException.class, () -> guarded.getObject("key"));
        }
        assertEquals(null, cache.getObject("key"), "The rejected access must not own a cache-miss lock");
        cache.putObject("key", "value");
    }

    @Test
    void coordinatedReplacementInvalidatesOldEntriesAndPendingPublications() {
        Configuration configuration = new MyBatisConfiguration();
        TrackingCache cache = new TrackingCache("cache");
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.read",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot old = coordinator.snapshot(configuration);
        MappedStatement original = configuration.getMappedStatement("test.read");
        cache.putObject("key", "old mapping");
        configuration.getMappedStatements().removeIf(statement -> statement == original);

        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.read",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT).cache(cache).useCache(true).build());

        assertEquals(1, cache.clears);
        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(old)) {
            assertFalse(completion.canPublish());
            original.getCache().putObject("key", "late old mapping");
            assertEquals(0, cache.getSize());
            completion.success();
        }
        assertSame(original.getCache(), configuration.getMappedStatement("test.read").getCache());
    }

    @Test
    void cacheRegisteredDuringFactoryWriteFenceIsClearedBeforeTheFenceCanOpen() {
        Configuration configuration = new MyBatisConfiguration();
        TrackingCache existing = new TrackingCache("existing");
        existing.putObject("old", "value");
        configuration.addCache(existing);
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);
        SqlSessionCacheCoordinator.StatementAccess writeWithoutKnownRegion = new SqlSessionCacheCoordinator.StatementAccess();
        writeWithoutKnownRegion.readOnly = false;

        try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(snapshot)) {
            snapshot.beforePotentialWrite(writeWithoutKnownRegion, false);
            assertEquals(0, existing.getSize());

            Cache late = new PerpetualCache("late-during-write");
            late.putObject("old", "value");
            configuration.addCache(late);

            assertEquals(0, late.getSize(), "A late cache must not escape an active factory write fence");
            completion.releaseInvalidations();
            completion.success();
        }
    }

    private static Configuration configurationWith(Cache cache) {
        Configuration configuration = new Configuration();
        configuration.addCache(cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "test.select",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(true).build());
        return configuration;
    }

    @Test
    void slowRegionReadDoesNotBlockAnotherRegionOrSnapshot() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Cache slow = new PerpetualCache("slow") {
            @Override
            public Object getObject(Object key) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return "slow-value";
            }
        };
        Configuration configuration = configurationWith(slow);
        Cache fast = new PerpetualCache("fast");
        configuration.addCache(fast);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "fast.read",
                new StaticSqlSource(configuration, "SELECT 2"), SqlCommandType.SELECT).cache(fast).useCache(true).build());
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        Cache slowGuard = configuration.getMappedStatement("test.select").getCache();
        Cache fastGuard = configuration.getMappedStatement("fast.read").getCache();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> blocked = executor.submit(() -> slowGuard.getObject("key"));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> independent = executor.submit(() -> {
                SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);
                snapshot.invocation(coordinator.validateStatement(configuration, "fast.read"));
                try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(snapshot)) {
                    fastGuard.putObject("key", "fast-value");
                    assertEquals("fast-value", fastGuard.getObject("key"));
                    fastGuard.clear();
                    assertTrue(completion.canPublish());
                    completion.success();
                }
            });
            independent.get(2, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            blocked.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void decoratorCannotPublishBeforeItsInventoryIsInstalled() {
        Configuration configuration = new MyBatisConfiguration();
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot before = coordinator.snapshot(configuration);
        java.util.concurrent.atomic.AtomicBoolean checked = new java.util.concurrent.atomic.AtomicBoolean();
        Cache cache = new BlockingCache(new PerpetualCache("late")) {
            @Override
            public String getId() {
                if (configuration.hasStatement("late.read", false) && checked.compareAndSet(false, true)) {
                    assertFalse(before.hasCaches(), "Inventory has not yet been assigned at this point");
                    Cache prepared = configuration.getMappedStatement("late.read", false).getCache();
                    assertThrows(IllegalStateException.class, () -> prepared.getObject("probe"),
                            "The publication flag must stay false until inventory assignment completes");
                }
                return super.getId();
            }
        };
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "late.read",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
        assertTrue(checked.get());
        assertTrue(before.hasCaches());
        assertThrows(BlockingCacheNotSupportedException.class, before::requireNonBlocking);
    }

    @Test
    void providerCanReenterMetadataWhileEvictionWaitsForItsRead() throws Exception {
        Configuration configuration = new Configuration();
        SqlSessionCacheCoordinator[] coordinator = new SqlSessionCacheCoordinator[1];
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch evictionStarted = new java.util.concurrent.CountDownLatch(1);
        Cache cache = new PerpetualCache("reentrant") {
            @Override
            public Object getObject(Object key) {
                entered.countDown();
                try {
                    assertTrue(evictionStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertTrue(coordinator[0].snapshot(configuration).hasCaches());
                return "value";
            }

            @Override
            public void clear() {
                assertFalse(Thread.holdsLock(coordinator[0]), "Provider I/O must not hold the metadata monitor");
                super.clear();
            }
        };
        configuration.addCache(cache);
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, "read",
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).build());
        coordinator[0] = SqlSessionCacheCoordinator.forConfiguration(configuration);
        Cache guarded = configuration.getMappedStatement("read").getCache();
        SqlSessionCacheCoordinator.Snapshot writer = coordinator[0].snapshot(configuration);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> reading = executor.submit(() -> guarded.getObject("key"));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> evicting = executor.submit(() -> {
                try (SqlSessionCacheCoordinator.Completion completion = coordinator[0].begin(writer)) {
                    completion.invalidate();
                    completion.success();
                    evictionStarted.countDown();
                }
            });
            reading.get(10, java.util.concurrent.TimeUnit.SECONDS);
            evicting.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            evictionStarted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void recursiveRecoveryFailsWithoutDeadlockingAndCanRetryAfterProviderRecovers() {
        Configuration configuration = new Configuration();
        SqlSessionCacheCoordinator[] coordinator = new SqlSessionCacheCoordinator[1];
        java.util.concurrent.atomic.AtomicBoolean recurse = new java.util.concurrent.atomic.AtomicBoolean(true);
        Cache cache = new PerpetualCache("recursive") {
            @Override
            public void clear() {
                if (recurse.get()) {
                    coordinator[0].snapshot(configuration);
                }
                super.clear();
            }
        };
        configuration.addCache(cache);
        coordinator[0] = SqlSessionCacheCoordinator.forConfiguration(configuration);
        try (SqlSessionCacheCoordinator.Completion completion = coordinator[0].begin(coordinator[0].snapshot(configuration))) {
            completion.invalidate();
            completion.success();
            assertThrows(IllegalStateException.class, completion::close);
        }
        recurse.set(false);
        assertTrue(coordinator[0].snapshot(configuration).hasCaches());
    }

    @Test
    void registrationWaitsUntilFactoryRecoveryFinishes() throws Exception {
        java.util.concurrent.CountDownLatch clearing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch registering = new java.util.concurrent.CountDownLatch(1);
        Configuration configuration = new MyBatisConfiguration();
        configuration.addCache(new PerpetualCache("slow-clear") {
            @Override
            public void clear() {
                clearing.countDown();
                try {
                    assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                super.clear();
            }
        });
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.forConfiguration(configuration);
        SqlSessionCacheCoordinator.Snapshot snapshot = coordinator.snapshot(configuration);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> recovery = executor.submit(() -> {
                try (SqlSessionCacheCoordinator.Completion completion = coordinator.begin(snapshot)) {
                    completion.invalidate();
                    completion.success();
                }
            });
            assertTrue(clearing.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> registration = executor.submit(() -> {
                registering.countDown();
                configuration.addCache(new PerpetualCache("late"));
            });
            assertTrue(registering.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> registration.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            recovery.get(10, java.util.concurrent.TimeUnit.SECONDS);
            registration.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, coordinator.snapshot(configuration).caches().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static final class TrackingCache extends PerpetualCache {
        private int clears;
        private RuntimeException failure;

        private TrackingCache(String id) {
            super(id);
        }

        @Override
        public void clear() {
            clears++;
            if (failure != null) {
                throw failure;
            }
            super.clear();
        }
    }
}
