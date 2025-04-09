package io.quarkiverse.it.mybatis.plus;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

import java.net.URL;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
public class MybatisPlusServiceTest {

    @TestHTTPEndpoint(MybatisPlusServiceResource.class)
    @TestHTTPResource
    URL apiUrl;

    @Test
    public void test() {
        when().get(apiUrl + "/user/1").then()
                .statusCode(200)
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));

        when().get(apiUrl + "/user/page/1/2").then().assertThat()
                .body("records[0].name", is("Test User1"))
                .body("records[1].name", is("Test User2"))
                .body("total", is(3))
                .body("size", is(2))
                .body("current", is(1))
                .body("pages", is(2));

        given().param("id", "205")
                .param("name", "New User")
                .param("externalId", UUID.fromString("9b036c98-eb1d-4580-a8bb-1115d7a3cd44"))
                .post(apiUrl + "/user")
                .then().body(is("true"));

        when().get(apiUrl + "/user/205").then()
                .body("createTime", not(0))
                .body("updateTime", not(0));

        when().delete(apiUrl + "/user/205").then()
                .body(is("true"));

        when().get(apiUrl + "/user/count/h2").then()
                .body(is("3"));

        User user1 = new User();
        user1.setId(200);
        user1.setName("Test User 200");
        user1.setExternalId(UUID.randomUUID());

        User user2 = new User();
        user2.setId(201);
        user2.setName("Test User 201");
        user2.setExternalId(UUID.randomUUID());

        List<User> users = List.of(user1, user2);
        given()
                .contentType(ContentType.JSON)
                .body(users)
                .post(apiUrl + "/users")
                .then().body(is("true"));

        when().get(apiUrl + "/user/count/h2").then()
                .body(is("5"));

        when().delete(apiUrl + "/user/200").then()
                .body(is("true"));

        when().delete(apiUrl + "/user/201").then()
                .body(is("true"));
    }
}
