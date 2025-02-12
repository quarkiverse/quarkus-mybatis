package io.quarkiverse.mybatis.plus;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.mybatis-plus")
public interface MyBatisPlusConfig {

    /**
     * MyBatis-plus PaginationInnerInterceptor
     */
    @WithName("pagination.enabled")
    @WithDefault("true")
    boolean pageEnabled();

    /**
     * MyBatis-plus SqlInjector Class
     */
    @WithName("sql-injector")
    Optional<String> sqlInjector();

    /**
     * MyBatis-plus globalConfig metaObjectHandler
     */
    @WithName("meta-object-handler")
    Optional<String> metaObjectHandler();
}
