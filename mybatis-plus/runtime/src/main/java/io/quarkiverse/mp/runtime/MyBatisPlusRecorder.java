//package io.quarkiverse.mp.runtime;
//
//import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
//import io.quarkiverse.mybatis.runtime.MyBatisRecorder;
//import io.quarkiverse.mybatis.runtime.QuarkusDataSourceFactory;
//import io.quarkiverse.mybatis.runtime.config.MyBatisDataSourceRuntimeConfig;
//import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;
//import io.quarkus.runtime.RuntimeValue;
//import io.quarkus.runtime.annotations.Recorder;
//import org.apache.ibatis.builder.xml.XMLConfigBuilder;
//import org.apache.ibatis.io.Resources;
//import org.apache.ibatis.session.Configuration;
//import org.apache.ibatis.session.SqlSessionFactory;
//import org.jboss.logging.Logger;
//
//import java.io.IOException;
//import java.io.Reader;
//import java.util.List;
//
//@Recorder
//public class MyBatisPlusRecorder extends MyBatisRecorder {
//    private static final Logger LOG = Logger.getLogger(MyBatisPlusRecorder.class);
//
//    public RuntimeValue<SqlSessionFactory> createSqlSessionFactory(MyBatisRuntimeConfig myBatisRuntimeConfig) {
//        LOG.info("plus createSqlSessionFactory");
//        Configuration configuration;
//
//        try {
//            Reader reader = Resources.getResourceAsReader(myBatisRuntimeConfig.xmlconfig.path);
//            XMLConfigBuilder builder = new XMLConfigBuilder(reader, myBatisRuntimeConfig.environment);
//            builder.getConfiguration().getTypeAliasRegistry().registerAlias("QUARKUS", QuarkusDataSourceFactory.class);
//            configuration = builder.parse();
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//
//        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
//        return new RuntimeValue<>(sqlSessionFactory);
//    }
//
//    @Override
//    public RuntimeValue<SqlSessionFactory> createSqlSessionFactory(
//            MyBatisRuntimeConfig myBatisRuntimeConfig,
//            MyBatisDataSourceRuntimeConfig myBatisDataSourceRuntimeConfig,
//            String dataSourceName,
//            List<String> mappers,
//            List<String> mappedTypes,
//            List<String> mappedJdbcTypes) {
//        LOG.info("plus createSqlSessionFactory");
//        Configuration configuration = createConfiguration(myBatisRuntimeConfig, myBatisDataSourceRuntimeConfig, dataSourceName);
//        addMappers(configuration, mappedTypes, mappedJdbcTypes, mappers);
//
//        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
//        return new RuntimeValue<>(sqlSessionFactory);
//    }
//
//    private void addMappers(Configuration configuration,
//            List<String> mappedTypes, List<String> mappedJdbcTypes, List<String> mappers) {
//        for (String mappedType : mappedTypes) {
//            try {
//                configuration.getTypeHandlerRegistry().register(Resources.classForName(mappedType));
//            } catch (ClassNotFoundException e) {
//                LOG.debug("Can not find the mapped type class " + mappedType);
//            }
//        }
//
//        for (String mappedJdbcType : mappedJdbcTypes) {
//            try {
//                configuration.getTypeHandlerRegistry().register(Resources.classForName(mappedJdbcType));
//            } catch (ClassNotFoundException e) {
//                LOG.debug("Can not find the mapped jdbc type class " + mappedJdbcType);
//            }
//        }
//
//        for (String mapper : mappers) {
//            try {
//                configuration.addMapper(Resources.classForName(mapper));
//            } catch (ClassNotFoundException e) {
//                LOG.debug("Can not find the mapper class " + mapper);
//            }
//        }
//    }
//
//}
