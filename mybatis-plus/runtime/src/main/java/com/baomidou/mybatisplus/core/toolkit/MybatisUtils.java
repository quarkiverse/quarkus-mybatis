package com.baomidou.mybatisplus.core.toolkit;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;

import com.baomidou.mybatisplus.core.handlers.IJsonTypeHandler;
import com.baomidou.mybatisplus.core.override.MybatisMapperProxy;

import io.quarkus.arc.ClientProxy;

public class MybatisUtils {

    /**
     * Instantiate Json type processors
     * <p>
     * 1.Subclasses need to contain constructs (Class, Field)
     * 2.If there is no structure or no attribute field, the default structure (Class) is used for instantiation
     * </p>
     *
     * @param typeHandler {@link IJsonTypeHandler}
     * @param javaTypeClass java type information
     * @param field attribute field
     * @return Instantiated type processor
     */
    public static TypeHandler<?> newJsonTypeHandler(Class<? extends TypeHandler<?>> typeHandler, Class<?> javaTypeClass,
            Field field) {
        TypeHandler<?> result = null;
        if (IJsonTypeHandler.class.isAssignableFrom(typeHandler)) {
            if (field != null) {
                try {
                    result = typeHandler.getConstructor(Class.class, Field.class).newInstance(javaTypeClass, field);
                } catch (ReflectiveOperationException e) {
                    // ignore
                }
            }
            if (result == null) {
                try {
                    result = typeHandler.getConstructor(Class.class).newInstance(javaTypeClass);
                } catch (ReflectiveOperationException ex) {
                    throw new TypeException("Failed invoking constructor for handler " + typeHandler, ex);
                }
            }
        }
        return result;
    }

    /**
     * get SqlSessionFactory
     *
     * @param mybatisMapperProxy {@link MybatisMapperProxy}
     * @return SqlSessionFactory
     * @since 3.5.7
     */
    public static SqlSessionFactory getSqlSessionFactory(MybatisMapperProxy<?> mybatisMapperProxy) {
        SqlSession sqlSession = mybatisMapperProxy.getSqlSession();
        if (sqlSession instanceof DefaultSqlSession) {
            return GlobalConfigUtils.getGlobalConfig(mybatisMapperProxy.getSqlSession().getConfiguration())
                    .getSqlSessionFactory();
        }
        Field declaredField;
        try {
            declaredField = sqlSession.getClass().getDeclaredField("sqlSessionFactory");
            declaredField.setAccessible(true);
            return (SqlSessionFactory) declaredField.get(sqlSession);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get proxy implementation
     *
     * @param mapper mapper class
     * @return Proxy implementation
     * @since 3.5.7
     */
    public static MybatisMapperProxy<?> getMybatisMapperProxy(Object mapper) {
        // Fast return
        if (mapper instanceof MybatisMapperProxy) {
            return (MybatisMapperProxy<?>) mapper;
        }

        Object result = mapper;
        //Quarkus's proxy detection
        if (result instanceof ClientProxy) {
            result = ((ClientProxy) result).arc_contextualInstance();
        }

        // Handling JDK dynamic proxies (if MyBatis still uses JDK proxies)
        if (result != null && Proxy.isProxyClass(result.getClass())) {
            result = Proxy.getInvocationHandler(result);
        }

        if (result instanceof MybatisMapperProxy) {
            return (MybatisMapperProxy<?>) result;
        }
        throw new RuntimeException("Unable to get MybatisMapperProxy in Quarkus: " + mapper);
    }

}
