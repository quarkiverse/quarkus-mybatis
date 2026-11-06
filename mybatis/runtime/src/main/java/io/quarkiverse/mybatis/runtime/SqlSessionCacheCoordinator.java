package io.quarkiverse.mybatis.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;

import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;

/**
 * Coordinates cache access, not SQL execution. Invalidation and publication checks are atomic with
 * respect to each other, so an older session cannot refill a cache after a committed write.
 */
final class SqlSessionCacheCoordinator {
    private static final Map<Configuration, SqlSessionCacheCoordinator> COORDINATORS = new WeakHashMap<>();

    // A later facade may join after the database snapshot was established through an earlier facade.
    // Weak keys also cover openers that fail before they can register a completion callback.
    private final Map<Transaction, Long> managedStarts = Collections.synchronizedMap(new WeakHashMap<>());

    private record DeferredReads(TransactionManager manager, Set<Transaction> transactions) {
    }

    private final List<DeferredReads> deferredReads = new CopyOnWriteArrayList<>();
    // Weak references prevent custom caches from retaining the Configuration registry key indirectly.
    private volatile CacheInventory inventory = new CacheInventory(List.of(), null);
    private final List<WeakReference<GuardedCache>> guards = new ArrayList<>();
    private final Set<String> statementIds = new HashSet<>();
    private final ThreadLocal<Snapshot> current = new ThreadLocal<>();
    private int cacheCount = -1;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger pendingFactoryInvalidations = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pendingRegionalInvalidations = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long factoryClear;
    private final java.util.concurrent.locks.ReentrantReadWriteLock publication = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile boolean evictionFailed;

    static SqlSessionCacheCoordinator forConfiguration(Configuration configuration) {
        SqlSessionCacheCoordinator coordinator;
        synchronized (COORDINATORS) {
            coordinator = COORDINATORS.computeIfAbsent(configuration, config -> {
                SqlSessionCacheCoordinator created = new SqlSessionCacheCoordinator();
                // Finish all mapping changes before the first facade can be published to application code.
                created.prepareCaches(config);
                return created;
            });
        }
        return coordinator;
    }

    static SqlSessionCacheCoordinator existing(Configuration configuration) {
        synchronized (COORDINATORS) {
            return COORDINATORS.get(configuration);
        }
    }

    void transactionCompleted(Transaction transaction) {
        synchronized (managedStarts) {
            managedStarts.remove(transaction);
            if (!deferredReads.isEmpty()) {
                deferredReads.removeIf(reads -> reads.transactions.remove(transaction) && reads.transactions.isEmpty());
            }
        }
    }

    void trackDeferredReads(TransactionManager manager, Transaction transaction) {
        synchronized (managedStarts) {
            // A timeout may already have completed this holder after it was acquired.
            if (!managedStarts.containsKey(transaction)) {
                return;
            }
            for (DeferredReads reads : deferredReads) {
                if (reads.manager == manager) {
                    reads.transactions.add(transaction);
                    return;
                }
            }
            Set<Transaction> transactions = ConcurrentHashMap.newKeySet();
            transactions.add(transaction);
            deferredReads.add(new DeferredReads(manager, transactions));
        }
    }

    // Deferred queries can also run inside an immediate facade's callback. That facade's newer
    // scope cannot establish cache eligibility for a query using the enlisted transaction's snapshot.
    private boolean deferredManagedTransaction() {
        if (deferredReads.isEmpty()) {
            return false;
        }
        for (DeferredReads reads : deferredReads) {
            try {
                Transaction transaction = reads.manager.getTransaction();
                if (transaction != null && reads.transactions.contains(transaction)) {
                    return true;
                }
            } catch (SystemException failure) {
                return true;
            }
        }
        return false;
    }

    Snapshot snapshot(Configuration configuration) {
        publication.readLock().lock();
        try {
            synchronized (this) {
                // Custom configurations may not implement the registration hooks. Discover registered caches
                // without inspecting or rewriting live statements. Statement-only caches are checked at use.
                Collection<Cache> registered = configuration.getCaches();
                if (registered.size() != cacheCount) {
                    for (Object candidate : registered) {
                        if (candidate instanceof Cache cache) {
                            registerCache(cache);
                        }
                    }
                    cacheCount = registered.size();
                }
                if (!configuration.isCacheEnabled()) {
                    return new Snapshot(this, generation.get(), inventory, false);
                }
                if (!evictionFailed) {
                    return new Snapshot(this, generation.get(), inventory, true);
                }
            }
        } finally {
            publication.readLock().unlock();
        }
        evictCaches();
        return snapshot(configuration);
    }

