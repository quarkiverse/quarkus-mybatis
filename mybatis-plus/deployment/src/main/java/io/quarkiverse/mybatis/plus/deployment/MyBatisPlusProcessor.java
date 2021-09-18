package io.quarkiverse.mybatis.plus.deployment;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.quarkiverse.mybatis.deployment.ConfigurationFactoryBuildItem;
import io.quarkiverse.mybatis.deployment.SqlSessionFactoryBuilderBuildItem;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusConfigurationFactory;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

public class MyBatisPlusProcessor {

    private static final String FEATURE = "mybatis-plus";
    private static final DotName MYBATIS_PLUS_MAPPER = DotName.createSimple(BaseMapper.class.getName());
    private static final DotName MYBATIS_MAPPER_DATA_SOURCE = DotName.createSimple(MapperDataSource.class.getName());
    private static final DotName MYBATIS_PLUS_WRAPPER = DotName.createSimple(Wrapper.class.getName());

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

    @BuildStep
    void reflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            CombinedIndexBuildItem indexBuildItem) {

        reflectiveClass.produce(new ReflectiveClassBuildItem(true, true, PerpetualCache.class, LruCache.class));
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(DotName.createSimple(TableName.class.getName()))) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, dotName.toString()));
            }
        }
        reflectiveClass.produce(new ReflectiveClassBuildItem(true, true, MYBATIS_PLUS_WRAPPER.toString()));
        for (ClassInfo classInfo : indexBuildItem.getIndex().getAllKnownSubclasses(MYBATIS_PLUS_WRAPPER)) {
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, true, classInfo.name().toString()));
        }
    }
}
