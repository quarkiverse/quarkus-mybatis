package io.quarkiverse.mybatis.runtime;

import java.util.function.Consumer;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;

/**
 * Registration hooks for custom configurations. Pass the superclass registration as the publisher.
 * No runtime coordinator is created during
 * build-time configuration. Existing factories are coordinated only after their facade is constructed.
 */
public final class CacheRegistrationSupport {
    private CacheRegistrationSupport() {
    }

    public static MappedStatement prepare(MappedStatement statement) {
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.existing(statement.getConfiguration());
        return coordinator == null ? statement : coordinator.prepareStatement(statement);
    }

    public static void addMappedStatement(MappedStatement statement, Consumer<MappedStatement> publisher) {
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.existing(statement.getConfiguration());
        if (coordinator == null) {
            publisher.accept(statement);
        } else {
            coordinator.publishStatement(statement, publisher);
        }
    }

    public static void addCache(Configuration configuration, Cache cache, Consumer<Cache> publisher) {
        SqlSessionCacheCoordinator coordinator = SqlSessionCacheCoordinator.existing(configuration);
        if (coordinator == null) {
            publisher.accept(cache);
        } else {
            coordinator.publishCache(cache, publisher);
        }
    }
}
