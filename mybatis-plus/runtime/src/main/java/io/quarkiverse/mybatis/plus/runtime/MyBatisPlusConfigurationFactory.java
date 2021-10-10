package io.quarkiverse.mybatis.plus.runtime;

import org.apache.ibatis.session.Configuration;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.reflect.GenericTypeUtils;

import io.quarkiverse.mybatis.runtime.ConfigurationFactory;

public class MyBatisPlusConfigurationFactory implements ConfigurationFactory {
    private static final Logger LOG = Logger.getLogger(MyBatisPlusConfigurationFactory.class);

    @Override
    public Configuration createConfiguration() {
        GenericTypeUtils.setGenericTypeResolver(new GenericTypeResolverImpl());
        return new MybatisConfiguration();
    }
}
