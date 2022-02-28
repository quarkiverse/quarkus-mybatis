package io.quarkiverse.mybatis.plus.runtime;

import org.apache.ibatis.session.SqlSessionFactory;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import io.quarkiverse.mybatis.plus.MyBatisPlusConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MyBatisPlusRecorder {

    public void initSqlSession(RuntimeValue<SqlSessionFactory> sqlSessionFactory, MyBatisPlusConfig config) {
        if (config.pageEnabled) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            sqlSessionFactory.getValue().getConfiguration().addInterceptor(interceptor);
        }
    }
}