    synchronized void registerCache(Cache cache) {
        if (cache instanceof GuardedCache guard && guard.owner() == this) {
            cache = guard.delegate;
        }
        for (WeakReference<Cache> reference : inventory.caches) {
            if (reference.get() == cache) {
                publishGuards(cache);
                return;
            }
        }
        List<WeakReference<Cache>> updated = new ArrayList<>(inventory.caches);
        updated.removeIf(reference -> reference.get() == null);
        updated.add(new WeakReference<>(cache));
        inventory = new CacheInventory(List.copyOf(updated),
                cache instanceof BlockingCache ? cache.getId() : inventory.blockingCacheId);
        publishGuards(cache);
    }

    private void publishGuards(Cache cache) {
        for (WeakReference<GuardedCache> reference : guards) {
            GuardedCache guarded = reference.get();
            if (guarded != null && guarded.delegate == cache) {
                guarded.published = true;
            }
        }
    }

    MappedStatement prepareStatement(MappedStatement statement) {
        // A replacement may change result mappings while retaining the same SQL/cache key. Invalidate
        // before it is published, including entries held by older transactions. Keep only IDs here:
        // retaining statements would retain the weak registry's Configuration keys.
        boolean replacement;
        synchronized (this) {
            replacement = !statementIds.add(statement.getId());
        }
        if (replacement) {
            evictCaches();
        }
        GuardedCache guarded = null;
        MappedStatement prepared;
        publication.readLock().lock();
        try {
            synchronized (this) {
                Cache cache = statement.getCache();
                if (cache != null) {
                    registerCache(cache);
                    guarded = guard(cache);
                }
                prepared = cache == null ? statement : withCache(statement, guarded);
            }
            if (guarded != null) {
                guarded.clearIfFactoryInvalidationPending();
            }
        } finally {
            publication.readLock().unlock();
        }
        return prepared;
    }

    void publishCache(Cache cache, java.util.function.Consumer<Cache> publisher) {
        // Readers and completions use this monitor too: publication and inventory installation form
        // one boundary. A rejected registration must never install cache metadata (notably blocking).
        publication.readLock().lock();
        try {
            GuardedCache guarded;
            synchronized (this) {
                publisher.accept(cache);
                registerCache(cache);
                guarded = guard(cache);
            }
            guarded.clearIfFactoryInvalidationPending();
        } finally {
            publication.readLock().unlock();
        }
    }

    void publishStatement(MappedStatement statement, java.util.function.Consumer<MappedStatement> publisher) {
        boolean replacement;
        synchronized (this) {
            replacement = statementIds.contains(statement.getId());
        }
        if (replacement) {
            // Never undo an invalidation, even if publication fails: older readers remain obsolete.
            evictCaches();
        }
        publication.readLock().lock();
        try {
            GuardedCache acceptedCache = null;
            synchronized (this) {
                Cache cache = statement.getCache();
                MappedStatement prepared = cache == null ? statement : withCache(statement, guard(cache));
                publisher.accept(prepared);
                // MyBatis-Plus can silently ignore duplicate mappings. Only accepted mappings contribute caches.
                if (statement.getConfiguration().getMappedStatement(statement.getId(), false) == prepared) {
                    statementIds.add(statement.getId());
                    if (cache != null) {
                        registerCache(cache);
                        acceptedCache = guard(cache);
                    }
                }
            }
            if (acceptedCache != null) {
                acceptedCache.clearIfFactoryInvalidationPending();
            }
        } finally {
            publication.readLock().unlock();
        }
    }

    private GuardedCache guard(Cache cache) {
        if (cache instanceof GuardedCache guarded && guarded.owner() == this) {
            return guarded;
        }
        for (WeakReference<GuardedCache> reference : guards) {
            GuardedCache guarded = reference.get();
            if (guarded != null && guarded.delegate == cache) {
                return guarded;
            }
        }
        guards.removeIf(reference -> reference.get() == null);
        GuardedCache guarded = new GuardedCache(cache);
        guards.add(new WeakReference<>(guarded));
        return guarded;
    }

    static final class StatementAccess {
        boolean readOnly = true;
        boolean cacheable;
        boolean flushesCache;
        boolean nested;
        boolean lazy;
        boolean untrackedDependency;
        GuardedCache region;
    }

