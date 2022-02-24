package io.quarkiverse.it.mybatis;

import static org.hamcrest.core.Is.is;

import java.util.UUID;

import io.restassured.RestAssured;

public class BaseMyBatisTestCase {

    public void runTest() {
        RestAssured.when().get("/mybatis/user/1").then()
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));

        RestAssured.when().get("/mybatis/user/dynamic/1").then()
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));

        RestAssured.given().param("id", "5")
                .param("name", "New User")
                .param("externalId", UUID.fromString("9b036c98-eb1d-4580-a8bb-1115d7a3cd44"))
                .post("/mybatis/user")
                .then().body(is("1"));

        RestAssured.when().delete("/mybatis/user/3").then()
                .body(is("1"));

        RestAssured.when().get("/mybatis/book/1").then()
                .body(is(
                        "{\"id\":1,\"author\":{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"},\"title\":\"Test Title\"}"));
        RestAssured.when().get("/mybatis/book/xmlMapper/1").then()
                .body(is(
                        "{\"id\":1,\"author\":{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"},\"title\":\"Test Title\"}"
                ));
        RestAssured.when().get("/mybatis/user/xmlMapper/1").then()
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));

    }
}
