package io.quarkiverse.mybatis.deployment;

import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.RuntimeValue;

/**
 * Hold the RuntimeValue of {@link TransactionalSqlSession}
 */
public final class SqlSessionManagerBuildItem extends MultiBuildItem {
    private final RuntimeValue<TransactionalSqlSession> sqlSessionManager;
    private final String dataSourceName;
    private final boolean defaultDataSource;

    public SqlSessionManagerBuildItem(
            RuntimeValue<TransactionalSqlSession> sqlSessionManager,
            String dataSourceName,
            Boolean defaultDataSource) {
        this.sqlSessionManager = sqlSessionManager;
        this.dataSourceName = dataSourceName;
        this.defaultDataSource = defaultDataSource;
    }

    public RuntimeValue<TransactionalSqlSession> getSqlSessionManager() {
        return sqlSessionManager;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public boolean isDefaultDataSource() {
        return defaultDataSource;
    }
}
