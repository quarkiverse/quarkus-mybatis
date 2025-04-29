package com.baomidou.mybatisplus.core.metadata;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSession;

import com.baomidou.mybatisplus.core.override.MybatisMapperProxy;

public class MapperProxyMetadata {
    private final SqlSession sqlSession;

    private final Class<?> mapperInterface;

    public MapperProxyMetadata(MetaObject metaObject) {
        MybatisMapperProxy<?> mapperProxy = (MybatisMapperProxy) metaObject.getOriginalObject();
        this.mapperInterface = mapperProxy.getMapperInterface();
        this.sqlSession = mapperProxy.getSqlSession();
    }

    public Class<?> getMapperInterface() {
        return mapperInterface;
    }

    public SqlSession getSqlSession() {
        return sqlSession;
    }

    @Override
    public String toString() {
        return "MapperProxy{" +
                "mapperInterface=" + mapperInterface +
                ", sqlSession=" + sqlSession +
                '}';
    }

}
