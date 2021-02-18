package io.quarkiverse.it.mybatis;

public class SqlProviderAdapter {
    public static String select() {
        return "select * from users where id = #{id}";
    }
}
