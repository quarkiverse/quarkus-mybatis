package io.quarkiverse.it.mybatis;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

import static org.hamcrest.core.Is.is;

@QuarkusTest
class MultipleDataSourcesTest {
    @Test
    public void test() {
        RestAssured.when().get("/mybatis/user/count/h2").then()
                  .body(is("3"));

        RestAssured.when().get("/mybatis/user/count/derby").then()
                  .body(is("4"));
    }
}
