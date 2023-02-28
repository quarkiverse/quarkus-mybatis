package io.quarkiverse.mybatis.runtime;

import org.apache.ibatis.session.Configuration;

public interface ConfigurationFactory {
    Configuration createConfiguration();

    default boolean isOverrideSetting() {
        return false;
    }
}
