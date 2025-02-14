package io.quarkiverse.mybatis.plus.runtime;

import java.util.function.Consumer;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
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

        if (config.metaObjectHandler().isPresent()) {
            try {
                String classMetaObjectHandler = config.metaObjectHandler().get();
                MetaObjectHandler handler = (MetaObjectHandler) Resources.classForName(classMetaObjectHandler)
                        .getDeclaredConstructor().newInstance();
                GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(handler);
            } catch (Exception e) {
                LOG.warn("Can not initialize metaObjectHandler " + config.metaObjectHandler().get());
            }
        }
    }

    public Consumer<Configuration> addCustomSqlInjector(MyBatisPlusConfig config) {
        return configuration -> {
            if (config.sqlInjector().isPresent()) {
                try {
                    String classSqlInjector = config.sqlInjector().get();
                    ISqlInjector sqlInjector = (ISqlInjector) Resources.classForName(classSqlInjector)
                            .getDeclaredConstructor().newInstance();
                    GlobalConfigUtils.getGlobalConfig(configuration).setSqlInjector(sqlInjector);
                } catch (Exception e) {
                    LOG.warn("Can not initialize sqlInjector " + config.sqlInjector().get());
                }
            }
        };
    }
}
