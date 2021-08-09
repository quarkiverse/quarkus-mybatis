package io.quarkiverse.mybatis.runtime;

import org.apache.ibatis.session.Configuration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;

public class ConfigurationBuilder {

    public Configuration configuration() {
        return new MybatisConfiguration();
    }

}
