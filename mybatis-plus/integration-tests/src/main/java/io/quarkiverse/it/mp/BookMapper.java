package io.quarkiverse.it.mp;

import org.apache.ibatis.annotations.*;

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
