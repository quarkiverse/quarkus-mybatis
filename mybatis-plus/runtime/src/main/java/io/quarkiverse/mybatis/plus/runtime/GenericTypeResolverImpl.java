package io.quarkiverse.mybatis.plus.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baomidou.mybatisplus.core.toolkit.reflect.IGenericTypeResolver;

import ru.vyarus.java.generics.resolver.GenericsResolver;
import ru.vyarus.java.generics.resolver.context.GenericsContext;

public class GenericTypeResolverImpl implements IGenericTypeResolver {
    private final Logger logger = LoggerFactory.getLogger(GenericTypeResolverImpl.class);

    @Override
    public Class<?>[] resolveTypeArguments(Class<?> clazz, Class<?> genericIfc) {
        GenericsContext context = GenericsResolver.resolve(clazz)
                .type(genericIfc);
        return context.generics().toArray(new Class[] {});
    }
}
