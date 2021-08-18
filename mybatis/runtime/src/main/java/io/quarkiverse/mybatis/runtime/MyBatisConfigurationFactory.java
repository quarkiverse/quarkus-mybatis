package io.quarkiverse.mybatis.runtime;

import org.apache.ibatis.session.Configuration;

public class MyBatisConfigurationFactory implements ConfigurationFactory {
    @Override
    public Configuration createConfiguration() {
        return new Configuration();
    }
}
