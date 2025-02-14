package io.quarkiverse.mybatis.runtime;

import java.io.Reader;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;

public class MyBatisXMLConfigDelegateBuilder implements XMLConfigDelegateBuilder {
    private XMLConfigBuilder builder;
    private MyBatisRuntimeConfig config;

    public MyBatisXMLConfigDelegateBuilder() {

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

    private XMLConfigBuilder getBuilder() throws Exception {
        if (builder == null) {
            Reader reader = Resources.getResourceAsReader(config.xmlconfig().path());
            builder = new XMLConfigBuilder(reader, config.environment());
        }
        return builder;
    }
}
