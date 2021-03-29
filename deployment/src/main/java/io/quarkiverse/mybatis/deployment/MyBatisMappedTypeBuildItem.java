package io.quarkiverse.mybatis.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;

public final class MyBatisMappedTypeBuildItem extends MultiBuildItem {
    private final DotName mappedTypeName;

    public MyBatisMappedTypeBuildItem(DotName mappedTypeName) {
        this.mappedTypeName = mappedTypeName;
    }

    public DotName getMappedTypeName() {
        return mappedTypeName;
    }
}
