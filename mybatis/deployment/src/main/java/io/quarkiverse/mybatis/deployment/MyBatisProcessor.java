package io.quarkiverse.mybatis.deployment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.javassist.util.proxy.ProxyFactory;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkiverse.mybatis.runtime.MyBatisConfigurationFactory;
import io.quarkiverse.mybatis.runtime.MyBatisRecorder;
import io.quarkiverse.mybatis.runtime.MyBatisXMLConfigDelegateBuilder;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;
import io.quarkiverse.mybatis.runtime.config.MyBatisDataSourceRuntimeConfig;
import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeansRuntimeInitBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Overridable;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem.DefaultIgnoreTypePredicate;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;

@SuppressWarnings("unused")
public class MyBatisProcessor {

    private static final Logger LOG = Logger.getLogger(MyBatisProcessor.class);
    private static final String FEATURE = "mybatis";
    private static final DotName MYBATIS_MAPPER = DotName.createSimple(Mapper.class.getName());
    private static final DotName MYBATIS_TYPE_HANDLER = DotName.createSimple(MappedTypes.class.getName());
    private static final DotName MYBATIS_JDBC_TYPE_HANDLER = DotName.createSimple(MappedJdbcTypes.class.getName());
    private static final DotName MYBATIS_MAPPER_DATA_SOURCE = DotName.createSimple(MapperDataSource.class.getName());
    private static final DotName MYBATIS_CACHE_NAMESPACE = DotName.createSimple(CacheNamespace.class.getName());
    private static final DotName MYBATIS_CACHE_NAMESPACE_REF = DotName.createSimple(CacheNamespaceRef.class.getName());
    private static final Class<?>[] SERIALIZABLE_JDK_TYPES = {
            ArrayList.class,
            String.class,
            Number.class,
            Boolean.class,
            Byte.class,
            Character.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigDecimal.class,
            BigInteger.class,
            UUID.class,
            Date.class,
            java.sql.Date.class,
            Time.class,
            Timestamp.class,
            Instant.class,
            LocalDate.class,
            LocalTime.class,
            LocalDateTime.class,
            OffsetTime.class,
            OffsetDateTime.class,
            ZonedDateTime.class,
            Enum.class
    };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void runtimeInitialized(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.apache.ibatis.logging.log4j.Log4jImpl"));
    }

