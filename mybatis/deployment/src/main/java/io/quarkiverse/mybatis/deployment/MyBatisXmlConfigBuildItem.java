package io.quarkiverse.mybatis.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

public final class MyBatisXmlConfigBuildItem extends SimpleBuildItem {
    private final boolean enabled;
    private final String name;

    public MyBatisXmlConfigBuildItem(String name, boolean enabled) {
        this.name = name;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getName() {
        return name;
    }
}
