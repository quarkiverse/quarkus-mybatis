package io.quarkiverse.mybatis.test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.derby.DerbyDatabaseTestResource;
import io.quarkus.test.h2.H2DatabaseTestResource;

@QuarkusTestResource(H2DatabaseTestResource.class)
@QuarkusTestResource(DerbyDatabaseTestResource.class)
public class TestResources {
}