    @BuildStep
    void reflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ProxyFactory.class,
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
                EnumTypeHandler.class).methods(false).fields(false).build());

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(DefaultSqlSession.class, TransactionalSqlSession.class,
                SqlSessionManager.class, SystemMetaObject.class, MetaObject.class).methods(true).fields(true).build());
        reflectiveClass.produce(
                ReflectiveClassBuildItem.builder(PerpetualCache.class, LruCache.class).methods(true).fields(true).build());
    }

    /**
     * The default read / write second level cache (SerializedCache) stores query results using Java serialization.
     * For mappers that use a second level cache, register the result types of mapper methods, together with common JDK
     * value types, for serialization.
     */
    @BuildStep
    void serializableResultTypes(List<MyBatisMapperBuildItem> mappers,
            CombinedIndexBuildItem indexBuildItem,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy) {
        IndexView index = indexBuildItem.getIndex();
        Set<DotName> cachedMappers = new HashSet<>();
        collectAnnotationCachedMappers(index, cachedMappers);
        collectXmlCachedMappers(applicationArchivesBuildItem, cachedMappers);

        Set<DotName> visited = new HashSet<>();
        Set<DotName> resultTypes = new HashSet<>();
        for (MyBatisMapperBuildItem mapper : mappers) {
            if (cachedMappers.contains(mapper.getMapperName())) {
                collectMapperResultTypes(index, mapper.getMapperName(), visited, resultTypes);
            }
        }

        if (visited.isEmpty()) {
            // No mappers use a second level cache
            return;
        }

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(SERIALIZABLE_JDK_TYPES).serialization(true).build());
        // java.time types are serialized via a writeReplace proxy
        reflectiveClass.produce(ReflectiveClassBuildItem.builder("java.time.Ser").serialization(true).build());

        for (DotName resultType : resultTypes) {
            reflectiveHierarchy.produce(ReflectiveHierarchyBuildItem.builder(resultType)
                    .index(index)
                    .source(MyBatisProcessor.class.getSimpleName() + " > " + resultType)
                    .ignoreTypePredicate(DefaultIgnoreTypePredicate.INSTANCE.or(MyBatisProcessor::isFrameworkType))
                    .methods(false)
                    .fields(false)
                    .serialization(true)
                    .build());
        }
    }

    private static void collectAnnotationCachedMappers(IndexView index, Set<DotName> cachedMappers) {
        for (AnnotationInstance cacheNamespace : index.getAnnotations(MYBATIS_CACHE_NAMESPACE)) {
            AnnotationValue readWrite = cacheNamespace.value("readWrite");
            if (cacheNamespace.target().kind() == AnnotationTarget.Kind.CLASS
                    && (readWrite == null || readWrite.asBoolean())) {
                cachedMappers.add(cacheNamespace.target().asClass().name());
            }
        }
        // Mappers that share the cache of another namespace
        for (AnnotationInstance cacheNamespaceRef : index.getAnnotations(MYBATIS_CACHE_NAMESPACE_REF)) {
            if (cacheNamespaceRef.target().kind() == AnnotationTarget.Kind.CLASS) {
                cachedMappers.add(cacheNamespaceRef.target().asClass().name());
            }
        }
    }

    private static void collectXmlCachedMappers(ApplicationArchivesBuildItem applicationArchivesBuildItem,
            Set<DotName> cachedMappers) {
        for (ApplicationArchive archive : applicationArchivesBuildItem.getAllApplicationArchives()) {
            archive.accept(tree -> tree.walk(visit -> {
                if (!visit.getRelativePath("/").endsWith(".xml") || Files.isDirectory(visit.getPath())) {
                    return;
                }
                try {
                    String content = Files.readString(visit.getPath());
                    if (!content.contains("<mapper") || !content.contains("<cache")) {
                        return;
                    }
                    XPathParser parser = new XPathParser(content, false, null, new XMLMapperEntityResolver());
                    XNode mapper = parser.evalNode("/mapper");
                    String namespace = mapper != null ? mapper.getStringAttribute("namespace") : null;
                    if (namespace == null) {
                        return;
                    }
                    XNode cache = parser.evalNode("/mapper/cache");
                    if ((cache != null && !cache.getBooleanAttribute("readOnly", false))
                            || parser.evalNode("/mapper/cache-ref") != null) {
                        cachedMappers.add(DotName.createSimple(namespace));
                    }
                } catch (Exception e) {
                    LOG.debugf(e, "Unable to determine second level cache usage from %s", visit.getRelativePath("/"));
                }
            }));
        }
    }

    private static boolean isFrameworkType(DotName name) {
        String className = name.toString();
        return className.startsWith("org.apache.ibatis.") || className.startsWith("com.baomidou.");
    }

    private static void collectMapperResultTypes(IndexView index, DotName mapperName, Set<DotName> visited,
            Set<DotName> resultTypes) {
        if (!visited.add(mapperName)) {
            return;
        }
        ClassInfo mapper = index.getClassByName(mapperName);
        if (mapper == null) {
            return;
        }
        for (MethodInfo method : mapper.methods()) {
            collectTypes(method.returnType(), resultTypes);
        }
        for (Type superInterface : mapper.interfaceTypes()) {
            // Generic base mappers, e.g. UserMapper extends BaseMapper<User>
            if (superInterface.kind() == Type.Kind.PARAMETERIZED_TYPE) {
                superInterface.asParameterizedType().arguments().forEach(argument -> collectTypes(argument, resultTypes));
            }
            collectMapperResultTypes(index, superInterface.name(), visited, resultTypes);
        }
    }

    private static void collectTypes(Type type, Set<DotName> types) {
        switch (type.kind()) {
            case CLASS -> addType(type.name(), types);
            case ARRAY -> collectTypes(type.asArrayType().elementType(), types);
            case PARAMETERIZED_TYPE -> {
                addType(type.name(), types);
                type.asParameterizedType().arguments().forEach(argument -> collectTypes(argument, types));
            }
            default -> {
                // Primitives, void, type variables and wildcards cannot be resolved to a concrete result type
            }
        }
    }

    private static void addType(DotName name, Set<DotName> types) {
        // JDK types are registered explicitly via SERIALIZABLE_JDK_TYPES
        if (!name.toString().startsWith("java.") && !isFrameworkType(name)) {
            types.add(name);
        }
    }

    @BuildStep
    @Overridable
    void addMyBatisMappers(BuildProducer<MyBatisMapperBuildItem> mappers,
            BuildProducer<ReflectiveClassBuildItem> reflective,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxy,
            CombinedIndexBuildItem indexBuildItem) {
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_MAPPER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                reflective.produce(ReflectiveClassBuildItem.builder(dotName.toString()).methods(true).fields(false).build());
                proxy.produce(new NativeImageProxyDefinitionBuildItem(dotName.toString()));

                Optional<AnnotationInstance> mapperDatasource = i.target().asClass().annotationsMap().entrySet().stream()
                        .filter(entry -> entry.getKey().equals(MYBATIS_MAPPER_DATA_SOURCE))
                        .map(Map.Entry::getValue)
                        .map(annotationList -> annotationList.get(0))
                        .findFirst();
                if (mapperDatasource.isPresent()) {
                    String dataSourceName = mapperDatasource.get().value().asString();
                    mappers.produce(new MyBatisMapperBuildItem(dotName, dataSourceName));
                } else {
                    mappers.produce(new MyBatisMapperBuildItem(dotName, "<default>"));
                }
            }
        }
    }

    @BuildStep
    void addMyBatisMappedTypes(BuildProducer<MyBatisMappedTypeBuildItem> mappedTypes,
            BuildProducer<MyBatisMappedJdbcTypeBuildItem> mappedJdbcTypes,
            CombinedIndexBuildItem indexBuildItem) {
        List<DotName> names = new ArrayList<>();
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_TYPE_HANDLER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                mappedTypes.produce(new MyBatisMappedTypeBuildItem(dotName));
                names.add(dotName);
            }
        }
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_JDBC_TYPE_HANDLER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                if (!names.contains(dotName)) {
                    mappedJdbcTypes.produce(new MyBatisMappedJdbcTypeBuildItem(dotName));
                }
            }
        }
    }

    @BuildStep
    void initialSql(BuildProducer<NativeImageResourceBuildItem> resource, MyBatisRuntimeConfig config) {
        config.initialSql().ifPresent(initialSqlString -> {
            String[] sqlFiles = initialSqlString.split(",");
            for (String sqlFile : sqlFiles) {
                resource.produce(new NativeImageResourceBuildItem(sqlFile.trim()));
            }
        });
        config.dataSources().values().forEach(dataSource -> dataSource.initialSql()
                .ifPresent(initialSqlString -> {
                    String[] sqlFiles = initialSqlString.split(",");
                    for (String sqlFile : sqlFiles) {
                        resource.produce(new NativeImageResourceBuildItem(sqlFile.trim()));
                    }
                }));
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void generateSqlSessionFactory(MyBatisRuntimeConfig myBatisRuntimeConfig,
            ConfigurationFactoryBuildItem configurationFactoryBuildItem,
            SqlSessionFactoryBuilderBuildItem sqlSessionFactoryBuilderBuildItem,
            List<MyBatisMapperBuildItem> myBatisMapperBuildItems,
            List<MyBatisMappedTypeBuildItem> myBatisMappedTypeBuildItems,
            List<MyBatisMappedJdbcTypeBuildItem> myBatisMappedJdbcTypeBuildItems,
            List<ConfigurationCustomizerBuildItem> configurationCustomizerBuildItems,
            List<JdbcDataSourceBuildItem> jdbcDataSourcesBuildItem,
            BuildProducer<SqlSessionFactoryBuildItem> sqlSessionFactory,
            MyBatisRecorder recorder) {
        List<String> mappedTypes = myBatisMappedTypeBuildItems
                .stream().map(m -> m.getMappedTypeName().toString()).collect(Collectors.toList());
        List<String> mappedJdbcTypes = myBatisMappedJdbcTypeBuildItems
                .stream().map(m -> m.getMappedJdbcTypeName().toString()).collect(Collectors.toList());

        List<Pair<String, Boolean>> dataSources = new ArrayList<>();
        if (myBatisRuntimeConfig.dataSource().isPresent()) {
            String dataSourceName = myBatisRuntimeConfig.dataSource().get();
            Optional<JdbcDataSourceBuildItem> jdbcDataSourceBuildItem = jdbcDataSourcesBuildItem.stream()
                    .filter(i -> i.getName().equals(dataSourceName))
                    .findFirst();
            if (jdbcDataSourceBuildItem.isEmpty()) {
                throw new ConfigurationException("Can not find datasource " + dataSourceName);
            }
            dataSources.add(Pair.of(dataSourceName, true));
        } else {
            dataSources = jdbcDataSourcesBuildItem.stream()
                    .map(dataSource -> Pair.of(dataSource.getName(), dataSource.isDefault()))
                    .collect(Collectors.toList());
            if (dataSources.isEmpty()) {
                throw new ConfigurationException("No datasource found");
            }
        }

        List<Consumer<Configuration>> configurationCustomizers = configurationCustomizerBuildItems.stream()
                .map(ConfigurationCustomizerBuildItem::getCustomizer)
                .collect(Collectors.toList());

        dataSources.forEach(dataSource -> {
            List<String> mappers = myBatisMapperBuildItems
                    .stream().filter(m -> m.getDataSourceName().equals(dataSource.getKey()))
                    .map(m -> m.getMapperName().toString()).collect(Collectors.toList());
            sqlSessionFactory.produce(
                    new SqlSessionFactoryBuildItem(
                            recorder.createSqlSessionFactory(
                                    configurationFactoryBuildItem.getFactory(),
                                    configurationCustomizers,
                                    sqlSessionFactoryBuilderBuildItem.getBuilder(),
                                    myBatisRuntimeConfig,
                                    dataSource.getKey(),
                                    mappers,
                                    mappedTypes,
                                    mappedJdbcTypes),
                            dataSource.getKey(), dataSource.getValue(), false));
        });
    }

    @BuildStep
    @Overridable
    ConfigurationFactoryBuildItem createConfigurationFactory() {
        return new ConfigurationFactoryBuildItem(new MyBatisConfigurationFactory());
    }

    @BuildStep
    @Overridable
    SqlSessionFactoryBuilderBuildItem createSqlSessionFactoryBuilder() {
        return new SqlSessionFactoryBuilderBuildItem(new SqlSessionFactoryBuilder());
    }

    @BuildStep
    @Overridable
    XMLConfigBuilderBuildItem createXMLConfigBuilder() {
        return new XMLConfigBuilderBuildItem(new MyBatisXMLConfigDelegateBuilder());
    }

    @BuildStep
    void xmlConfig(MyBatisRuntimeConfig config, BuildProducer<MyBatisXmlConfigBuildItem> xmlConfig) {
        if (config.xmlconfig().enable()) {
            xmlConfig.produce(new MyBatisXmlConfigBuildItem("xmlconfig", true));
        }
    }

    @BuildStep
    void registerXmlMappersForHotDeployment(MyBatisRuntimeConfig config,
            BuildProducer<HotDeploymentWatchedFileBuildItem> hotDeploymentProducer) {
        config.mapperLocations().ifPresent(mapperLocations -> {
            for (String mapperLocation : mapperLocations) {
                hotDeploymentProducer.produce(new HotDeploymentWatchedFileBuildItem(mapperLocation + "/**/*.xml"));
            }
        });

        hotDeploymentProducer.produce(new HotDeploymentWatchedFileBuildItem("**/*Mapper.xml"));
        hotDeploymentProducer.produce(new HotDeploymentWatchedFileBuildItem("mapper/**/*.xml"));
        hotDeploymentProducer.produce(new HotDeploymentWatchedFileBuildItem("mappers/**/*.xml"));
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void generateSqlSessionFactoryFromXmlConfig(
            MyBatisRuntimeConfig config,
            XMLConfigBuilderBuildItem xmlConfigBuilderBuildItem,
            MyBatisXmlConfigBuildItem xmlConfigBuildItem,
            BuildProducer<SqlSessionFactoryBuildItem> sqlSessionFactory,
            MyBatisRecorder recorder) {
        if (xmlConfigBuildItem != null && xmlConfigBuildItem.isEnabled()) {
            sqlSessionFactory.produce(
                    new SqlSessionFactoryBuildItem(
                            recorder.createSqlSessionFactory(config, xmlConfigBuilderBuildItem.getBuilder()),
                            xmlConfigBuildItem.getName(), false, true));
        }
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void generateSqlSessionManager(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            BuildProducer<SqlSessionManagerBuildItem> sqlSessionManager,
            MyBatisRecorder recorder) {
        sqlSessionFactoryBuildItems.forEach(sessionFactory -> sqlSessionManager.produce(
                new SqlSessionManagerBuildItem(
                        recorder.createSqlSessionManager(sessionFactory.getSqlSessionFactory()),
                        sessionFactory.getDataSourceName(),
                        sessionFactory.isDefaultDataSource())));
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void generateMapperBeans(MyBatisRecorder recorder,
            List<MyBatisMapperBuildItem> myBatisMapperBuildItems,
            List<MyBatisMappedTypeBuildItem> myBatisMappedTypesBuildItems,
            List<MyBatisMappedJdbcTypeBuildItem> myBatisMappedJdbcTypesBuildItems,
            List<SqlSessionManagerBuildItem> sqlSessionManagerBuildItems,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer) {
        Map<String, SqlSessionManagerBuildItem> dataSourceToSessionManagerBuildItem = sqlSessionManagerBuildItems.stream()
                .collect(Collectors.toMap(SqlSessionManagerBuildItem::getDataSourceName, Function.identity()));
        SqlSessionManagerBuildItem defaultSqlSessionManagerBuildItem = getDefaultSessionManager(sqlSessionManagerBuildItems);
        for (MyBatisMapperBuildItem i : myBatisMapperBuildItems) {
            SqlSessionManagerBuildItem sessionManagerBuildItem;
            if (i.getDataSourceName() == null) {
                if (defaultSqlSessionManagerBuildItem.isDefaultDataSource() || sqlSessionManagerBuildItems.size() == 1) {
                    sessionManagerBuildItem = defaultSqlSessionManagerBuildItem;
                } else {
                    throw new ConfigurationException("Could not choose data source for mapper: " + i.getMapperName() +
                            ". Please use @MapperDataSource annotation for specified the mapper class");
                }
            } else {
                sessionManagerBuildItem = dataSourceToSessionManagerBuildItem.get(i.getDataSourceName());
                if (sessionManagerBuildItem == null) {
                    throw new ConfigurationException(String.format("Data source %s does not exist", i.getDataSourceName()));
                }
            }
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMapperName())
                    .scope(ApplicationScoped.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMapperSupplier(i.getMapperName().toString(),
                            sessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
        for (MyBatisMappedTypeBuildItem i : myBatisMappedTypesBuildItems) {
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMappedTypeName())
                    .scope(ApplicationScoped.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMappedTypeSupplier(i.getMappedTypeName().toString(),
                            defaultSqlSessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
        for (MyBatisMappedJdbcTypeBuildItem i : myBatisMappedJdbcTypesBuildItems) {
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMappedJdbcTypeName())
                    .scope(ApplicationScoped.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMappedJdbcTypeSupplier(i.getMappedJdbcTypeName().toString(),
                            defaultSqlSessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void register(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer,
            MyBatisRecorder recorder) {
        sqlSessionFactoryBuildItems.forEach(sqlSessionFactoryBuildItem -> {
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(SqlSessionFactory.class)
                    .scope(ApplicationScoped.class)
                    .unremovable()
                    .supplier(recorder.MyBatisSqlSessionFactorySupplier(sqlSessionFactoryBuildItem.getSqlSessionFactory()));
            String dataSourceName = sqlSessionFactoryBuildItem.getDataSourceName();
            if (!sqlSessionFactoryBuildItem.isDefaultDataSource()) {
                configurator.reserve(true);
                configurator.addQualifier().annotation(Named.class).addValue("value", dataSourceName).done();
            }
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        });
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @Consume(SyntheticBeansRuntimeInitBuildItem.class)
    @BuildStep
    void runInitialSql(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            MyBatisRuntimeConfig myBatisRuntimeConfig,
            MyBatisRecorder recorder) {
        sqlSessionFactoryBuildItems.forEach(sqlSessionFactoryBuildItem -> {
            MyBatisDataSourceRuntimeConfig dataSourceConfig = myBatisRuntimeConfig.dataSources()
                    .get(sqlSessionFactoryBuildItem.getDataSourceName());
            Optional<String> optionalInitialSqlString;
            if (sqlSessionFactoryBuildItem.isDefaultDataSource() || sqlSessionFactoryBuildItems.size() == 1) {
                optionalInitialSqlString = dataSourceConfig != null && dataSourceConfig.initialSql().isPresent()
                        ? dataSourceConfig.initialSql()
                        : myBatisRuntimeConfig.initialSql();
            } else {
                optionalInitialSqlString = dataSourceConfig != null ? dataSourceConfig.initialSql() : Optional.empty();
            }
            optionalInitialSqlString.ifPresent(initialSqlString -> recorder.runInitialSqlString(
                    sqlSessionFactoryBuildItem.getSqlSessionFactory(), initialSqlString));
        });
    }

    private SqlSessionManagerBuildItem getDefaultSessionManager(List<SqlSessionManagerBuildItem> sqlSessionManagerBuildItems) {
        return sqlSessionManagerBuildItems.stream()
                .filter(SqlSessionManagerBuildItem::isDefaultDataSource)
                .findFirst()
                .orElse(sqlSessionManagerBuildItems.get(0));
    }
}