    StatementAccess validateStatement(Configuration configuration, String id) {
        StatementAccess access = new StatementAccess();
        if (configuration.isCacheEnabled()) {
            MappedStatement statement = configuration.getMappedStatement(id);
            boolean leaf = statement.getResultMaps().stream().noneMatch(result -> result.hasNestedQueries()
                    || result.hasNestedResultMaps() || result.getDiscriminator() != null);
            if (leaf && !configuration.hasStatement(id + "!selectKey", false)) {
                inspectStatement(statement, access);
            } else {
                validateStatement(configuration, id, new HashSet<>(), new HashSet<>(), access);
            }
        }
        return access;
    }

    private void validateStatement(Configuration configuration, String id, Set<String> statements, Set<String> resultMaps,
            StatementAccess access) {
        if (!statements.add(id)) {
            return;
        }
        MappedStatement statement = configuration.getMappedStatement(id);
        inspectStatement(statement, access);
        for (ResultMap resultMap : statement.getResultMaps()) {
            validateResultMap(configuration, resultMap, statements, resultMaps, access);
        }
        String selectKey = id + "!selectKey";
        if (configuration.hasStatement(selectKey, false)) {
            access.nested = true;
            validateStatement(configuration, selectKey, statements, resultMaps, access);
        }
    }

    private void inspectStatement(MappedStatement statement, StatementAccess access) {
        Cache cache = statement.getCache();
        if (cache != null && !(cache instanceof GuardedCache guarded && guarded.owner() == this)) {
            throw new IllegalStateException("Late cached statement '" + statement.getId()
                    + "' was registered without cache coordination. Use MyBatisConfiguration or invoke "
                    + "CacheRegistrationSupport.prepare before publishing the statement in a custom Configuration.");
        }
        access.readOnly &= statement.getSqlCommandType() == org.apache.ibatis.mapping.SqlCommandType.SELECT
                && !statement.isDirtySelect() && !statement.isFlushCacheRequired()
                && statement.getStatementType() != org.apache.ibatis.mapping.StatementType.CALLABLE;
        access.cacheable |= cache != null && statement.isUseCache();
        access.flushesCache |= statement.isFlushCacheRequired();
        if (cache instanceof GuardedCache guarded) {
            access.untrackedDependency |= access.region != null && access.region != guarded;
            access.region = guarded;
        } else {
            access.untrackedDependency = true;
        }
    }

    private void validateResultMap(Configuration configuration, ResultMap resultMap, Set<String> statements,
            Set<String> resultMaps, StatementAccess access) {
        if (!resultMaps.add(resultMap.getId())) {
            return;
        }
        for (ResultMapping mapping : resultMap.getResultMappings()) {
            if (mapping.getNestedQueryId() != null) {
                access.nested = true;
                access.lazy |= mapping.isLazy();
                validateStatement(configuration, mapping.getNestedQueryId(), statements, resultMaps, access);
            }
            if (mapping.getNestedResultMapId() != null) {
                validateResultMap(configuration, configuration.getResultMap(mapping.getNestedResultMapId()), statements,
                        resultMaps, access);
            }
        }
        if (resultMap.getDiscriminator() != null) {
            for (String id : resultMap.getDiscriminator().getDiscriminatorMap().values()) {
                if (configuration.hasResultMap(id)) {
                    validateResultMap(configuration, configuration.getResultMap(id), statements, resultMaps, access);
                }
            }
        }
    }

