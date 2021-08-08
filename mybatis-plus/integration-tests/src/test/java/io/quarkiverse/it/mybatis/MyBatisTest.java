package io.quarkiverse.it.mybatis;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MyBatisTest extends BaseMyBatisTestCase {

    @Test
    void test() {
        runTest();
    }
}
