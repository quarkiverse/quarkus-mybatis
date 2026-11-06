package io.quarkiverse.mybatis.runtime;

/** Raised before acquiring a blocking cache-miss lock in a managed JTA session. */
public final class BlockingCacheNotSupportedException extends IllegalStateException {
    public BlockingCacheNotSupportedException(String cacheId) {
        super("Blocking second-level cache '" + cacheId
                + "' is not supported by TransactionalSqlSession with MANAGED JTA transactions. "
                + "Set blocking=false on the cache, or disable second-level caching for this factory.");
    }
}
