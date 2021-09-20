package io.quarkiverse.mybatis.runtime;

import org.apache.ibatis.session.Configuration;

import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;

public interface XMLConfigDelegateBuilder {
    void setConfig(MyBatisRuntimeConfig config);

    Configuration getConfiguration() throws Exception;

    Configuration parse() throws Exception;
}
