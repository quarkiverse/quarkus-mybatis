package io.quarkiverse.it.mp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
//@MapperDataSource("derby")
public interface DerbyUserMapper {
    @Select("select count(*) from users")
    int getUserCount();
}
