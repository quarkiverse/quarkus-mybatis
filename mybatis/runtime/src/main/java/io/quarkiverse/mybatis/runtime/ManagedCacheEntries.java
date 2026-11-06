package io.quarkiverse.mybatis.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/** Private to one managed session. JDBC completion and shared-cache I/O belong to the coordinator. */
final class ManagedCacheEntries {
    final Map<Cache, Region> regions = new IdentityHashMap<>();
    final long started;
    long factoryVersion;
    boolean connectionExposed;
    Query query;

    ManagedCacheEntries(long started) {
        this.started = started;
        factoryVersion = started;
    }

    Region region(Cache cache) {
        return regions.computeIfAbsent(cache, ignored -> new Region(started));
    }

    static final class Region {
        long version;
        Map<Object, Candidate> candidates = new HashMap<>();

        Region(long version) {
            this.version = version;
        }

        void invalidate() {
            // An in-flight query retains the previous map and cannot resurrect an invalidated entry.
            candidates = new HashMap<>();
        }
    }

    static final class Query {
        private final Cache cache;
        private final boolean single;
        private Map<Object, Candidate> candidates;
        private Object key;
        private boolean lookedUp;

        Query(Cache cache, boolean single) {
            this.cache = cache;
            this.single = single;
        }

        boolean matches(Cache actual) {
            return cache == actual;
        }

        void lookup(Cache actual, Region region, Object key) {
            // Multiple lookups (e.g. a plugin issuing another query) have no unambiguous result.
            candidates = !lookedUp && cache == actual ? region.candidates : null;
            lookedUp = true;
            this.key = key;
        }

        void returned(Object result) {
            if (candidates == null) {
                return;
            }
            if (single && immutableScalar(result)) {
                candidates.put(key, new Candidate(null, Collections.singletonList(result)));
            } else if (!single) {
                List<?> rows = copyRows(result);
                if (rows != null) {
                    candidates.put(key, new Candidate(result, rows));
                }
            }
        }

        void hit() {
            candidates = null;
        }
    }

    static final class Candidate {
        private Object source;
        List<?> rows;
        boolean confirmed;

        Candidate(Object source, List<?> rows) {
            this.source = source;
            this.rows = rows;
        }

        boolean confirm(Object value) {
            if (confirmed) {
                return true;
            }
            // Only MyBatis knows whether selectOne(null) came from no rows or one NULL row.
            if (source != null) {
                confirmed = source == value;
            } else if (value instanceof ArrayList<?> actual && actual.getClass() == ArrayList.class) {
                Object scalar = rows.get(0);
                confirmed = actual.size() == 1 && actual.get(0) == scalar || actual.isEmpty() && scalar == null;
                if (confirmed && actual.isEmpty()) {
                    rows = List.of();
                }
            }
            source = null;
            return confirmed;
        }
    }

    static List<?> copyRows(Object result) {
        if (plainScalarRows(result)) {
            return new ArrayList<>((List<?>) result);
        }
        return null;
    }

    static boolean plainScalarRows(Object value) {
        return value instanceof ArrayList<?> rows && rows.getClass() == ArrayList.class
                && rows.stream().allMatch(ManagedCacheEntries::immutableScalar);
    }

    static boolean immutableScalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double;
    }
}
