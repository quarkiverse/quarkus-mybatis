package io.quarkiverse.it.mybatis.plus;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/mybatis/plus/service")
public class MybatisPlusServiceResource {
    @Inject
    UserServiceImpl userService;

    @Path("/user/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User getUser(@PathParam("id") Integer id) {
        return userService.getById(id);
    }

    @Path("/user")
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Boolean createUser(@FormParam("id") Integer id, @FormParam("name") String name,
                              @FormParam("externalId") UUID externalId) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setExternalId(externalId);
        return userService.save(user);
    }

    @Path("/users")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Boolean createUsers(List<User> users) {
        return userService.saveBatch(users);
    }

    @Path("/user/{id}")
    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Boolean removeUser(@PathParam("id") Integer id) {
        return userService.removeById(id);
    }

    @Path("/user/count/h2")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Long getUserCount() {
        return userService.count();
    }

    @Path("/user/page/{page}/{pageSize}")
    @GET
    public Page<User> list(@PathParam("page") Integer page, @PathParam("pageSize") Integer pageSize) {
        return userService.page(new Page<User>(page, pageSize), null);
    }
}
