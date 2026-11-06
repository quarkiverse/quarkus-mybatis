package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;

public class TypeHandlerScopeTest {
    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(UuidTypeHandler.class, UuidJdbcTypeHandler.class, UserMapper.class, User.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:type_handler_scope")
            .overrideConfigKey("quarkus.datasource.username", "sa");

    @Inject
    UuidTypeHandler handler;

    @Inject
    UuidJdbcTypeHandler jdbcHandler;

    @Inject
    UserMapper mapper;

    @Inject
    SqlSessionFactory factory;

    @Test
    void handlersExposeRegistryInstancesWithoutClientProxies() {
        assertEquals(Singleton.class, Arc.container().instance(UuidTypeHandler.class).getBean().getScope());
        assertEquals(Singleton.class, Arc.container().instance(UuidJdbcTypeHandler.class).getBean().getScope());
        assertSame(UuidTypeHandler.class, handler.getClass());
        assertSame(UuidJdbcTypeHandler.class, jdbcHandler.getClass());
        assertEquals(UUID.class, handler.getRawType());
        assertEquals(UUID.class, jdbcHandler.getRawType());
        TypeHandlerRegistry registry = factory.getConfiguration().getTypeHandlerRegistry();
        assertSame(handler, registry.getMappingTypeHandler(UuidTypeHandler.class));
        assertSame(jdbcHandler, registry.getMappingTypeHandler(UuidJdbcTypeHandler.class));
    }

    @Test
    void mappersAndFactoriesKeepApplicationScope() {
        assertEquals(ApplicationScoped.class, Arc.container().instance(UserMapper.class).getBean().getScope());
        assertEquals(ApplicationScoped.class, Arc.container().instance(SqlSessionFactory.class).getBean().getScope());
        assertInstanceOf(ClientProxy.class, mapper);
        assertInstanceOf(ClientProxy.class, factory);
    }
}
