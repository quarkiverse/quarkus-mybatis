package io.quarkiverse.it.mybatis.plus;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends CustomBaseMapper<User> {
}
