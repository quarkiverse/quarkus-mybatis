package io.quarkiverse.it.mybatis.plus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;

public class CustomKeyGenerator implements IKeyGenerator {

    @Override
    public String executeSql(String incrementerName) {
        return "select nextval('" + incrementerName + "')";
    }

    @Override
    public DbType dbType() {
        return DbType.H2;
    }
}
