package io.quarkiverse.mybatis.plus.runtime;

import java.io.Reader;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

import com.baomidou.mybatisplus.core.MybatisXMLConfigBuilder;

import io.quarkiverse.mybatis.runtime.XMLConfigDelegateBuilder;
import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;

public class MyBatisPlusXMLConfigDelegateBuilder implements XMLConfigDelegateBuilder {
    private MybatisXMLConfigBuilder builder;
    private MyBatisRuntimeConfig config;

    public MyBatisPlusXMLConfigDelegateBuilder() {

    }

    @Override
    public void setConfig(MyBatisRuntimeConfig config) {
        this.config = config;
    }

    @Override
    public Configuration getConfiguration() throws Exception {
        return getBuilder().getConfiguration();
    }

    @Override
    public Configuration parse() throws Exception {
        return getBuilder().parse();
    }

    private MybatisXMLConfigBuilder getBuilder() throws Exception {
        if (builder == null) {
            Reader reader = Resources.getResourceAsReader(config.xmlconfig().path());
            builder = new MybatisXMLConfigBuilder(reader, config.environment());
        }
        return builder;
    }
}
