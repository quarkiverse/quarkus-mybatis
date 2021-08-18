package io.quarkiverse.mybatis.plus.deployment;

import org.jboss.jandex.DotName;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.quarkiverse.mybatis.deployment.ConfigurationFactoryBuildItem;
import io.quarkiverse.mybatis.deployment.SqlSessionFactoryBuilderBuildItem;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusConfigurationFactory;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class MyBatisPlusProcessor {

    private static final String FEATURE = "mybatis-plus";
    private static final DotName MYBATIS_PLUS_MAPPER = DotName.createSimple(BaseMapper.class.getName());
    private static final DotName MYBATIS_MAPPER_DATA_SOURCE = DotName.createSimple(MapperDataSource.class.getName());

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ConfigurationFactoryBuildItem createConfigurationFactory() {
        return new ConfigurationFactoryBuildItem(new MyBatisPlusConfigurationFactory());
    }

    @BuildStep
    SqlSessionFactoryBuilderBuildItem createSqlSessionFactoryBuilder() {
        return new SqlSessionFactoryBuilderBuildItem(new MybatisSqlSessionFactoryBuilder());
    }
}
