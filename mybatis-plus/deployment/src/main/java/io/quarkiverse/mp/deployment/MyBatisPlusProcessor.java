package io.quarkiverse.mp.deployment;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.javassist.util.proxy.ProxyFactory;
import org.apache.ibatis.logging.log4j.Log4jImpl;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.type.EnumTypeHandler;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.annotation.TableName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;

class MyBatisPlusProcessor {

    private static final Logger LOG = Logger.getLogger(MyBatisPlusProcessor.class);
    private static final String FEATURE = "mybatis-plus";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void runtimeInitialzed(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(Log4jImpl.class.getName()));
    }

    //    @BuildStep
    //    void addDependencies(BuildProducer<IndexDependencyBuildItem> indexDependency) {
    //        indexDependency.produce(new IndexDependencyBuildItem("com.baomidou", "mybatis-plus"));
    //    }

    @BuildStep
    void reflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            CombinedIndexBuildItem indexBuildItem) {
        reflectiveClass.produce(new ReflectiveClassBuildItem(false, false,
                ProxyFactory.class,
                XMLLanguageDriver.class,
                RawLanguageDriver.class,
                SelectProvider.class,
                UpdateProvider.class,
                InsertProvider.class,
                DeleteProvider.class,
                Result.class,
                Results.class,
                ResultType.class,
                ResultMap.class,
                EnumTypeHandler.class));

        reflectiveClass.produce(new ReflectiveClassBuildItem(true, true,
                PerpetualCache.class, LruCache.class));
        LOG.infof("add TableName ------------>");
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(DotName.createSimple(TableName.class.getName()))) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, dotName.toString()));
                LOG.infof("TableName class: %s", dotName.toString());
            }
        }
    }

    //    @Record(ExecutionTime.RUNTIME_INIT)
    //    @BuildStep
    //    void generateMapperBeans(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems) {
    //        for (SqlSessionFactoryBuildItem sqlSessionFactoryBuildItem : sqlSessionFactoryBuildItems) {
    //            SqlSession sqlSession = sqlSessionFactoryBuildItem.getSqlSessionFactory().getValue().openSession();
    //            Configuration configuration = sqlSession.getConfiguration();
    //            MybatisConfiguration mybatisConfiguration = new MybatisConfiguration(configuration.getEnvironment());
    //            mybatisConfiguration.addMapper();
    //
    //        }
    //    }

}
