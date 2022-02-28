package io.quarkiverse.it.mybatis.plus;

import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

@Path("/mybatis/plus")
public class MyBatisPlusResource {
    private Logger logger = LoggerFactory.getLogger(MyBatisPlusResource.class);
    @Inject
    UserMapper userMapper;

    @Path("/user/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User getUser(@PathParam("id") Integer id) {
        return userMapper.selectById(id);
    }

    @Path("/user")
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Integer createUser(@FormParam("id") Integer id, @FormParam("name") String name,
            @FormParam("externalId") UUID externalId) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setExternalId(externalId);
        return userMapper.insert(user);
    }

    @Path("/user/{id}")
    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Integer removeUser(@PathParam("id") Integer id) {
        return userMapper.deleteById(id);
    }

    @Path("/user/count/h2")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Long getUserCount() {
        return userMapper.selectCount(new QueryWrapper<>(new User()));
    }

    @Path("/user/page/{page}/{pageSize}")
    @GET
    public Page<User> list(@PathParam("page") Integer page, @PathParam("pageSize") Integer pageSize) {
        return userMapper.selectPage(new Page<User>(page, pageSize), null);
    }
}
