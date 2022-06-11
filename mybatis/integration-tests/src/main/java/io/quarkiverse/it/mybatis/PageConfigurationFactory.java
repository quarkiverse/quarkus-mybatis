package io.quarkiverse.it.mybatis;

import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;

public class PageConfigurationFactory {
    public Configuration getConfiguration() {
        Configuration configuration = new Configuration();
        configuration.addInterceptor(new PageInterceptor());
        return configuration;
    }
}

class PageInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }
}