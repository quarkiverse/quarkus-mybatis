package io.quarkiverse.it.mybatis.plus;

import static org.hamcrest.core.Is.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class MyBatisPlusTest {

    @Test
    public void test() {
        RestAssured.when().get("/mybatis/plus/user/1").then()
                .body(is("{\"id\":1,\"name\":\"Test User1\",\"externalId\":\"ccb16b65-8924-4c3f-8c55-681d85a16e79\"}"));
    }
}
