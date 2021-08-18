package io.quarkiverse.mybatis.plus.runtime;

import org.apache.ibatis.session.Configuration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;

import io.quarkiverse.mybatis.runtime.ConfigurationFactory;

public class MyBatisPlusConfigurationFactory implements ConfigurationFactory {
    @Override
    public Configuration createConfiguration() {
        return new MybatisConfiguration();
    }
}
