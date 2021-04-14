package io.quarkiverse.mybatis.deployment;

import org.apache.ibatis.session.SqlSessionFactory;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.RuntimeValue;

/**
 * Hold the RuntimeValue of {@link SqlSessionFactory}
 */
public final class SqlSessionFactoryBuildItem extends MultiBuildItem {
    private final RuntimeValue<SqlSessionFactory> sqlSessionFactory;
    private final String dataSourceName;
    private final boolean defaultDataSource;

    public SqlSessionFactoryBuildItem(
            RuntimeValue<SqlSessionFactory> sqlSessionFactory,
            String dataSourceName,
            boolean isDefaultDataSource) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.dataSourceName = dataSourceName;
        this.defaultDataSource = isDefaultDataSource;
    }

    public RuntimeValue<SqlSessionFactory> getSqlSessionFactory() {
        return sqlSessionFactory;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public boolean isDefaultDataSource() {
        return defaultDataSource;
    }
}
