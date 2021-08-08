package io.quarkiverse.it.mp;

import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/mybatis")
public class MyBatisResource {

    @Inject
    UserMapper userMapper;

    @Path("/user/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User getUser(@PathParam("id") Integer id) {
        return userMapper.selectById(id);
    }

    @Path("/user/dynamic/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User getDynamicUser(@PathParam("id") Integer id) {
        return userMapper.selectOne(id);
    }

    @Path("/user")
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Integer createUser(@FormParam("id") Integer id, @FormParam("name") String name,
            @FormParam("externalId") UUID externalId) {
        return userMapper.createUser(id, name, externalId);
    }

    @Path("/user/{id}")
    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Integer removeUser(@PathParam("id") Integer id) {
        return userMapper.removeUser(id);
    }

    @Path("/user/count/h2")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public int getUserCount() {
        return userMapper.getUserCount();
    }

}
