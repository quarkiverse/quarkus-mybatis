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
     * Plz use MyBatis-plus globalConfig sqlInjector
     */
    @Deprecated
    @WithName("sql-injector")
    Optional<String> sqlInjector();

    /**
     * MyBatis-plus globalConfig metaObjectHandler
     * Plz use MyBatis-plus globalConfig metaObjectHandler
     */
    @Deprecated
    @WithName("meta-object-handler")
    Optional<String> metaObjectHandler();

    /**
     * MyBatis-plus globalConfig
     */
    @WithName("global-config")
    GlobalConfig globalConfig();

    interface GlobalConfig {
        /***
         * MyBatis-plus globalConfig sqlInjector
         */
        @WithName("sql-injector")
        Optional<String> sqlInjector();

        /**
         * MyBatis-plus globalConfig metaObjectHandler
         */
        @WithName("meta-object-handler")
        Optional<String> metaObjectHandler();

        /**
         * MyBatis-plus globalConfig identifierGenerator
         */
        @WithName("identifier-generator")
        Optional<String> identifierGenerator();

        /**
         * MyBatis-plus globalConfig dbConfig
         */
        @WithName("db-config")
        Optional<DbConfig> dbConfig();
    }

    interface DbConfig {
        /**
         * MyBatis-plus globalConfig dbConfig idType
         */
        @WithName("id-type")
        @WithDefault("ASSIGN_ID")
        Optional<String> idType();

        /**
         * MyBatis-plus globalConfig dbConfig tablePrefix
         */
        @WithName("table-prefix")
        Optional<String> tablePrefix();

        /**
         * MyBatis-plus globalConfig dbConfig schema
         */
        @WithName("schema")
        Optional<String> schema();

        /**
         * MyBatis-plus globalConfig dbConfig columnFormat
         */
        @WithName("column-format")
        Optional<String> columnFormat();

        /**
         * MyBatis-plus globalConfig dbConfig tableFormat
         */
        @WithName("table-format")
        Optional<String> tableFormat();

        /**
         * MyBatis-plus globalConfig dbConfig propertyFormat
         */
        @WithName("property-format")
        Optional<String> propertyFormat();

        /**
         * MyBatis-plus globalConfig dbConfig tableUnderline
         */
        @WithName("table-underline")
        @WithDefault("true")
        boolean tableUnderline();

        /**
         * MyBatis-plus globalConfig dbConfig capitalMode
         */
        @WithName("capital-mode")
        @WithDefault("false")
        boolean capitalMode();

        /**
         * MyBatis-plus globalConfig dbConfig keyGenerator
         */
        @WithName("key-generator")
        Optional<String> keyGenerator();

        /**
         * MyBatis-plus globalConfig dbConfig logicDeleteField
         */
        @WithName("logic-delete-field")
        Optional<String> logicDeleteField();

        /**
         * MyBatis-plus globalConfig dbConfig logicDeleteValue
         */
        @WithName("logic-delete-value")
        @WithDefault("1")
        String logicDeleteValue();

        /**
         * MyBatis-plus globalConfig dbConfig logicNotDeleteValue
         */
        @WithName("logic-not-delete-value")
        @WithDefault("0")
        String logicNotDeleteValue();

        /**
         * MyBatis-plus globalConfig dbConfig insertStrategy
         */
        @WithName("insert-strategy")
        @WithDefault("NOT_NULL")
        String insertStrategy();

        /**
         * MyBatis-plus globalConfig dbConfig updateStrategy
         */
        @WithName("update-strategy")
        @WithDefault("NOT_NULL")
        String updateStrategy();

        /**
         * MyBatis-plus globalConfig dbConfig whereStrategy
         */
        @WithName("where-strategy")
        @WithDefault("NOT_NULL")
        String whereStrategy();
    }
}
