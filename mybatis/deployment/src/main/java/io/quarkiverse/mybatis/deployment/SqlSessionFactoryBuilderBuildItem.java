package io.quarkiverse.mybatis.deployment;

import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import io.quarkus.builder.item.SimpleBuildItem;

public final class SqlSessionFactoryBuilderBuildItem extends SimpleBuildItem {
    private final SqlSessionFactoryBuilder builder;

    public SqlSessionFactoryBuilderBuildItem(SqlSessionFactoryBuilder builder) {
        this.builder = builder;
    }

    public SqlSessionFactoryBuilder getBuilder() {
        return builder;
    }
}
