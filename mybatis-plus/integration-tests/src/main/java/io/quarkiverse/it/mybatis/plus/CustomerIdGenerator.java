package io.quarkiverse.it.mybatis.plus;

import org.jboss.logging.Logger;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

public class CustomerIdGenerator implements IdentifierGenerator {
    private static final Logger LOG = Logger.getLogger(CustomerIdGenerator.class);

    @Override
    public Number nextId(Object entity) {
        throw new RuntimeException("CustomerIdGenerator throw");
    }
}
