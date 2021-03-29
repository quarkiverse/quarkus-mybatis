package io.quarkiverse.mybatis.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;

public final class MyBatisMappedJdbcTypeBuildItem extends MultiBuildItem {
    private final DotName mappedJdbcTypeName;

    public MyBatisMappedJdbcTypeBuildItem(DotName mappedJdbcTypeName) {
        this.mappedJdbcTypeName = mappedJdbcTypeName;
    }

    public DotName getMappedJdbcTypeName() {
        return mappedJdbcTypeName;
    }
}
