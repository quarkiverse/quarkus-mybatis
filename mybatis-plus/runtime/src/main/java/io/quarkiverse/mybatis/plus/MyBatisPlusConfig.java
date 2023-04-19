package io.quarkiverse.mybatis.plus;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

@ConfigRoot(name = "mybatis-plus", phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public class MyBatisPlusConfig {

    /**
     * MyBatis-plus PaginationInnerInterceptor
     */
    @ConfigItem(name = "pagination.enabled", defaultValue = "true")
    public boolean pageEnabled;

    /**
     * MyBatis-plus SqlInjector Class
     */
    @ConfigItem(name = "sql-injector")
    public Optional<String> sqlInjector;

    /**
     * MyBatis-plus globalConfig metaObjectHandler
     */
    @ConfigItem(name = "meta-object-handler")
    public Optional<String> metaObjectHandler;
}
