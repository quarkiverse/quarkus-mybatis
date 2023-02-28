package io.quarkiverse.it.mybatis.plus;

import java.util.Date;

import org.apache.ibatis.reflection.MetaObject;
import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

public class MyObjectHandler implements MetaObjectHandler {
    private static final Logger LOG = Logger.getLogger(MyObjectHandler.class);

    @Override
    public void insertFill(final MetaObject metaObject) {
        this.setFieldValByName("createTime", new Date().getTime(), metaObject);
        this.setFieldValByName("updateTime", new Date().getTime(), metaObject);
    }

    @Override
    public void updateFill(final MetaObject metaObject) {
        this.setFieldValByName("updateTime", new Date().getTime(), metaObject);
    }
}
