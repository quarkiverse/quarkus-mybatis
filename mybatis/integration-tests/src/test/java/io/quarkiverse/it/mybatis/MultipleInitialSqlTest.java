package io.quarkiverse.it.mybatis;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(MultipleInitialSqlTestProfile.class)
class MultipleInitialSqlTest extends BaseMyBatisTestCase {

    @Test
    void testMultipleInitialSqlFiles() {
        // This test uses the application-multisql.properties configuration
        // which specifies multiple SQL files: schema.sql,data.sql
        // The test verifies that both files are executed correctly
        runTest();
    }
}