package io.quarkiverse.it.mybatis;

import java.io.Serializable;
import java.util.UUID;

public class User implements Serializable {
    private Integer id;
    private String name;
    private UUID externalId;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getExternalId() {
        return externalId;
    }

    public void setExternalId(UUID externalId) {
        this.externalId = externalId;
    }
}
