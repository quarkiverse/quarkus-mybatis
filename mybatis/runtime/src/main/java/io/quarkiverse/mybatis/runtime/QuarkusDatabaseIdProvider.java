package io.quarkiverse.mybatis.runtime;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.eclipse.microprofile.config.ConfigProvider;

public class QuarkusDatabaseIdProvider implements DatabaseIdProvider {
    @Override
    public String getDatabaseId(final DataSource dataSource) throws SQLException {
        if (dataSource instanceof QuarkusDataSource ds) {
            String name = ds.getDataSourceName();
            return ConfigProvider.getConfig().getOptionalValue(
                    "quarkus.mybatis." + ("<default>".equals(name) ? "" : name + ".") + "database-id", String.class)
                    .orElse(null);
        }
        return null;
    }
}
