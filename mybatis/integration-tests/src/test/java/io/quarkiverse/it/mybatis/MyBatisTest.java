package io.quarkiverse.it.mybatis;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MyBatisTest extends BaseMyBatisTestCase {

    @Test
    void test() {
        runTest();
    }

    @Test
    void lateCachedStatementPreservesTransactionAndResultIsolation() {
        io.restassured.RestAssured.given().when().get("/mybatis/cache-registration")
                .then().statusCode(200).body(org.hamcrest.Matchers.is("ok"));
    }

    @Test
    void reuseCursorRetainsTimeoutAndIndependentStatements() {
        io.restassured.RestAssured.given().when().get("/mybatis/deferred-query/cursor")
                .then().statusCode(200).body(org.hamcrest.Matchers.is("ok"));
    }

    @Test
    void deferredLoaderRetainsTimeoutAndOwningTransaction() {
        io.restassured.RestAssured.given().when().get("/mybatis/deferred-query/loader")
                .then().statusCode(200).body(org.hamcrest.Matchers.is("ok"));
    }

    @Test
    void injectedTypeHandlersRetainGenericTypeAndRegistryIdentity() {
        io.restassured.RestAssured.given().when().get("/mybatis/type-handlers")
                .then().statusCode(200).body(org.hamcrest.Matchers.is("ok"));
    }
}
