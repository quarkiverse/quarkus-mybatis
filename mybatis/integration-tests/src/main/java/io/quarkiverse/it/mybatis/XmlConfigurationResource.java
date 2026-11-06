package io.quarkiverse.it.mybatis;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;

@Path("/mybatis/xml-configuration")
public class XmlConfigurationResource {

    @Inject
    @Named("xmlconfig")
    Instance<SqlSessionFactory> xmlFactory;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public User verify() {
        if (!xmlFactory.isResolvable()) {
            throw new IllegalStateException("XML SqlSessionFactory is unavailable");
        }
        SqlSessionFactory factory = xmlFactory.get();
        Configuration configuration = factory.getConfiguration();
        if (!(configuration instanceof MyBatisConfiguration)) {
            throw new IllegalStateException("XML configuration does not use MyBatisConfiguration");
        }
        Environment environment = configuration.getEnvironment();
        if (environment == null || !"development".equals(environment.getId())
                || !(environment.getTransactionFactory() instanceof JdbcTransactionFactory)) {
            throw new IllegalStateException("XML development/JDBC environment was not loaded");
        }
        Set<Class<?>> handlerTypes = configuration.getTypeHandlerRegistry().getTypeHandlers().stream()
                .map(Object::getClass)
                .filter(type -> type.getName().startsWith(UuidTypeHandler.class.getPackageName() + "."))
                .collect(Collectors.toSet());
        if (!handlerTypes.equals(Set.of(UuidTypeHandler.class, UuidJdbcTypeHandler.class))) {
            throw new IllegalStateException("Unexpected XML package-scanned type handlers: " + handlerTypes);
        }
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(UserMapper.class).getUser(1);
        }
    }
}
