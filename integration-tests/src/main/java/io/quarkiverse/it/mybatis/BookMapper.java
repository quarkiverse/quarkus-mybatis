package io.quarkiverse.it.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.One;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BookMapper {
    @Select("SELECT id, author_id, title from books where id = #{id}")
    @Results(value = {
            @Result(property = "id", column = "id"),
            @Result(property = "author", column = "author_id", javaType = User.class, one = @One(select = "getUser"))
    })
    Book getBook(Integer id);

    @Select("select * from users where id = #{id}")
    User getUser(Integer id);
}
