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
import io.smallrye.config.WithName;

@ConfigGroup
public interface MyBatisDataSourceRuntimeConfig {
    /**
     * MyBatis environment id
     */
    Optional<String> environment();

    /**
     * MyBatis transaction factory
     */
    Optional<String> transactionFactory();

    /**
     * MyBatis databaseId
     */
    Optional<String> databaseId();

    /**
     * MyBatis initial sql files (comma-separated)
     */
    @WithName("initial-sql")
    Optional<String> initialSql();

    /**
     * MyBatis cacheEnabled
     */
    Optional<Boolean> cacheEnabled();

    /**
     * MyBatis lazyLoadingEnabled
     */
    Optional<Boolean> lazyLoadingEnabled();

    /**
     * MyBatis aggressiveLazyLoading
     */
    Optional<Boolean> aggressiveLazyLoading();

    /**
     * MyBatis useColumnLabel
     */
    Optional<Boolean> useColumnLabel();

    /**
     * MyBatis useGeneratedKeys
     */
    Optional<Boolean> useGeneratedKeys();

    /**
     * MyBatis autoMappingBehavior
     */
    Optional<AutoMappingBehavior> autoMappingBehavior();

    /**
     * MyBatis autoMappingUnknownColumnBehavior
     */
    Optional<AutoMappingUnknownColumnBehavior> autoMappingUnknownColumnBehavior();

    /**
     * MyBatis argNameBasedConstructorAutoMapping
     */
    Optional<Boolean> argNameBasedConstructorAutoMapping();

    /**
     * MyBatis defaultExecutorType
     */
    Optional<ExecutorType> defaultExecutorType();

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
    Optional<Boolean> safeRowBoundsEnabled();

    /**
     * MyBatis safeResultHandlerEnabled
     */
    Optional<Boolean> safeResultHandlerEnabled();

    /**
     * MyBatis mapUnderscoreToCamelCase
     */
    Optional<Boolean> mapUnderscoreToCamelCase();

    /**
     * MyBatis multipleResultSetsEnabled
     */
    Optional<Boolean> multipleResultSetsEnabled();

    /**
     * MyBatis localCacheScope
     */
    Optional<LocalCacheScope> localCacheScope();

    /**
     * MyBatis jdbcTypeForNull
     */
    Optional<JdbcType> jdbcTypeForNull();

    /**
     * MyBatis lazyLoadTriggerMethods
     */
    Optional<Set<String>> lazyLoadTriggerMethods();

    /**
     * MyBatis defaultScriptingLanguage
     */
    Optional<String> defaultScriptingLanguage();

    /**
     * MyBatis defaultEnumTypeHandler
     */
    Optional<String> defaultEnumTypeHandler();

    /**
     * MyBatis callSettersOnNulls
     */
    Optional<Boolean> callSettersOnNulls();

    /**
     * MyBatis returnInstanceForEmptyRow
     */
    Optional<Boolean> returnInstanceForEmptyRow();

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
    Optional<String> proxyFactory();

    /**
     * MyBatis vfsImpl
     */
    Optional<String> vfsImpl();

    /**
     * MyBatis useActualParamName
     */
    Optional<Boolean> useActualParamName();

    /**
     * MyBatis configurationFactory
     */
    Optional<String> configurationFactory();

    /**
     * MyBatis shrinkWhitespacesInSql
     */
    Optional<Boolean> shrinkWhitespacesInSql();

    /**
     * MyBatis defaultSqlProviderType
     */
    Optional<String> defaultSqlProviderType();
}
