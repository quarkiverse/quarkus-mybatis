package io.quarkiverse.mybatis.deployment;

import io.quarkiverse.mybatis.runtime.XMLConfigDelegateBuilder;
import io.quarkus.builder.item.SimpleBuildItem;

public final class XMLConfigBuilderBuildItem extends SimpleBuildItem {
    private final XMLConfigDelegateBuilder builder;

    public XMLConfigBuilderBuildItem(XMLConfigDelegateBuilder builder) {
        this.builder = builder;
    }

    public XMLConfigDelegateBuilder getBuilder() {
        return builder;
    }
}
