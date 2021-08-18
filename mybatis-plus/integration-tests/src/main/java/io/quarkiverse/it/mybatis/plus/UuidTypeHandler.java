package io.quarkiverse.it.mybatis.plus;

import java.sql.*;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    public static UUID fromStringAllowNulls(String uuid) {
        if (uuid == null || uuid.trim().equals("")) {
            return null;
        } else {
            return UUID.fromString(uuid);
        }
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        // note the difference in how H2 wants to persist a UUID
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromStringAllowNulls(rs.getString(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromStringAllowNulls(rs.getString(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromStringAllowNulls(cs.getString(columnIndex));
    }

}
