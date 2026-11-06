package io.quarkiverse.mybatis.plus.runtime;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.mapping.MappedStatement;

import com.baomidou.mybatisplus.core.MybatisConfiguration;

import io.quarkiverse.mybatis.runtime.CacheRegistrationSupport;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(methods = false, fields = false)
public class MyBatisPlusConfiguration extends MybatisConfiguration {
    @Override
    public void addMappedStatement(MappedStatement statement) {
        CacheRegistrationSupport.addMappedStatement(statement, super::addMappedStatement);
    }

    @Override
    public void addCache(Cache cache) {
        CacheRegistrationSupport.addCache(this, cache, super::addCache);
    }
}
