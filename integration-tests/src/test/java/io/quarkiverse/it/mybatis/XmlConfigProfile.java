package io.quarkiverse.it.mybatis;

import io.quarkus.test.junit.QuarkusTestProfile;

public class XmlConfigProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
        return "xml";
    }
}