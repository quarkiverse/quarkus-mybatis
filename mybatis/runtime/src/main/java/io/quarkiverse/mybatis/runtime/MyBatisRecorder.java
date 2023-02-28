package io.quarkiverse.mybatis.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.sql.DataSource;

import jakarta.transaction.TransactionManager;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.loader.cglib.CglibProxyFactory;
import org.apache.ibatis.executor.loader.javassist.JavassistProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mybatis.runtime.config.MyBatisDataSourceRuntimeConfig;
import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.agroal.runtime.DataSources;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MyBatisRecorder {
    private static final Logger LOG = Logger.getLogger(MyBatisRecorder.class);

    public RuntimeValue<SqlSessionFactory> createSqlSessionFactory(
            MyBatisRuntimeConfig config, XMLConfigDelegateBuilder builder) {
        Configuration configuration;

        try {
            builder.setConfig(config);
            builder.getConfiguration().getTypeAliasRegistry().registerAlias("QUARKUS", QuarkusDataSourceFactory.class);
            configuration = builder.parse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        return new RuntimeValue<>(sqlSessionFactory);
    }

    public RuntimeValue<Configuration> createConfiguration() {
        return new RuntimeValue<>(new Configuration());
    }

    public RuntimeValue<SqlSessionFactory> createSqlSessionFactory(
            ConfigurationFactory configurationFactory,
            SqlSessionFactoryBuilder builder,
            MyBatisRuntimeConfig myBatisRuntimeConfig,
            MyBatisDataSourceRuntimeConfig myBatisDataSourceRuntimeConfig,
            String dataSourceName,
            List<String> mappers,
            List<String> mappedTypes,
            List<String> mappedJdbcTypes) {
        Configuration configuration = configurationFactory.createConfiguration();
        setupConfiguration(configuration, configurationFactory.isOverrideSetting(), myBatisRuntimeConfig,
                myBatisDataSourceRuntimeConfig, dataSourceName);
        addMappers(configuration, myBatisRuntimeConfig, mappedTypes, mappedJdbcTypes, mappers, dataSourceName);
        SqlSessionFactory sqlSessionFactory = builder.build(configuration);
        return new RuntimeValue<>(sqlSessionFactory);
    }

    private void buildFromMapperLocations(Configuration configuration, MyBatisRuntimeConfig myBatisRuntimeConfig,
            String dataSourceName) {
        myBatisRuntimeConfig.mapperLocations.ifPresent(mapperLocations -> {
            for (String mapperLocation : mapperLocations) {
                try {
                    if (mapperLocation.endsWith("/")) {
                        mapperLocation = mapperLocation.substring(0, mapperLocation.length() - 1);
                    }
                    if (mapperLocation.startsWith("/")) {
                        mapperLocation = mapperLocation.substring(1);
                    }
                    final String path = Thread.currentThread().getContextClassLoader()
                            .getResource(mapperLocation).getFile();
                    if (path.contains("jar!")) {
                        File resourceFile = Paths.get(new URL(path.substring(0, path.indexOf("!"))).toURI()).toFile();
                        try (JarFile jarFile = new JarFile(resourceFile)) {
                            Enumeration<JarEntry> entries = jarFile.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry entry = entries.nextElement();
                                String resourceName = entry.getName();
                                if (!entry.isDirectory() && resourceName.startsWith(mapperLocation)
                                        && !resourceName.endsWith(".class") && resourceName.endsWith(".xml")) {
                                    buildXmlMapper(jarFile.getInputStream(entry), jarFile.getInputStream(entry),
                                            entry.toString(), configuration, dataSourceName);
                                }
                            }
                        }
                    } else {
                        final File resources = new File(
                                Thread.currentThread().getContextClassLoader().getResource(mapperLocation).getFile());
                        if (resources.listFiles() == null) {
                            continue;
                        }
                        for (File file : resources.listFiles()) {
                            if (file.getName().endsWith(".xml")) {
                                buildXmlMapper(new FileInputStream(file), new FileInputStream(file),
                                        file.toString(),
                                        configuration, dataSourceName);
                            }
                        }
                    }
                } catch (NullPointerException | IOException | URISyntaxException e) {
                    LOG.warnf("Not found mapper location :%s.", mapperLocation);
                    continue;
                } catch (ClassNotFoundException e) {
                    LOG.warnf("Not found mapper class :%s.", e.getMessage());
                    continue;
                }
            }
        });
    }

    private void buildXmlMapper(InputStream filterStream, InputStream resourceStream, String resource,
            Configuration configuration,
            String dataSourceName)
            throws ClassNotFoundException {
        final XPathParser xPathParser = new XPathParser(filterStream,
                true, configuration.getVariables(), new XMLMapperEntityResolver());
        String nameSpace = xPathParser.evalNode("/mapper").getStringAttribute("namespace");
        final Class<?> mapperClass = Resources.classForName(nameSpace);
        final MapperDataSource annotation = mapperClass.getAnnotation(MapperDataSource.class);
        if ((annotation != null && annotation.value().equals(dataSourceName))
                || (annotation == null && dataSourceName.equals("<default>"))) {
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(resourceStream,
                    configuration, resource, configuration.getSqlFragments());
            xmlMapperBuilder.parse();
        }
    }

    private void addMappers(Configuration configuration, MyBatisRuntimeConfig myBatisRuntimeConfig,
            List<String> mappedTypes, List<String> mappedJdbcTypes, List<String> mappers, String dataSourceName) {
        for (String mappedType : mappedTypes) {
            try {
                configuration.getTypeHandlerRegistry().register(Resources.classForName(mappedType));
            } catch (ClassNotFoundException e) {
                LOG.debug("Can not find the mapped type class " + mappedType);
            }
        }

        for (String mappedJdbcType : mappedJdbcTypes) {
            try {
                configuration.getTypeHandlerRegistry().register(Resources.classForName(mappedJdbcType));
            } catch (ClassNotFoundException e) {
                LOG.debug("Can not find the mapped jdbc type class " + mappedJdbcType);
            }
        }

        buildFromMapperLocations(configuration, myBatisRuntimeConfig, dataSourceName);

        for (String mapper : mappers) {
            try {
                if (configuration.getMapperRegistry().hasMapper(Resources.classForName(mapper))) {
                    continue;
                }
                configuration.addMapper(Resources.classForName(mapper));
            } catch (ClassNotFoundException e) {
                LOG.debug("Can not find the mapper class " + mapper);
            }
        }
    }

    private Configuration setupConfiguration(Configuration configuration,
            boolean isOverrideSetting,
            MyBatisRuntimeConfig runtimeConfig,
            MyBatisDataSourceRuntimeConfig dataSourceRuntimeConfig,
            String dataSourceName) {
        TransactionFactory factory;
        String transactionFactory = dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.transactionFactory.isPresent()
                ? dataSourceRuntimeConfig.transactionFactory.get()
                : runtimeConfig.transactionFactory;
        if (transactionFactory.equals("MANAGED")) {
            factory = new ManagedTransactionFactory();
        } else {
            factory = new JdbcTransactionFactory();
        }

        configuration.setCacheEnabled(dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.cacheEnabled.isPresent()
                ? dataSourceRuntimeConfig.cacheEnabled.get()
                : runtimeConfig.cacheEnabled);
        configuration.setLazyLoadingEnabled(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.lazyLoadingEnabled.isPresent() ? dataSourceRuntimeConfig.lazyLoadingEnabled.get()
                        : runtimeConfig.lazyLoadingEnabled);
        configuration.setAggressiveLazyLoading(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.aggressiveLazyLoading.isPresent() ? dataSourceRuntimeConfig.aggressiveLazyLoading.get()
                        : runtimeConfig.aggressiveLazyLoading);
        configuration.setMultipleResultSetsEnabled(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.multipleResultSetsEnabled.isPresent()
                        ? dataSourceRuntimeConfig.multipleResultSetsEnabled.get()
                        : runtimeConfig.multipleResultSetsEnabled);
        configuration.setUseColumnLabel(dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.useColumnLabel.isPresent()
                ? dataSourceRuntimeConfig.useColumnLabel.get()
                : runtimeConfig.useColumnLabel);
        configuration.setUseGeneratedKeys(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.useGeneratedKeys.isPresent() ? dataSourceRuntimeConfig.useGeneratedKeys.get()
                        : runtimeConfig.useGeneratedKeys);
        configuration.setAutoMappingBehavior(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.autoMappingBehavior.isPresent() ? dataSourceRuntimeConfig.autoMappingBehavior.get()
                        : runtimeConfig.autoMappingBehavior);
        configuration.setAutoMappingUnknownColumnBehavior(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.autoMappingUnknownColumnBehavior.isPresent()
                        ? dataSourceRuntimeConfig.autoMappingUnknownColumnBehavior.get()
                        : runtimeConfig.autoMappingUnknownColumnBehavior);
        configuration.setDefaultExecutorType(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.defaultExecutorType.isPresent() ? dataSourceRuntimeConfig.defaultExecutorType.get()
                        : runtimeConfig.defaultExecutorType);
        if (dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.defaultStatementTimeout.isPresent()) {
            configuration.setDefaultStatementTimeout(dataSourceRuntimeConfig.defaultStatementTimeout.get());
        } else {
            runtimeConfig.defaultStatementTimeout.ifPresent(configuration::setDefaultStatementTimeout);
        }
        if (dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.defaultFetchSize.isPresent()) {
            configuration.setDefaultFetchSize(dataSourceRuntimeConfig.defaultFetchSize.get());
        } else {
            runtimeConfig.defaultFetchSize.ifPresent(configuration::setDefaultFetchSize);
        }
        if (dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.defaultResultSetType.isPresent()) {
            configuration.setDefaultResultSetType(dataSourceRuntimeConfig.defaultResultSetType.get());
        } else {
            runtimeConfig.defaultResultSetType.ifPresent(configuration::setDefaultResultSetType);
        }
        configuration.setSafeRowBoundsEnabled(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.safeRowBoundsEnabled.isPresent() ? dataSourceRuntimeConfig.safeRowBoundsEnabled.get()
                        : runtimeConfig.safeRowBoundsEnabled);
        configuration.setSafeResultHandlerEnabled(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.safeResultHandlerEnabled.isPresent()
                        ? dataSourceRuntimeConfig.safeResultHandlerEnabled.get()
                        : runtimeConfig.safeResultHandlerEnabled);
        configuration.setMapUnderscoreToCamelCase(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.mapUnderscoreToCamelCase.isPresent()
                        ? dataSourceRuntimeConfig.mapUnderscoreToCamelCase.get()
                        : runtimeConfig.mapUnderscoreToCamelCase);
        configuration.setLocalCacheScope(dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.localCacheScope.isPresent()
                ? dataSourceRuntimeConfig.localCacheScope.get()
                : runtimeConfig.localCacheScope);
        configuration.setJdbcTypeForNull(dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.jdbcTypeForNull.isPresent()
                ? dataSourceRuntimeConfig.jdbcTypeForNull.get()
                : runtimeConfig.jdbcTypeForNull);
        configuration.setLazyLoadTriggerMethods(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.lazyLoadTriggerMethods.isPresent()
                        ? dataSourceRuntimeConfig.lazyLoadTriggerMethods.get()
                        : runtimeConfig.lazyLoadTriggerMethods);
        try {
            if (!isOverrideSetting) {
                String defaultScriptingLanguage = dataSourceRuntimeConfig != null &&
                        dataSourceRuntimeConfig.defaultScriptingLanguage.isPresent()
                                ? dataSourceRuntimeConfig.defaultScriptingLanguage.get()
                                : runtimeConfig.defaultScriptingLanguage;
                configuration.setDefaultScriptingLanguage(
                        (Class<? extends LanguageDriver>) Resources.classForName(defaultScriptingLanguage));
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        try {
            if (!isOverrideSetting) {
                String defaultEnumTypeHandler = dataSourceRuntimeConfig != null &&
                        dataSourceRuntimeConfig.defaultEnumTypeHandler.isPresent()
                                ? dataSourceRuntimeConfig.defaultEnumTypeHandler.get()
                                : runtimeConfig.defaultEnumTypeHandler;
                configuration.setDefaultEnumTypeHandler(
                        (Class<? extends TypeHandler>) Resources.classForName(defaultEnumTypeHandler));
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        configuration.setCallSettersOnNulls(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.callSettersOnNulls.isPresent() ? dataSourceRuntimeConfig.callSettersOnNulls.get()
                        : runtimeConfig.callSettersOnNulls);
        configuration.setReturnInstanceForEmptyRow(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.returnInstanceForEmptyRow.isPresent()
                        ? dataSourceRuntimeConfig.returnInstanceForEmptyRow.get()
                        : runtimeConfig.returnInstanceForEmptyRow);
        if (dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.logPrefix.isPresent()) {
            configuration.setLogPrefix(dataSourceRuntimeConfig.logPrefix.get());
        } else {
            runtimeConfig.logPrefix.ifPresent(configuration::setLogPrefix);
        }

        Optional<String> optionalLogImpl = dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.logImpl.isPresent()
                ? dataSourceRuntimeConfig.logImpl
                : runtimeConfig.logImpl;
        optionalLogImpl.ifPresent(logImpl -> {
            try {
                configuration.setLogImpl((Class<? extends Log>) Resources.classForName(logImpl));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        String proxyFactory = dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.proxyFactory.isPresent()
                ? dataSourceRuntimeConfig.proxyFactory.get()
                : runtimeConfig.proxyFactory;
        if ("JAVASSIST".equals(proxyFactory)) {
            configuration.setProxyFactory(new JavassistProxyFactory());
        } else if ("CGLIB".equals(proxyFactory)) {
            configuration.setProxyFactory(new CglibProxyFactory());
        }

        Optional<String> optionalVfsImpl = dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.vfsImpl.isPresent()
                ? dataSourceRuntimeConfig.vfsImpl
                : runtimeConfig.vfsImpl;
        optionalVfsImpl.ifPresent(vfsImpl -> {
            try {
                configuration.setVfsImpl((Class<? extends VFS>) Resources.classForName(vfsImpl));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        configuration.setUseActualParamName(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.useActualParamName.isPresent() ? dataSourceRuntimeConfig.useActualParamName.get()
                        : runtimeConfig.useActualParamName);

        Optional<String> optionalConfigurationFactory = dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.configurationFactory.isPresent() ? dataSourceRuntimeConfig.configurationFactory
                        : runtimeConfig.configurationFactory;
        optionalConfigurationFactory.ifPresent(configurationFactory -> {
            try {
                configuration.setConfigurationFactory(Resources.classForName(configurationFactory));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        configuration.setShrinkWhitespacesInSql(dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.shrinkWhitespacesInSql.isPresent()
                        ? dataSourceRuntimeConfig.shrinkWhitespacesInSql.get()
                        : runtimeConfig.shrinkWhitespacesInSql);

        Optional<String> optionalDefaultSqlProviderType = dataSourceRuntimeConfig != null &&
                dataSourceRuntimeConfig.defaultSqlProviderType.isPresent() ? dataSourceRuntimeConfig.defaultSqlProviderType
                        : runtimeConfig.defaultSqlProviderType;
        optionalDefaultSqlProviderType.ifPresent(defaultSqlProviderType -> {
            try {
                configuration.setDefaultSqlProviderType(Resources.classForName(defaultSqlProviderType));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        });

        String environment = dataSourceRuntimeConfig != null && dataSourceRuntimeConfig.environment.isPresent()
                ? dataSourceRuntimeConfig.environment.get()
                : runtimeConfig.environment;
        Environment.Builder environmentBuilder = new Environment.Builder(environment)
                .transactionFactory(factory)
                .dataSource(new QuarkusDataSource(dataSourceName));
        configuration.setEnvironment(environmentBuilder.build());

        return configuration;
    }

    public RuntimeValue<TransactionalSqlSession> createSqlSessionManager(RuntimeValue<SqlSessionFactory> sqlSessionFactory) {
        TransactionManager transactionManager = Arc.container().instance(TransactionManager.class).get();
        TransactionalSqlSession sqlSessionManager = new TransactionalSqlSession(sqlSessionFactory.getValue(),
                transactionManager);
        return new RuntimeValue<>(sqlSessionManager);
    }

    public Supplier<Object> MyBatisMapperSupplier(String name, RuntimeValue<TransactionalSqlSession> sqlSessionManager) {
        return () -> {
            try {
                return sqlSessionManager.getValue().getMapper(Resources.classForName(name));
            } catch (ClassNotFoundException e) {
                return null;
            }
        };
    }

    public Supplier<Object> MyBatisMappedTypeSupplier(String name, RuntimeValue<TransactionalSqlSession> sqlSessionManager) {
        return () -> {
            try {
                return sqlSessionManager.getValue().getConfiguration().getTypeHandlerRegistry()
                        .getTypeHandler(Resources.classForName(name));
            } catch (ClassNotFoundException e) {
                return null;
            }
        };
    }

    public Supplier<Object> MyBatisMappedJdbcTypeSupplier(String name,
            RuntimeValue<TransactionalSqlSession> sqlSessionManager) {
        return () -> {
            try {
                return sqlSessionManager.getValue().getConfiguration().getTypeHandlerRegistry()
                        .getTypeHandler(Resources.classForName(name));
            } catch (ClassNotFoundException e) {
                return null;
            }
        };
    }

    public Supplier<Object> MyBatisSqlSessionFactorySupplier(RuntimeValue<SqlSessionFactory> sqlSessionFactory) {
        return sqlSessionFactory::getValue;
    }

    public void runInitialSql(RuntimeValue<SqlSessionFactory> sqlSessionFactory, String sql) {
        try (SqlSession session = sqlSessionFactory.getValue().openSession()) {
            Connection conn = session.getConnection();
            Reader reader = Resources.getResourceAsReader(sql);
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setLogWriter(null);
            runner.runScript(reader);
            reader.close();
        } catch (Exception e) {
            LOG.warn("Error executing SQL Script " + sql, e);
        }
    }
}

class QuarkusDataSource implements DataSource {
    private String dataSourceName;
    private AgroalDataSource dataSource;

    public QuarkusDataSource(String dataSourceName) {
        this.dataSourceName = dataSourceName;
        this.dataSource = null;
    }

    private DataSource getDataSource() {
        if (dataSource == null) {
            dataSource = DataSources.fromName(dataSourceName);
        }
        return dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    @Override
    public Connection getConnection(String user, String passwd) throws SQLException {
        return getDataSource().getConnection(user, passwd);
    }

    @Override
    public <T> T unwrap(Class<T> aClass) throws SQLException {
        return getDataSource().unwrap(aClass);
    }

    @Override
    public boolean isWrapperFor(Class<?> aClass) throws SQLException {
        return getDataSource().isWrapperFor(aClass);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return getDataSource().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter printWriter) throws SQLException {
        getDataSource().setLogWriter(printWriter);
    }

    @Override
    public void setLoginTimeout(int timeout) throws SQLException {
        getDataSource().setLoginTimeout(timeout);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return getDataSource().getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return getDataSource().getParentLogger();
    }
}
