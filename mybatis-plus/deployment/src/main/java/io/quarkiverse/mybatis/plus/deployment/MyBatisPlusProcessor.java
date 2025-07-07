package io.quarkiverse.mybatis.plus.deployment;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.quarkiverse.mybatis.deployment.ConfigurationCustomizerBuildItem;
import io.quarkiverse.mybatis.deployment.ConfigurationFactoryBuildItem;
import io.quarkiverse.mybatis.deployment.SqlSessionFactoryBuildItem;
import io.quarkiverse.mybatis.deployment.SqlSessionFactoryBuilderBuildItem;
import io.quarkiverse.mybatis.deployment.XMLConfigBuilderBuildItem;
import io.quarkiverse.mybatis.plus.MyBatisPlusConfig;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusConfigurationFactory;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusRecorder;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusXMLConfigDelegateBuilder;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

public class MyBatisPlusProcessor {

    private static final String FEATURE = "mybatis-plus";
    private static final DotName MYBATIS_PLUS_MAPPER = DotName.createSimple(BaseMapper.class.getName());
    private static final DotName MYBATIS_MAPPER_DATA_SOURCE = DotName.createSimple(MapperDataSource.class.getName());
    private static final DotName MYBATIS_PLUS_WRAPPER = DotName.createSimple(Wrapper.class.getName());
    private static final Logger LOG = Logger.getLogger(MyBatisPlusProcessor.class);

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
    XMLConfigBuilderBuildItem createXMLConfigBuilder() throws Exception {
        return new XMLConfigBuilderBuildItem(new MyBatisPlusXMLConfigDelegateBuilder());
    }

    @BuildStep
    void addDependencies(BuildProducer<IndexDependencyBuildItem> indexDependency) {
        indexDependency.produce(new IndexDependencyBuildItem("com.baomidou", "mybatis-plus-core"));
    }

    @BuildStep
    void reflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxyClass,
            CombinedIndexBuildItem indexBuildItem) {
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(StatementHandler.class,
                Executor.class).methods(true).fields(false).build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(BoundSql.class).methods(true).fields(true).build());
        proxyClass.produce(new NativeImageProxyDefinitionBuildItem(StatementHandler.class.getName()));
        proxyClass.produce(new NativeImageProxyDefinitionBuildItem(Executor.class.getName()));

        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(DotName.createSimple(TableName.class.getName()))) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                reflectiveClass
                        .produce(ReflectiveClassBuildItem.builder(dotName.toString()).methods(true).fields(false).build());
            }
        }
        reflectiveClass
                .produce(ReflectiveClassBuildItem.builder(MYBATIS_PLUS_WRAPPER.toString()).methods(true).fields(true).build());
        for (ClassInfo classInfo : indexBuildItem.getIndex().getAllKnownSubclasses(MYBATIS_PLUS_WRAPPER)) {
            reflectiveClass
                    .produce(ReflectiveClassBuildItem.builder(classInfo.name().toString()).methods(true).fields(true).build());
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void init(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            MyBatisPlusConfig config,
            MyBatisPlusRecorder recorder) {
        sqlSessionFactoryBuildItems
                .forEach(sqlSessionFactory -> recorder.initSqlSession(sqlSessionFactory.getSqlSessionFactory(), config));
    }

    private ConfigurationCustomizerBuildItem addCustomizer(
            MyBatisPlusRecorder recorder,
            MyBatisPlusConfig config,
            Function<MyBatisPlusConfig, Consumer<Configuration>> customizerSupplier) {
        return new ConfigurationCustomizerBuildItem(customizerSupplier.apply(config));
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem addCustomSqlInjector(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::addCustomSqlInjector);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem addCustomIdentifierGenerator(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::addCustomIdentifierGenerator);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigIdType(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigIdType);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigTablePrefix(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigTablePrefix);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigSchema(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigSchema);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigColumnFormat(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigColumnFormat);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigTableFormat(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigTableFormat);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigPropertyFormat(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigPropertyFormat);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigTableUnderline(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigTableUnderline);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigCapitalMode(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigCapitalMode);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigKeyGenerator(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigKeyGenerator);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigLogicDeleteField(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigLogicDeleteField);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigLogicDeleteValue(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigLogicDeleteValue);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigLogicNotDeleteValue(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigLogicNotDeleteValue);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ConfigurationCustomizerBuildItem setDbConfigFieldStrategy(MyBatisPlusRecorder recorder, MyBatisPlusConfig config) {
        return addCustomizer(recorder, config, recorder::setDbConfigFieldStrategy);
    }
}
