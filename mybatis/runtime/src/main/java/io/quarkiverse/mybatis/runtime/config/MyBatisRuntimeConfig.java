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
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

@ConfigRoot(name = "mybatis", phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public class MyBatisRuntimeConfig {
    /**
     * Data sources config
     */
    @ConfigDocSection
    @ConfigDocMapKey("data-source-name")
    @ConfigItem(name = ConfigItem.PARENT)
    public Map<String, MyBatisDataSourceRuntimeConfig> dataSources;

    /**
     * Support XML Configuration
     */
    @ConfigItem
    public XmlConfig xmlconfig;

    @ConfigGroup
    public static class XmlConfig {
        /**
         * enable mybatis xml configuration
         */
        @ConfigItem(defaultValue = "false")
        public boolean enable;

        /**
         * xml configuration file
         */
        @ConfigItem(defaultValue = "mybatis-config.xml")
        public String path;
    }

    /**
     * MyBatis environment id
     */
    @ConfigItem(defaultValue = "quarkus")
    public String environment;

    /**
     * MyBatis transaction factory
     */
    @ConfigItem(defaultValue = "MANAGED")
    public String transactionFactory;

    /**
     * MyBatis data source
     */
    @ConfigItem(name = "datasource")
    public Optional<String> dataSource;

    /**
     * MyBatis initial sql
     */
    @ConfigItem(name = "initial-sql")
    public Optional<String> initialSql;

    /**
     * MyBatis cacheEnabled
     */
    @ConfigItem(defaultValue = "true")
    public boolean cacheEnabled;

    /**
     * MyBatis lazyLoadingEnabled
     */
    @ConfigItem(defaultValue = "false")
    public boolean lazyLoadingEnabled;

    /**
     * MyBatis aggressiveLazyLoading
     */
    @ConfigItem(defaultValue = "false")
    public boolean aggressiveLazyLoading;

    /**
     * MyBatis useColumnLabel
     */
    @ConfigItem(defaultValue = "true")
    public boolean useColumnLabel;

    /**
     * MyBatis useGeneratedKeys
     */
    @ConfigItem(defaultValue = "false")
    public boolean useGeneratedKeys;

    /**
     * MyBatis autoMappingBehavior
     */
    @ConfigItem(defaultValue = "PARTIAL")
    public AutoMappingBehavior autoMappingBehavior;

    /**
     * MyBatis autoMappingUnknownColumnBehavior
     */
    @ConfigItem(defaultValue = "NONE")
    public AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior;

    /**
     * MyBatis defaultExecutorType
     */
    @ConfigItem(defaultValue = "SIMPLE")
    public ExecutorType defaultExecutorType;

    /**
     * MyBatis defaultStatementTimeout
     */
    @ConfigItem
    public Optional<Integer> defaultStatementTimeout;

    /**
     * MyBatis defaultFetchSize
     */
    @ConfigItem
    public Optional<Integer> defaultFetchSize;

    /**
     * MyBatis defaultResultSetType
     */
    @ConfigItem
    public Optional<ResultSetType> defaultResultSetType;

    /**
     * MyBatis safeRowBoundsEnabled
     */
    @ConfigItem(defaultValue = "false")
    public boolean safeRowBoundsEnabled;

    /**
     * MyBatis safeResultHandlerEnabled
     */
    @ConfigItem(defaultValue = "true")
    public boolean safeResultHandlerEnabled;

    /**
     * MyBatis mapUnderscoreToCamelCase
     */
    @ConfigItem(defaultValue = "false")
    public boolean mapUnderscoreToCamelCase;

    /**
     * MyBatis multipleResultSetsEnabled
     */
    @ConfigItem(defaultValue = "true")
    public boolean multipleResultSetsEnabled;

    /**
     * MyBatis localCacheScope
     */
    @ConfigItem(defaultValue = "SESSION")
    public LocalCacheScope localCacheScope;

    /**
     * MyBatis jdbcTypeForNull
     */
    @ConfigItem(defaultValue = "OTHER")
    public JdbcType jdbcTypeForNull;

    /**
     * MyBatis lazyLoadTriggerMethods
     */
    @ConfigItem(defaultValue = "equals,clone,hashCode,toString")
    public Set<String> lazyLoadTriggerMethods;

    /**
     * MyBatis defaultScriptingLanguage
     */
    @ConfigItem(defaultValue = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver")
    public String defaultScriptingLanguage;

    /**
     * MyBatis defaultEnumTypeHandler
     */
    @ConfigItem(defaultValue = "org.apache.ibatis.type.EnumTypeHandler")
    public String defaultEnumTypeHandler;

    /**
     * MyBatis callSettersOnNulls
     */
    @ConfigItem(defaultValue = "false")
    public boolean callSettersOnNulls;

    /**
     * MyBatis returnInstanceForEmptyRow
     */
    @ConfigItem(defaultValue = "false")
    public boolean returnInstanceForEmptyRow;

    /**
     * MyBatis logPrefix
     */
    @ConfigItem
    public Optional<String> logPrefix;

    /**
     * MyBatis logImpl
     */
    @ConfigItem
    public Optional<String> logImpl;

    /**
     * MyBatis proxyFactory
     */
    @ConfigItem(defaultValue = "JAVASSIST")
    public String proxyFactory;

    /**
     * MyBatis vfsImpl
     */
    @ConfigItem
    public Optional<String> vfsImpl;

    /**
     * MyBatis useActualParamName
     */
    @ConfigItem(defaultValue = "true")
    public boolean useActualParamName;

    /**
     * MyBatis configurationFactory
     */
    @ConfigItem
    public Optional<String> configurationFactory;

    /**
     * MyBatis shrinkWhitespacesInSql
     */
    @ConfigItem(defaultValue = "false")
    public boolean shrinkWhitespacesInSql;

    /**
     * MyBatis defaultSqlProviderType
     */
    @ConfigItem
    public Optional<String> defaultSqlProviderType;

    /**
     * MyBatis mapperLocations
     */
    @ConfigItem
    public Optional<List<String>> mapperLocations;
}
