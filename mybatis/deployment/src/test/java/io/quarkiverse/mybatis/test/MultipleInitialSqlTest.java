package io.quarkiverse.mybatis.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.ibatis.session.SqlSessionFactory;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class MultipleInitialSqlTest {
    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withConfigurationResource("application-multiple-sql.properties")
            .setArchiveProducer(
                    () -> ShrinkWrap.create(JavaArchive.class)
                            .addClasses(UserMapper.class, User.class, UuidTypeHandler.class, UuidJdbcTypeHandler.class));

    @Inject
    UserMapper userMapper;

    @Inject
    SqlSessionFactory h2SqlSessionFactory;

    @Test
    public void testMultipleInitialSqlFiles() throws Exception {
        assertTrue(h2SqlSessionFactory.getConfiguration().getMapperRegistry().hasMapper(UserMapper.class));
        assertEquals("H2", h2SqlSessionFactory.openSession().getConnection().getMetaData().getDatabaseProductName());

        // This test verifies that both schema-test.sql and data-test.sql were executed
        User user = userMapper.getUser(1);
        assertEquals(user.getId(), 1);
        assertEquals(user.getName(), "Test User1");
        assertEquals(user.getExternalId(), UUID.fromString("8c5034fe-1a00-43b7-9c75-f83ef14e3507"));

        // Verify additional test data was inserted
        User user2 = userMapper.getUser(2);
        assertEquals(user2.getId(), 2);
        assertEquals(user2.getName(), "Test User2");
        assertEquals(user2.getExternalId(), UUID.fromString("0a197020-e05a-41ab-9c46-649cd432feb4"));
    }
}