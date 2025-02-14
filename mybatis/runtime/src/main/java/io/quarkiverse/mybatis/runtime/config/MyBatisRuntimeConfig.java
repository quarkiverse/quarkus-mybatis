package io.quarkiverse.mybatis.runtime.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.type.JdbcType;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.smallrye.config.WithParentName;

@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.mybatis")
public interface MyBatisRuntimeConfig {
    /**
     * Data sources config
     */
    @ConfigDocSection
    @ConfigDocMapKey("data-source-name")
    @WithParentName
    Map<String, MyBatisDataSourceRuntimeConfig> dataSources();

    /**
     * Support XML Configuration
     */
    XmlConfig xmlconfig();

    @ConfigGroup
    public interface XmlConfig {
        /**
         * enable mybatis xml configuration
         */
        @WithDefault("false")
        boolean enable();

        /**
         * xml configuration file
         */
        @WithDefault("mybatis-config.xml")
        String path();
    }

    /**
     * MyBatis environment id
     */
    @WithDefault("quarkus")
    String environment();

    /**
     * MyBatis transaction factory
     */
    @WithDefault("MANAGED")
    String transactionFactory();

    /**
     * MyBatis data source
     */
    @WithName("datasource")
    Optional<String> dataSource();

    /**
     * MyBatis DatabaseId
     */
    Optional<String> databaseId();

    /**
     * MyBatis initial sql
     */
    @WithName("initial-sql")
    Optional<String> initialSql();

    /**
     * MyBatis cacheEnabled
     */
    @WithDefault("true")
    boolean cacheEnabled();

    /**
     * MyBatis lazyLoadingEnabled
     */
    @WithDefault("false")
    boolean lazyLoadingEnabled();

    /**
     * MyBatis aggressiveLazyLoading
     */
    @WithDefault("false")
    boolean aggressiveLazyLoading();

    /**
     * MyBatis useColumnLabel
     */
    @WithDefault("true")
    boolean useColumnLabel();

    /**
     * MyBatis useGeneratedKeys
     */
    @WithDefault("false")
    boolean useGeneratedKeys();

    /**
     * MyBatis autoMappingBehavior
     */
    @WithDefault("PARTIAL")
    AutoMappingBehavior autoMappingBehavior();

    /**
     * MyBatis autoMappingUnknownColumnBehavior
     */
    @WithDefault("NONE")
    AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior();

    /**
     * MyBatis defaultExecutorType
     */
    @WithDefault("SIMPLE")
    ExecutorType defaultExecutorType();

    /**
     * MyBatis defaultStatementTimeout
     */
    Optional<Integer> defaultStatementTimeout();

    /**
     * MyBatis defaultFetchSize
     */
    Optional<Integer> defaultFetchSize();

    /**
     * MyBatis defaultResultSetType
     */
    Optional<ResultSetType> defaultResultSetType();

    /**
     * MyBatis safeRowBoundsEnabled
     */
    @WithDefault("false")
    boolean safeRowBoundsEnabled();

    /**
     * MyBatis safeResultHandlerEnabled
     */
    @WithDefault("true")
    boolean safeResultHandlerEnabled();

    /**
     * MyBatis mapUnderscoreToCamelCase
     */
    @WithDefault("false")
    boolean mapUnderscoreToCamelCase();

    /**
     * MyBatis multipleResultSetsEnabled
     */
    @WithDefault("true")
    boolean multipleResultSetsEnabled();

    /**
     * MyBatis localCacheScope
     */
    @WithDefault("SESSION")
    LocalCacheScope localCacheScope();

    /**
     * MyBatis jdbcTypeForNull
     */
    @WithDefault("OTHER")
    JdbcType jdbcTypeForNull();

    /**
     * MyBatis lazyLoadTriggerMethods
     */
    @WithDefault("equals,clone,hashCode,toString")
    Set<String> lazyLoadTriggerMethods();

    /**
     * MyBatis defaultScriptingLanguage
     */
    @WithDefault("org.apache.ibatis.scripting.xmltags.XMLLanguageDriver")
    String defaultScriptingLanguage();

    /**
     * MyBatis defaultEnumTypeHandler
     */
    @WithDefault("org.apache.ibatis.type.EnumTypeHandler")
    String defaultEnumTypeHandler();

    /**
     * MyBatis callSettersOnNulls
     */
    @WithDefault("false")
    boolean callSettersOnNulls();

    /**
     * MyBatis returnInstanceForEmptyRow
     */
    @WithDefault("false")
    boolean returnInstanceForEmptyRow();

    /**
     * MyBatis logPrefix
     */
    Optional<String> logPrefix();

    /**
     * MyBatis logImpl
     */
    Optional<String> logImpl();

    /**
     * MyBatis proxyFactory
     */
    @WithDefault("JAVASSIST")
    String proxyFactory();

    /**
     * MyBatis vfsImpl
     */
    Optional<String> vfsImpl();

    /**
     * MyBatis useActualParamName
     */
    @WithDefault("true")
    boolean useActualParamName();

    /**
     * MyBatis configurationFactory
     */
    Optional<String> configurationFactory();

    /**
     * MyBatis shrinkWhitespacesInSql
     */
    @WithDefault("false")
    public boolean shrinkWhitespacesInSql();

    /**
     * MyBatis defaultSqlProviderType
     */
    Optional<String> defaultSqlProviderType();

    /**
     * MyBatis mapperLocations
     */
    Optional<List<String>> mapperLocations();
}
