package io.quarkiverse.it.mybatis.plus;

import java.io.Serializable;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("USERS")
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
