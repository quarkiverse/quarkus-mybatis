package io.quarkiverse.it.mybatis;

import static org.hamcrest.core.Is.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

@QuarkusTest
@TestProfile(XmlConfigProfile.class)
public class MyBatisXmlConfigTest extends BaseMyBatisTestCase {

    @Test
    void test() {
        runTest();
    }

    @Test
    void xmlConfigurationFactoryLoadsEnvironmentAndMapper() {
        RestAssured.when().get("/mybatis/xml-configuration").then()
                .statusCode(200)
                .body("id", is(1))
                .body("name", is("Test User1"))
                .body("externalId", is("ccb16b65-8924-4c3f-8c55-681d85a16e79"));
    }
}
