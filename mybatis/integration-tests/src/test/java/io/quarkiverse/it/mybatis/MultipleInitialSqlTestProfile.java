package io.quarkiverse.it.mybatis;

import io.quarkus.test.junit.QuarkusTestProfile;

public class MultipleInitialSqlTestProfile implements QuarkusTestProfile {

    @Override
    public String getConfigProfile() {
        return "multisql";
    }
}