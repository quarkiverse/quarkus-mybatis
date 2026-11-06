package io.quarkiverse.mybatis.plus.test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.StringReader;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisXMLConfigBuilder;

import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusConfiguration;
import io.quarkiverse.mybatis.plus.runtime.MyBatisPlusConfigurationFactory;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;

class CacheRegistrationTest {
    @Test
    void plusFactoryProtectsLateStatementsWithoutReplacingExistingMappings() {
        verify(new MyBatisPlusConfigurationFactory().createConfiguration());
    }

    @Test
    void xmlConfigurationPreservesPlusRegistrationHooks() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
                  "https://mybatis.org/dtd/mybatis-3-config.dtd">
                <configuration/>
                """;
        Configuration configuration = new MybatisXMLConfigBuilder(MyBatisPlusConfiguration.class,
                new StringReader(xml), null, null).parse();
        verify(configuration);
    }

    private static void verify(Configuration configuration) {
        assertInstanceOf(MyBatisPlusConfiguration.class, configuration);
        Cache cache = new PerpetualCache("shared");
        configuration.addCache(cache);
        addStatement(configuration, "one.read", cache);
        new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration), null);
        MappedStatement original = configuration.getMappedStatement("one.read");

        addStatement(configuration, "two.read", cache);

        assertSame(original, configuration.getMappedStatement("one.read"));
        assertNotSame(cache, configuration.getMappedStatement("two.read").getCache());
        assertSame(original.getCache(), configuration.getMappedStatement("two.read").getCache());
    }

    private static void addStatement(Configuration configuration, String id, Cache cache) {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT).cache(cache).useCache(true).build());
    }
}
