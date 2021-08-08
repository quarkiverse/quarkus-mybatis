package io.quarkiverse.it.mybatis;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(XmlConfigProfile.class)
public class MyBatisXmlConfigTest extends BaseMyBatisTestCase {

    @Test
    void test() {
        runTest();
    }
}
