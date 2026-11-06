package io.quarkiverse.it.mybatis;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandlerRegistry;

@Path("/mybatis/type-handlers")
public class TypeHandlerResource {

    @Inject
    UuidTypeHandler uuidHandler;

    @Inject
    UuidJdbcTypeHandler jdbcHandler;

    @Inject
    SqlSessionFactory factory;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String verify() {
        if (uuidHandler.getRawType() != UUID.class || jdbcHandler.getRawType() != UUID.class) {
            throw new IllegalStateException("Injected type handlers lost their generic type");
        }
        TypeHandlerRegistry registry = factory.getConfiguration().getTypeHandlerRegistry();
        if (uuidHandler != registry.getMappingTypeHandler(UuidTypeHandler.class)
                || jdbcHandler != registry.getMappingTypeHandler(UuidJdbcTypeHandler.class)) {
            throw new IllegalStateException("Injected type handlers do not match the registered handler instances");
        }
        return "ok";
    }
}
