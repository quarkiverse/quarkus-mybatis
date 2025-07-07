package io.quarkiverse.mybatis.plus.runtime;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import io.quarkiverse.mybatis.plus.MyBatisPlusConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MyBatisPlusRecorder {
    private static final Logger LOG = Logger.getLogger(MyBatisPlusRecorder.class);

    public void initSqlSession(RuntimeValue<SqlSessionFactory> sqlSessionFactory, MyBatisPlusConfig config) {
        Configuration configuration = sqlSessionFactory.getValue().getConfiguration();

        if (config.pageEnabled()) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            configuration.addInterceptor(interceptor);
        }
        String classMetaObjectHandler = null;
        if (config.metaObjectHandler().isPresent()) {
            classMetaObjectHandler = config.metaObjectHandler().get();
        }
        if (config.globalConfig().metaObjectHandler().isPresent()) {
            classMetaObjectHandler = config.globalConfig().metaObjectHandler().get();
        }
        if (classMetaObjectHandler != null) {
            try {
                MetaObjectHandler handler = (MetaObjectHandler) Resources.classForName(classMetaObjectHandler)
                        .getDeclaredConstructor().newInstance();
                GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(handler);
            } catch (Exception e) {
                LOG.warn("Can not initialize metaObjectHandler " + config.globalConfig().metaObjectHandler().get());
            }
        }
    }

    public Consumer<Configuration> addCustomSqlInjector(MyBatisPlusConfig config) {
        return configuration -> {
            String classSqlInjector = null;
            if (config.sqlInjector().isPresent()) {
                classSqlInjector = config.sqlInjector().get();
            }
            if (config.globalConfig().sqlInjector().isPresent()) {
                classSqlInjector = config.globalConfig().sqlInjector().get();
            }
            if (classSqlInjector != null) {
                try {
                    ISqlInjector sqlInjector = (ISqlInjector) Resources.classForName(classSqlInjector)
                            .getDeclaredConstructor().newInstance();
                    GlobalConfigUtils.getGlobalConfig(configuration).setSqlInjector(sqlInjector);
                } catch (Exception e) {
                    LOG.warn("Can not initialize sqlInjector " + config.globalConfig().sqlInjector().get());
                }
            }
        };
    }

    public Consumer<Configuration> addCustomIdentifierGenerator(MyBatisPlusConfig config) {
        return configuration -> {
            if (config.globalConfig().identifierGenerator().isPresent()) {
                String classIdentifierGenerator = config.globalConfig().identifierGenerator().get();
                try {
                    IdentifierGenerator customIdentifierGenerator = (IdentifierGenerator) Resources
                            .classForName(classIdentifierGenerator).getDeclaredConstructor().newInstance();
                    GlobalConfigUtils.getGlobalConfig(configuration).setIdentifierGenerator(customIdentifierGenerator);
                } catch (Exception e) {
                    LOG.warn("Can not initialize identifierGenerator " + classIdentifierGenerator);
                }
            }
        };
    }

    public Consumer<Configuration> setDbConfigIdType(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().idType().isPresent()) {
                String classIdType = dbConfig.get().idType().get();
                try {
                    IdType customerIdType = IdType.valueOf(classIdType);
                    GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setIdType(customerIdType);
                } catch (Exception e) {
                    LOG.warn("Can not initialize dbConfig.idType " + classIdType);
                }
            }
        };
    }

    public Consumer<Configuration> setDbConfigTablePrefix(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().tablePrefix().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setTablePrefix(dbConfig.get().tablePrefix().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigSchema(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().schema().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setSchema(dbConfig.get().schema().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigColumnFormat(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().columnFormat().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setColumnFormat(dbConfig.get().columnFormat().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigTableFormat(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().tableFormat().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setTableFormat(dbConfig.get().tableFormat().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigPropertyFormat(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().propertyFormat().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setPropertyFormat(dbConfig.get().propertyFormat().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigTableUnderline(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && !dbConfig.get().tableUnderline()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setTableUnderline(false);
            }
        };
    }

    public Consumer<Configuration> setDbConfigCapitalMode(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().capitalMode()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setCapitalMode(true);
            }
        };
    }

    public Consumer<Configuration> setDbConfigKeyGenerator(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().keyGenerator().isPresent()) {
                String classKeyGenerators = dbConfig.get().keyGenerator().get();
                ArrayList<IKeyGenerator> objects = new ArrayList<>();
                for (String classKeyGenerator : classKeyGenerators.split(",")) {
                    try {
                        IKeyGenerator customKeyGenerator = (IKeyGenerator) Resources
                                .classForName(classKeyGenerator).getDeclaredConstructor().newInstance();
                        objects.add(customKeyGenerator);
                    } catch (Exception e) {
                        LOG.warn("Can not initialize dbConfig.keyGenerator " + classKeyGenerator);
                    }
                }
                if (!objects.isEmpty()) {
                    GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setKeyGenerators(objects);
                }
            }
        };
    }

    public Consumer<Configuration> setDbConfigLogicDeleteField(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && dbConfig.get().logicDeleteField().isPresent()) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setLogicDeleteField(dbConfig.get().logicDeleteField().get());
            }
        };
    }

    public Consumer<Configuration> setDbConfigLogicDeleteValue(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && !"1".equals(dbConfig.get().logicDeleteValue())) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setLogicDeleteValue(dbConfig.get().logicDeleteValue());
            }
        };
    }

    public Consumer<Configuration> setDbConfigLogicNotDeleteValue(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && !"0".equals(dbConfig.get().logicNotDeleteValue())) {
                GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig()
                        .setLogicNotDeleteValue(dbConfig.get().logicNotDeleteValue());
            }
        };
    }

    public Consumer<Configuration> setDbConfigFieldStrategy(MyBatisPlusConfig config) {
        return configuration -> {
            Optional<MyBatisPlusConfig.DbConfig> dbConfig = config.globalConfig().dbConfig();
            if (dbConfig.isPresent() && !"NOT_NULL".equals(dbConfig.get().insertStrategy())) {
                String strategyName = dbConfig.get().insertStrategy();
                try {
                    FieldStrategy customerStrategy = FieldStrategy.valueOf(strategyName);
                    GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setInsertStrategy(customerStrategy);
                } catch (Exception e) {
                    LOG.warn("Can not initialize dbConfig.insertStrategy " + strategyName);
                }
            }

            if (dbConfig.isPresent() && !"NOT_NULL".equals(dbConfig.get().updateStrategy())) {
                String strategyName = dbConfig.get().updateStrategy();
                try {
                    FieldStrategy customerStrategy = FieldStrategy.valueOf(strategyName);
                    GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setUpdateStrategy(customerStrategy);
                } catch (Exception e) {
                    LOG.warn("Can not initialize dbConfig.updateStrategy " + strategyName);
                }
            }

            if (dbConfig.isPresent() && !"NOT_NULL".equals(dbConfig.get().whereStrategy())) {
                String strategyName = dbConfig.get().whereStrategy();
                try {
                    FieldStrategy customerStrategy = FieldStrategy.valueOf(strategyName);
                    GlobalConfigUtils.getGlobalConfig(configuration).getDbConfig().setWhereStrategy(customerStrategy);
                } catch (Exception e) {
                    LOG.warn("Can not initialize dbConfig.whereStrategy " + strategyName);
                }
            }
        };
    }
}
