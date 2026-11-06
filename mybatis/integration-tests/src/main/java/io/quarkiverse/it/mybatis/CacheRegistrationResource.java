package io.quarkiverse.it.mybatis;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import io.quarkiverse.mybatis.runtime.MyBatisConfiguration;
import io.quarkiverse.mybatis.runtime.TransactionalSqlSession;

/** Exercised by the same HTTP test in JVM and native mode. Uses no runtime mapper class loading. */
@Path("/mybatis/cache-registration")
public class CacheRegistrationResource {
    @Inject
    SqlSessionFactory factory;

    @Inject
    TransactionManager transactions;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String verify() throws Exception {
        MyBatisConfiguration configuration = new MyBatisConfiguration(factory.getConfiguration().getEnvironment());
        addRead(configuration, "uncached", null);
        TransactionalSqlSession session = new TransactionalSqlSession(new SqlSessionFactoryBuilder().build(configuration),
                transactions);
        Cache cache = new PerpetualCache("runtime-registered");
        transactions.begin();
        try {
            if (!Integer.valueOf(1).equals(session.selectOne("uncached"))) {
                throw new IllegalStateException("Uncached query failed");
            }
            configuration.addCache(cache);
            addRead(configuration, "cached", cache);
            List<Integer> result = session.selectList("cached");
            result.add(999);
            if (cache.getSize() != 0) {
                throw new IllegalStateException("Cache published before JTA commit");
            }
            transactions.commit();
        } finally {
            if (transactions.getTransaction() != null) {
                transactions.rollback();
            }
        }
        if (cache.getSize() != 1 || !List.of(1).equals(session.selectList("cached"))) {
            throw new IllegalStateException("Result isolation or Native cache fallback failed");
        }
        return "ok";
    }

    private static void addRead(MyBatisConfiguration configuration, String id, Cache cache) {
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, id,
                new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                .cache(cache).useCache(cache != null)
                .resultMaps(List.of(new ResultMap.Builder(configuration, id + "-result", Integer.class, List.of()).build()))
                .build());
    }
}
