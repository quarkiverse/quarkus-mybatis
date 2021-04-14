package io.quarkiverse.mybatis.deployment;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;

public final class MyBatisMapperBuildItem extends MultiBuildItem {
    private final DotName mapperName;
    private final String dataSourceName;

    public MyBatisMapperBuildItem(DotName mapperName, String dataSourceName) {
        this.mapperName = mapperName;
        this.dataSourceName = dataSourceName;
    }

    public DotName getMapperName() {
        return mapperName;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }
}
