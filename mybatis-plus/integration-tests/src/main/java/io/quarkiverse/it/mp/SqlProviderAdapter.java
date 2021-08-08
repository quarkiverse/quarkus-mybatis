package io.quarkiverse.it.mp;

public class SqlProviderAdapter {
    public static String select() {
        return "select * from users where id = #{id}";
    }
}
