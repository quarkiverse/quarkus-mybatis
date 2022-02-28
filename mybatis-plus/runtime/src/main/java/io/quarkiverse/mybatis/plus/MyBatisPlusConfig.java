package io.quarkiverse.mybatis.plus;

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
}
