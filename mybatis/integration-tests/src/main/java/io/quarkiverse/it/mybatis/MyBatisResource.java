package io.quarkiverse.it.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.ibatis.cursor.Cursor;

@Path("/mybatis")
public class MyBatisResource {

    @Inject
    UserMapper userMapper;

    @Inject
    BookMapper bookMapper;

    @Inject
    DerbyUserMapper derbyUserMapper;

    @Path("/user/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public User getUser(@PathParam("id") Integer id) {
        return userMapper.getUser(id);
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

    @Path("/user/count/derby")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public int getDerbyUserCount() {
        return derbyUserMapper.getUserCount();
    }

    @Path("/user/cursor")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public List<String> getUserCursor() throws Exception {
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = userMapper.selectCursor()) {
            for (String name : cursor) {
                result.add(name);
            }
        }
        return result;
    }

    @Path("/book/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Book getBook(@PathParam("id") Integer id) {
        return bookMapper.getBook(id);
    }

    @Path("/book/xmlMapper/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Book findBookById(@PathParam("id") Integer id) {
        return bookMapper.findById(id);
    }

    @Path("/user/xmlMapper/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User findUserById(@PathParam("id") Integer id) {
        return userMapper.findById(id);
    }

    @Path("/user/xmlMapper/derby/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User findDerbyUserById(@PathParam("id") Integer id) {
        return derbyUserMapper.findById(id);
    }
}
