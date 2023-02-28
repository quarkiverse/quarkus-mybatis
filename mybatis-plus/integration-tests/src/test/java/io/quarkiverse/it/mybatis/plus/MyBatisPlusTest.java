package io.quarkiverse.it.mybatis.plus;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

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

        RestAssured.when().get("/mybatis/plus/user/page/1/2").then().assertThat()
                .body("records[0].name", is("Test User1"))
                .body("records[1].name", is("Test User2"))
                .body("total", is(3))
                .body("size", is(2))
                .body("current", is(1))
                .body("pages", is(2));

        RestAssured.given().param("id", "5")
                .param("name", "New User")
                .param("externalId", UUID.fromString("9b036c98-eb1d-4580-a8bb-1115d7a3cd44"))
                .post("/mybatis/plus/user")
                .then().body(is("1"));

        RestAssured.when().get("/mybatis/plus/user/5").then()
                .body("createTime", not(0))
                .body("updateTime", not(0));

        RestAssured.when().delete("/mybatis/plus/user/3").then()
                .body(is("1"));

        RestAssured.when().get("/mybatis/plus/user/count/h2").then()
                .body(is("3"));
    }
}
