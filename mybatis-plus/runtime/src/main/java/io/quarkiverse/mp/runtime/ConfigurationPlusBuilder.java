package io.quarkiverse.mp.runtime;

import org.apache.ibatis.session.Configuration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;

import io.quarkiverse.mybatis.runtime.ConfigurationBuilder;

public class ConfigurationPlusBuilder extends ConfigurationBuilder {

    @Override
    public Configuration configuration() {
        return new MybatisConfiguration();
    }

}
