package io.quarkiverse.mybatis.plus;

import java.io.InputStream;
import java.util.function.Consumer;

import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import com.baomidou.mybatisplus.core.toolkit.ExceptionUtils;
import com.baomidou.mybatisplus.extension.spi.CompatibleSet;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * @author defned
 * @date 2025/4/8 10:42
 */
@RegisterForReflection
public class QuarkusCompatibleSet implements CompatibleSet {

    @Override
    public SqlSession getSqlSession(SqlSessionFactory sessionFactory) {
        return Arc.container().instance(SqlSession.class).get();
    }

    @Override
    public void closeSqlSession(SqlSession session, SqlSessionFactory sessionFactory) {
        if (session != null) {
            session.close();
        }
    }

    @Override
    public boolean executeBatch(SqlSessionFactory sqlSessionFactory, Log log, Consumer<SqlSession> consumer) {
        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        try {
            consumer.accept(sqlSession);
            sqlSession.commit(true); // Quarkus 事务由 @Transactional 控制
            return true;
        } catch (Throwable t) {
            sqlSession.rollback();
            Throwable unwrapped = ExceptionUtil.unwrapThrowable(t);
            if (unwrapped instanceof PersistenceException) {
                // Quarkus 异常转换（需自定义或使用内置机制）
                throw ExceptionUtils.mpe(unwrapped);
            }
            throw ExceptionUtils.mpe(unwrapped);
        } finally {
            sqlSession.close();
        }
    }

    @Override
    public InputStream getInputStream(String path) throws Exception {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }
}
