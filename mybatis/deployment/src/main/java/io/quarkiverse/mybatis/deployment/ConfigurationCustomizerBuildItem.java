package io.quarkiverse.mybatis.deployment;

import java.util.function.Consumer;

import org.apache.ibatis.session.Configuration;

import io.quarkus.builder.item.MultiBuildItem;

public final class ConfigurationCustomizerBuildItem extends MultiBuildItem {
    private Consumer<Configuration> customizer;

    public ConfigurationCustomizerBuildItem(Consumer<Configuration> customizer) {
        this.customizer = customizer;
    }

    public Consumer<Configuration> getCustomizer() {
        return customizer;
    }
}
