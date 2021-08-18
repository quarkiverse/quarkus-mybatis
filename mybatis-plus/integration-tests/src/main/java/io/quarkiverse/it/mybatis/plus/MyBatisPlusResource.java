package io.quarkiverse.it.mybatis.plus;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/mybatis/plus")
public class MyBatisPlusResource {
    @Inject
    UserMapper userMapper;

    @Path("/user/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User getUser(@PathParam("id") Integer id) {
        return userMapper.selectById(id);
    }
}