    /** Initialization only. Never called from a business invocation or for an already published configuration. */
    private void prepareCaches(Configuration configuration) {
        Collection<?> statements = configuration.getMappedStatements();
        Collection<Cache> registeredCaches = configuration.getCaches();
        Set<Cache> discovered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object candidate : registeredCaches) {
            if (candidate instanceof Cache cache) {
                discovered.add(cache);
            }
        }
        Map<MappedStatement, MappedStatement> replacements = new IdentityHashMap<>();
        // StrictMap also contains short-name aliases and ambiguity markers. Replace each actual statement once.
        for (Object candidate : statements) {
            if (candidate instanceof MappedStatement statement) {
                statementIds.add(statement.getId());
                if (statement.getCache() == null) {
                    continue;
                }
                Cache cache = statement.getCache();
                discovered.add(cache);
                GuardedCache guarded = guard(cache);
                replacements.computeIfAbsent(statement, ignored -> withCache(statement, guarded));
            }
        }
        if (!replacements.isEmpty()) {
            // Use the public configuration collection and builder; no reflective access to MyBatis internals.
            // This runs during construction, before application callers can resolve mapper methods.
            statements.removeIf(replacements::containsKey);
            replacements.values().forEach(configuration::addMappedStatement);
        }
        discovered.forEach(this::registerCache);
        cacheCount = registeredCaches.size();
    }

    private static MappedStatement withCache(MappedStatement statement, Cache cache) {
        return new MappedStatement.Builder(statement.getConfiguration(), statement.getId(), statement.getSqlSource(),
                statement.getSqlCommandType())
                .resource(statement.getResource()).parameterMap(statement.getParameterMap())
                .resultMaps(statement.getResultMaps()).fetchSize(statement.getFetchSize()).timeout(statement.getTimeout())
                .statementType(statement.getStatementType()).resultSetType(statement.getResultSetType()).cache(cache)
                .flushCacheRequired(statement.isFlushCacheRequired()).useCache(statement.isUseCache())
                .resultOrdered(statement.isResultOrdered()).keyGenerator(statement.getKeyGenerator())
                .keyProperty(join(statement.getKeyProperties())).keyColumn(join(statement.getKeyColumns()))
                .databaseId(statement.getDatabaseId()).lang(statement.getLang()).resultSets(join(statement.getResultSets()))
                .dirtySelect(statement.isDirtySelect()).build();
    }

    private static String join(String[] values) {
        return values == null ? null : String.join(",", values);
    }

    Scope enter(Snapshot snapshot) {
        if (snapshot.owner != this) {
            throw new IllegalArgumentException("Cache snapshot belongs to another configuration");
        }
        Scope scope = new Scope(current.get());
        current.set(snapshot);
        return scope;
    }

    Completion begin(Snapshot snapshot) {
        return new Completion(snapshot, enter(snapshot));
    }

    private boolean isCurrent(Snapshot snapshot) {
        return pendingFactoryInvalidations.get() == 0 && isVersionCurrent(snapshot)
                && (snapshot == null || (!snapshot.factoryWide || pendingRegionalInvalidations.get() == 0)
                        && (snapshot.region == null || snapshot.region.pendingInvalidations == 0));
    }

    private boolean isVersionCurrent(Snapshot snapshot) {
        return !evictionFailed && (snapshot == null || (!snapshot.discarded
                && (snapshot.region != null && !snapshot.factoryWide
                        ? snapshot.started >= factoryClear && snapshot.regionVersion >= snapshot.region.lastClear
                        : snapshot.generation == generation.get())));
    }

    private void beginFactoryInvalidation(Snapshot snapshot) {
        if (snapshot.factoryInvalidation) {
            return;
        }
        lockExclusivePublication();
        try {
            snapshot.factoryInvalidation = true;
            snapshot.factoryWide = true;
            pendingFactoryInvalidations.incrementAndGet();
            long previousFactoryClear = factoryClear;
            List<GuardedCache> regions = prepareFactoryEviction();
            snapshot.advanceFactoryVersion(previousFactoryClear, factoryClear);
            clearInventory(regions);
            // The writer's TCM can still contain reads made before an uncached write.
            // Keep its original generation so those entries cannot be published.
        } finally {
            publication.writeLock().unlock();
        }
    }

    private void lockExclusivePublication() {
        if (publication.getReadHoldCount() != 0 || publication.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Cache provider callbacks cannot recursively publish or invalidate caches");
        }
        publication.writeLock().lock();
    }

    /** Acquire the I/O barrier before the metadata monitor, and release the monitor before provider calls. */
    private void evictCaches() {
        lockExclusivePublication();
        try {
            clearInventory(prepareFactoryEviction());
        } finally {
            publication.writeLock().unlock();
        }
    }

    private List<GuardedCache> prepareFactoryEviction() {
        List<GuardedCache> regions = new ArrayList<>();
        synchronized (this) {
            evictionFailed = true;
            factoryClear = generation.incrementAndGet();
            for (WeakReference<Cache> reference : inventory.caches) {
                Cache cache = reference.get();
                if (cache != null) {
                    regions.add(guard(cache));
                }
            }
        }
        return regions;
    }

    private void clearInventory(List<GuardedCache> regions) {
        RuntimeException failure = null;
        for (GuardedCache region : regions) {
            try {
                synchronized (region) {
                    region.delegate.clear();
                }
            } catch (RuntimeException | Error cause) {
                if (failure == null) {
                    failure = new IllegalStateException("Could not invalidate MyBatis second-level caches", cause);
                } else {
                    failure.addSuppressed(cause);
                }
            }
        }
        evictionFailed = failure != null;
        if (failure != null) {
            throw failure;
        }
    }

    private final class GuardedCache implements Cache {
        private final Cache delegate;
        private volatile long lastClear;
        private volatile boolean published;
        private volatile int pendingInvalidations;

        private GuardedCache(Cache delegate) {
            this.delegate = delegate;
            published = inventory.caches.stream().anyMatch(reference -> reference.get() == delegate);
        }

        private void awaitPublication() {
            if (!published) {
                synchronized (owner()) {
                    if (!published) {
                        throw new IllegalStateException("Cache registration has not completed");
                    }
                }
            }
        }

        private SqlSessionCacheCoordinator owner() {
            return SqlSessionCacheCoordinator.this;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public Object getObject(Object key) {
            Snapshot snapshot = current.get();
            if ((snapshot == null || !snapshot.managed) && deferredManagedTransaction()) {
                return null;
            }
            if (delegate instanceof BlockingCache) {
                return readObject(key, snapshot);
            }
            publication.readLock().lock();
            try {
                return readObject(key, snapshot);
            } finally {
                publication.readLock().unlock();
            }
        }

        private Object readObject(Object key, Snapshot access) {
            awaitPublication();
            if (access != null) {
                access.cacheAccessed = true;
                access.touch(this);
                if (access.managed && delegate instanceof BlockingCache) {
                    throw new BlockingCacheNotSupportedException(delegate.getId());
                }
            }
            if (delegate instanceof BlockingCache) {
                // A cache-miss lock can wait for another session's publication. Never wait under our monitor.
                synchronized (this) {
                    if (!isCurrentAccess(access)) {
                        return null;
                    }
                }
                Object value = delegate.getObject(key);
                synchronized (this) {
                    if (value == null && access != null) {
                        access.recordBlockingMiss(this, key);
                    }
                    return isCurrentAccess(access) ? value : null;
                }
            }
            synchronized (this) {
                if (access != null && access.entries != null) {
                    ManagedCacheEntries entries = access.entries;
                    ManagedCacheEntries.Region state = entries.region(this);
                    state.candidates.remove(key);
                    if (entries.query != null) {
                        entries.query.lookup(this, state, key);
                    }
                    if (!eligible(entries, state) || pendingInvalidations != 0 || pendingFactoryInvalidations.get() != 0
                            || entries.query == null || !entries.query.matches(this)) {
                        return null;
                    }
                    // A provider may return a shared mutable object even for a cache hit.
                    List<?> rows = ManagedCacheEntries.copyRows(delegate.getObject(key));
                    if (rows != null && eligible(entries, state)) {
                        entries.query.hit();
                        return rows;
                    }
                    return null;
                }
                if (!isCurrentAccess(access)) {
                    return null;
                }
                Object value = delegate.getObject(key);
                return isCurrentAccess(access) ? value : null;
            }
        }

        @Override
        public void putObject(Object key, Object value) {
            awaitPublication();
            Snapshot snapshot = current.get();
            if ((snapshot == null || !snapshot.managed) && deferredManagedTransaction()) {
                return;
            }
            if (snapshot != null && snapshot.entries != null) {
                snapshot.cacheAccessed = true;
                ManagedCacheEntries.Region state = snapshot.entries.regions.get(this);
                if (snapshot.draining && state != null) {
                    ManagedCacheEntries.Candidate candidate = state.candidates.get(key);
                    if (candidate != null && !candidate.confirm(value)) {
                        state.candidates.remove(key);
                    }
                }
                return;
            }
            if (snapshot != null) {
                snapshot.touch(this);
                snapshot.cacheAccessed = true;
                if (snapshot.bypassNestedCache && !(delegate instanceof BlockingCache)) {
                    return;
                }
            }
            java.util.concurrent.locks.Lock gate = snapshot != null && (snapshot.factoryWide || snapshot.region == null)
                    ? publication.writeLock()
                    : publication.readLock();
            if (gate == publication.writeLock()) {
                lockExclusivePublication();
            } else {
                gate.lock();
            }
            try {
                synchronized (this) {
                    boolean ownsMiss = removeMiss(snapshot, key);
                    if (isCurrentAccess(snapshot)) {
                        delegate.putObject(key, value);
                    } else if (delegate instanceof BlockingCache && ownsMiss) {
                        delegate.removeObject(key);
                    }
                }
            } finally {
                gate.unlock();
            }
        }

        @Override
        public Object removeObject(Object key) {
            awaitPublication();
            Snapshot snapshot = current.get();
            if ((snapshot == null || !snapshot.managed) && deferredManagedTransaction()) {
                return null;
            }
            if (snapshot != null && (snapshot.managed || snapshot.bypassNestedCache) && !(delegate instanceof BlockingCache)) {
                // A managed transaction stages entries privately and takes no provider miss locks.
                // Rolling it back must not delete a value that another session has since published.
                return null;
            }
            publication.readLock().lock();
            try {
                synchronized (this) {
                    if (!(delegate instanceof BlockingCache) || removeMiss(current.get(), key)) {
                        return delegate.removeObject(key);
                    }
                    return null;
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        private boolean removeMiss(Snapshot snapshot, Object key) {
            if (snapshot == null) {
                return true;
            }
            Set<Object> misses = snapshot.blockingMisses.get(this);
            return misses != null && misses.remove(key);
        }

        private boolean isCurrentAccess(Snapshot snapshot) {
            return (snapshot == null || !snapshot.bypassNestedCache) && pendingInvalidations == 0 && isCurrent(snapshot);
        }

        private boolean eligible(ManagedCacheEntries entries, ManagedCacheEntries.Region state) {
            return !evictionFailed && !entries.connectionExposed && entries.factoryVersion >= factoryClear
                    && state.version >= lastClear;
        }

        private void publish(ManagedCacheEntries entries, ManagedCacheEntries.Region state) {
            publication.readLock().lock();
            try {
                synchronized (this) {
                    for (var entry : state.candidates.entrySet()) {
                        if (!eligible(entries, state) || pendingFactoryInvalidations.get() != 0 || pendingInvalidations != 0) {
                            return;
                        }
                        if (entry.getValue().confirmed) {
                            delegate.putObject(entry.getKey(), new ArrayList<>(entry.getValue().rows));
                        }
                    }
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        private void beginInvalidation(Snapshot snapshot) {
            publication.readLock().lock();
            try {
                synchronized (this) {
                    if (!snapshot.markInvalidated(this)) {
                        return;
                    }
                    pendingInvalidations++;
                    pendingRegionalInvalidations.incrementAndGet();
                    advanceVersion(snapshot);
                    try {
                        delegate.clear();
                    } catch (RuntimeException | Error failure) {
                        evictionFailed = true;
                        throw failure;
                    }
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        private void clearIfFactoryInvalidationPending() {
            synchronized (this) {
                if (pendingFactoryInvalidations.get() == 0) {
                    return;
                }
                try {
                    delegate.clear();
                } catch (RuntimeException | Error failure) {
                    evictionFailed = true;
                    throw failure;
                }
            }
        }

        private void releaseInvalidation(Snapshot snapshot) {
            publication.readLock().lock();
            try {
                synchronized (this) {
                    if (pendingInvalidations > 0) {
                        advanceVersion(snapshot);
                        pendingInvalidations--;
                        pendingRegionalInvalidations.decrementAndGet();
                    }
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        @Override
        public void clear() {
            awaitPublication();
            publication.readLock().lock();
            try {
                synchronized (this) {
                    Snapshot snapshot = current.get();
                    if (snapshot != null && snapshot.draining
                            && (snapshot.factoryInvalidation || snapshot.invalidatedRegions.contains(this))) {
                        // MyBatis is draining its transactional cache. The write already cleared this region.
                        return;
                    }
                    if (snapshot != null && snapshot.entries != null) {
                        snapshot.entries.region(this).invalidate();
                    }
                    boolean skipProviderClear = false;
                    if (snapshot != null) {
                        snapshot.cacheAccessed = true;
                        snapshot.touch(this);
                        skipProviderClear = snapshot.skipRepeatedClear(this);
                    }
                    advanceVersion(snapshot);
                    if (!skipProviderClear) {
                        try {
                            delegate.clear();
                        } catch (RuntimeException | Error failure) {
                            evictionFailed = true;
                            throw failure;
                        }
                    }
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        @Override
        public int getSize() {
            publication.readLock().lock();
            try {
                synchronized (this) {
                    return delegate.getSize();
                }
            } finally {
                publication.readLock().unlock();
            }
        }

        // Called under this region's monitor. Only a current owner may retain publication rights.
        private void advanceVersion(Snapshot snapshot) {
            boolean keepOwner = snapshot != null && isVersionCurrent(snapshot);
            long previousClear = lastClear;
            long previous = generation.getAndIncrement();
            lastClear = previous + 1;
            if (snapshot != null && snapshot.entries != null) {
                ManagedCacheEntries.Region state = snapshot.entries.region(this);
                if (state.version >= previousClear) {
                    state.version = lastClear;
                }
            }
            if (keepOwner) {
                snapshot.regionVersion = lastClear;
                if (snapshot.generation == previous) {
                    snapshot.generation = lastClear;
                }
            }
        }

        @Override
        public ReadWriteLock getReadWriteLock() {
            return delegate.getReadWriteLock();
        }
    }

    static final class CacheInventory {
        private final List<WeakReference<Cache>> caches;
        private final String blockingCacheId;

        private CacheInventory(List<WeakReference<Cache>> caches, String blockingCacheId) {
            this.caches = caches;
            this.blockingCacheId = blockingCacheId;
        }
    }

    static final class Snapshot {
        private final SqlSessionCacheCoordinator owner;
        private long generation;
        private final CacheInventory inventory;
        private final boolean cachingEnabled;
        private boolean managed;
        private boolean cacheAccessed;
        private boolean discarded;
        private final long started;
        private long regionVersion;
        private GuardedCache region;
        private boolean factoryWide;
        private boolean bypassNestedCache;
        private boolean factoryInvalidation;
        private boolean invalidationsReleased;
        private ManagedCacheEntries entries;
        private boolean draining;
        private Set<GuardedCache> invalidatedRegions = Set.of();
        private Set<GuardedCache> repeatedClears = Set.of();
        private Map<Cache, Set<Object>> blockingMisses = Map.of();

        void invocation(StatementAccess access) {
            // A fresh invocation version cannot validate the dependencies of an already cached parent.
            bypassNestedCache = access != null && access.nested && access.untrackedDependency;
            trackDependencies(access);
        }

        void trackDependencies(StatementAccess access) {
            if (access == null || access.nested) {
                factoryWide = true;
            } else if (access.region != null) {
                touch(access.region);
            }
        }

        void beforePotentialWrite(StatementAccess access, boolean connectionExposed) {
            if (!cachingEnabled || !connectionExposed && (access == null || access.readOnly)) {
                return;
            }
            if (invalidationsReleased) {
                throw new IllegalStateException("Cache invalidation fence has already completed");
            }
            if (connectionExposed || access == null || access.nested || access.region == null
                    || (entries == null && factoryWide)) {
                if (entries != null) {
                    entries.regions.values().forEach(ManagedCacheEntries.Region::invalidate);
                    entries.connectionExposed |= connectionExposed;
                }
                owner.beginFactoryInvalidation(this);
            } else {
                if (entries != null) {
                    entries.region(access.region).invalidate();
                }
                // Without a MyBatis TCM flush, entries read before this write are still pending.
                discarded |= !access.flushesCache;
                access.region.beginInvalidation(this);
            }
        }

        private boolean markInvalidated(GuardedCache cache) {
            if (invalidatedRegions.isEmpty()) {
                invalidatedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return invalidatedRegions.add(cache);
        }

        private boolean skipRepeatedClear(GuardedCache cache) {
            if (!factoryInvalidation && !invalidatedRegions.contains(cache)) {
                return false;
            }
            if (repeatedClears.isEmpty()) {
                repeatedClears = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return repeatedClears.add(cache);
        }

        void releaseInvalidations() {
            if (invalidationsReleased) {
                return;
            }
            if (!factoryInvalidation && invalidatedRegions.isEmpty()) {
                return;
            }
            invalidationsReleased = true;
            for (GuardedCache cache : invalidatedRegions) {
                cache.releaseInvalidation(this);
            }
            if (factoryInvalidation) {
                owner.lockExclusivePublication();
                try {
                    // Readers that started while this fence was held may have seen pre-commit rows.
                    long previous = owner.factoryClear;
                    owner.factoryClear = owner.generation.incrementAndGet();
                    advanceFactoryVersion(previous, owner.factoryClear);
                    owner.pendingFactoryInvalidations.decrementAndGet();
                } finally {
                    owner.publication.writeLock().unlock();
                }
            }
        }

        private void touch(GuardedCache cache) {
            if (region == null) {
                region = cache;
            } else if (region != cache) {
                factoryWide = true;
            }
        }

        private void advanceFactoryVersion(long previous, long next) {
            if (entries != null && entries.factoryVersion >= previous) {
                entries.factoryVersion = next;
            }
        }

        void manageEntries(Transaction transaction) {
            long transactionStart = owner.managedStarts.computeIfAbsent(transaction, ignored -> started);
            entries = new ManagedCacheEntries(transactionStart);
        }

        Scope drainEntries() {
            Scope scope = owner.enter(this);
            scope.drainingSnapshot = this;
            scope.previouslyDraining = draining;
            draining = true;
            return scope;
        }

        Scope deferredRead() {
            Scope scope = owner.enter(this);
            // Cursor rows and nested queries are not the outer facade invocation's return value.
            // Bind their owner explicitly and restore any enclosing query when iteration exits.
            scope.trackQuery(this, null, "selectCursor");
            return scope;
        }

        void publishEntries() {
            entries.regions.forEach((cache, state) -> ((GuardedCache) cache).publish(entries, state));
        }

        void releaseEntries() {
            // A closed cursor may retain its holder. Detach the whole private state;
            // an enclosing Scope can still restore a query on the old entries object.
            entries = null;
        }

        private void recordBlockingMiss(Cache cache, Object key) {
            if (blockingMisses.isEmpty()) {
                blockingMisses = new IdentityHashMap<>();
            }
            blockingMisses.computeIfAbsent(cache, ignored -> new HashSet<>()).add(key);
        }

        private Snapshot(SqlSessionCacheCoordinator owner, long generation, CacheInventory inventory, boolean cachingEnabled) {
            this.owner = owner;
            this.generation = generation;
            this.started = generation;
            this.regionVersion = generation;
            this.inventory = inventory;
            this.cachingEnabled = cachingEnabled;
        }

        boolean hasCaches() {
            // A transaction may have started before the factory's first cache was registered.
            return cachingEnabled && (!inventory.caches.isEmpty() || !owner.inventory.caches.isEmpty());
        }

        List<Cache> caches() {
            List<Cache> live = new ArrayList<>();
            if (cachingEnabled) {
                for (WeakReference<Cache> reference : owner.inventory.caches) {
                    Cache cache = reference.get();
                    if (cache != null) {
                        live.add(cache);
                    }
                }
            }
            return List.copyOf(live);
        }

        CacheInventory inventory() {
            return inventory;
        }

        void requireNonBlocking() {
            if (cachingEnabled && owner.inventory.blockingCacheId != null) {
                throw new BlockingCacheNotSupportedException(owner.inventory.blockingCacheId);
            }
            managed = true;
        }
    }

    final class Scope implements AutoCloseable {
        private final Snapshot previous;
        private ManagedCacheEntries entries;
        private ManagedCacheEntries.Query previousQuery;
        private Snapshot drainingSnapshot;
        private boolean previouslyDraining;

        private Scope(Snapshot previous) {
            this.previous = previous;
        }

        void trackQuery(Snapshot snapshot, StatementAccess access, String method) {
            entries = snapshot.entries;
            previousQuery = entries.query;
            boolean selectOne = method.equals("selectOne");
            entries.query = access != null && access.cacheable && !access.nested && (selectOne || method.equals("selectList"))
                    ? new ManagedCacheEntries.Query(access.region, selectOne)
                    : null;
        }

        void returned(Object result) {
            if (entries.query != null) {
                entries.query.returned(result);
            }
        }

        @Override
        public void close() {
            if (drainingSnapshot != null) {
                drainingSnapshot.draining = previouslyDraining;
            }
            if (entries != null) {
                entries.query = previousQuery;
            }
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    final class Completion implements AutoCloseable {
        private final Snapshot snapshot;
        private final Scope scope;
        private boolean successful;
        private boolean invalidate;
        private boolean closed;

        private Completion(Snapshot snapshot, Scope scope) {
            this.snapshot = snapshot;
            this.scope = scope;
        }

        boolean canPublish() {
            synchronized (SqlSessionCacheCoordinator.this) {
                return !snapshot.hasCaches() || isCurrent(snapshot);
            }
        }

        void invalidate() {
            // A factory fence has already emptied every region and expired overlapping readers.
            // A failed completion still retries eviction in close().
            invalidate = !snapshot.factoryInvalidation || evictionFailed;
        }

        void discard() {
            synchronized (SqlSessionCacheCoordinator.this) {
                snapshot.discarded = true;
                invalidate = true;
            }
        }

        void success() {
            successful = true;
        }

        void releaseInvalidations() {
            snapshot.releaseInvalidations();
        }

        void openingFailed(boolean noSqlPossible) {
            // Absence of cache access alone says nothing about JDBC work in a custom opener.
            if (noSqlPossible && !snapshot.cacheAccessed) {
                successful = true;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (snapshot.hasCaches() && (invalidate || !successful)) {
                    evictCaches();
                }
            } finally {
                try {
                    snapshot.releaseInvalidations();
                } finally {
                    scope.close();
                }
            }
        }
    }
}
