package io.quarkiverse.mybatis.deployment;

import org.apache.ibatis.session.SqlSessionManager;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.RuntimeValue;

/**
 * Hold the RuntimeValue of {@link SqlSessionManager}
 */
public final class SqlSessionManagerBuildItem extends MultiBuildItem {
    private final RuntimeValue<SqlSessionManager> sqlSessionManager;
    private final String dataSourceName;
    private final boolean defaultDataSource;

    public SqlSessionManagerBuildItem(
            RuntimeValue<SqlSessionManager> sqlSessionManager,
            String dataSourceName,
            Boolean defaultDataSource) {
        this.sqlSessionManager = sqlSessionManager;
        this.dataSourceName = dataSourceName;
        this.defaultDataSource = defaultDataSource;
    }

    public RuntimeValue<SqlSessionManager> getSqlSessionManager() {
        return sqlSessionManager;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public boolean isDefaultDataSource() {
        return defaultDataSource;
    }
}
