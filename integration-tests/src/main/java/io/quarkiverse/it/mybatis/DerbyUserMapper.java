package io.quarkiverse.it.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;

@Mapper
@MapperDataSource("derby")
public interface DerbyUserMapper {
    @Select("select count(*) from users")
    int getUserCount();
}
