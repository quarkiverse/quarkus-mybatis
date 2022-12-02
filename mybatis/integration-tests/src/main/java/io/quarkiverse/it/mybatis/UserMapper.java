package io.quarkiverse.it.mybatis;

import java.util.UUID;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.cursor.Cursor;

@Mapper
@CacheNamespace(readWrite = false)
public interface UserMapper {

    @Select("select * from users where id = #{id}")
    User getUser(Integer id);

    @Insert("insert into users (id, name, externalId) values (#{id}, #{name}, #{externalId,jdbcType=OTHER})")
    Integer createUser(@Param("id") Integer id, @Param("name") String name, @Param("externalId") UUID externalId);

    @Delete("delete from users where id = #{id}")
    Integer removeUser(Integer id);

    @SelectProvider(type = SqlProviderAdapter.class, method = "select")
    User selectOne(Integer id);

    @Select("select count(*) from users")
    int getUserCount();

    @Select("select name from users")
    Cursor<String> selectCursor();

    User findById(Integer id);
}
