package io.quarkiverse.it.mybatis.plus;

import static org.hamcrest.core.Is.is;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class MyBatisPlusTest {

    @Test
    public void test() {
        RestAssured.when().get("/mybatis/plus/user/1").then()
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));

        RestAssured.given().param("id", "5")
                .param("name", "New User")
                .param("externalId", UUID.fromString("9b036c98-eb1d-4580-a8bb-1115d7a3cd44"))
                .post("/mybatis/plus/user")
                .then().body(is("1"));

        RestAssured.when().delete("/mybatis/plus/user/3").then()
                .body(is("1"));

        RestAssured.when().get("/mybatis/plus/user/count/h2").then()
                .body(is("3"));
    }
}
