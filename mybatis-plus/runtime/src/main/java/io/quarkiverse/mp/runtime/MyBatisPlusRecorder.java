package io.quarkiverse.mp.runtime;

import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.jboss.logging.Logger;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MyBatisPlusRecorder {
    private static final Logger LOG = Logger.getLogger(MyBatisPlusRecorder.class);

    public RuntimeValue<SqlSessionFactoryBuilder> getMybatisSqlSessionFactoryBuilder() {
        LOG.info("getMybatisSqlSessionFactoryBuilder");
        return new RuntimeValue<>(new SqlSessionFactoryBuilder());
    }

    public RuntimeValue<ConfigurationPlusBuilder> getMybatisConfiguration() {
        LOG.info("getMybatisConfiguration");
        return new RuntimeValue<>(new ConfigurationPlusBuilder());
    }

}
