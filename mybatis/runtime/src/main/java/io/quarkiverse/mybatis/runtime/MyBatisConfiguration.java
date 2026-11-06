package io.quarkiverse.mybatis.runtime;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Configuration that protects late registrations before making them visible to executors. */
@RegisterForReflection(methods = false, fields = false)
public class MyBatisConfiguration extends Configuration {
    public MyBatisConfiguration() {
    }

    public MyBatisConfiguration(Environment environment) {
        super(environment);
    }

    @Override
    public void addMappedStatement(MappedStatement statement) {
        CacheRegistrationSupport.addMappedStatement(statement, super::addMappedStatement);
    }

    @Override
    public void addCache(Cache cache) {
        CacheRegistrationSupport.addCache(this, cache, super::addCache);
    }
}
