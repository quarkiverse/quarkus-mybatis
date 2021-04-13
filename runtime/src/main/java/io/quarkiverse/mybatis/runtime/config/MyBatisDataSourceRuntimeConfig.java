package io.quarkiverse.mybatis.runtime.config;

import java.util.Optional;
import java.util.Set;

import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.type.JdbcType;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;

@ConfigGroup
public class MyBatisDataSourceRuntimeConfig {
    /**
     * MyBatis environment id
     */
    @ConfigItem
    public Optional<String> environment;

    /**
     * MyBatis transaction factory
     */
    @ConfigItem
    public Optional<String> transactionFactory;

    /**
     * MyBatis initial sql
     */
    @ConfigItem(name = "initial-sql")
    public Optional<String> initialSql;

    /**
     * MyBatis cacheEnabled
     */
    @ConfigItem
    public Optional<Boolean> cacheEnabled;

    /**
     * MyBatis lazyLoadingEnabled
     */
    @ConfigItem
    public Optional<Boolean> lazyLoadingEnabled;

    /**
     * MyBatis aggressiveLazyLoading
     */
    @ConfigItem
    public Optional<Boolean> aggressiveLazyLoading;

    /**
     * MyBatis useColumnLabel
     */
    @ConfigItem
    public Optional<Boolean> useColumnLabel;

    /**
     * MyBatis useGeneratedKeys
     */
    @ConfigItem
    public Optional<Boolean> useGeneratedKeys;

    /**
     * MyBatis autoMappingBehavior
     */
    @ConfigItem
    public Optional<AutoMappingBehavior> autoMappingBehavior;

    /**
     * MyBatis autoMappingUnknownColumnBehavior
     */
    @ConfigItem
    public Optional<AutoMappingUnknownColumnBehavior> autoMappingUnknownColumnBehavior;

    /**
     * MyBatis defaultExecutorType
     */
    @ConfigItem
    public Optional<ExecutorType> defaultExecutorType;

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
    @ConfigItem
    public Optional<Boolean> safeRowBoundsEnabled;

    /**
     * MyBatis safeResultHandlerEnabled
     */
    @ConfigItem
    public Optional<Boolean> safeResultHandlerEnabled;

    /**
     * MyBatis mapUnderscoreToCamelCase
     */
    @ConfigItem
    public Optional<Boolean> mapUnderscoreToCamelCase;

    /**
     * MyBatis multipleResultSetsEnabled
     */
    @ConfigItem
    public Optional<Boolean> multipleResultSetsEnabled;

    /**
     * MyBatis localCacheScope
     */
    @ConfigItem
    public Optional<LocalCacheScope> localCacheScope;

    /**
     * MyBatis jdbcTypeForNull
     */
    @ConfigItem
    public Optional<JdbcType> jdbcTypeForNull;

    /**
     * MyBatis lazyLoadTriggerMethods
     */
    @ConfigItem
    public Optional<Set<String>> lazyLoadTriggerMethods;

    /**
     * MyBatis defaultScriptingLanguage
     */
    @ConfigItem
    public Optional<String> defaultScriptingLanguage;

    /**
     * MyBatis defaultEnumTypeHandler
     */
    @ConfigItem
    public Optional<String> defaultEnumTypeHandler;

    /**
     * MyBatis callSettersOnNulls
     */
    @ConfigItem
    public Optional<Boolean> callSettersOnNulls;

    /**
     * MyBatis returnInstanceForEmptyRow
     */
    @ConfigItem
    public Optional<Boolean> returnInstanceForEmptyRow;

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
    @ConfigItem
    public Optional<String> proxyFactory;

    /**
     * MyBatis vfsImpl
     */
    @ConfigItem
    public Optional<String> vfsImpl;

    /**
     * MyBatis useActualParamName
     */
    @ConfigItem
    public Optional<Boolean> useActualParamName;

    /**
     * MyBatis configurationFactory
     */
    @ConfigItem
    public Optional<String> configurationFactory;

    /**
     * MyBatis shrinkWhitespacesInSql
     */
    @ConfigItem
    public Optional<Boolean> shrinkWhitespacesInSql;

    /**
     * MyBatis defaultSqlProviderType
     */
    @ConfigItem
    public Optional<String> defaultSqlProviderType;
}
