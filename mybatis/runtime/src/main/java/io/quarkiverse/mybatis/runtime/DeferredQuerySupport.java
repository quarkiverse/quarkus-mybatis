package io.quarkiverse.mybatis.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.InterceptorChain;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.Transaction;

import io.quarkus.runtime.annotations.RegisterForProxy;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Binds the executor retained by MyBatis ResultLoader to its owning transaction.
 * Executor plugins alone cannot intercept it: CachingExecutor installs itself as
 * BaseExecutor's wrapper before Configuration applies the plugin chain.
 */
@RegisterForProxy(targets = Executor.class)
@RegisterForReflection(targets = { BaseExecutor.class, CachingExecutor.class, Configuration.class,
        InterceptorChain.class }, methods = false, ignoreNested = true)
final class DeferredQuerySupport {
    private static final ThreadLocal<Binding> creating = new ThreadLocal<>();
    private static final ThreadLocal<Object> executing = new ThreadLocal<>();
    private static final Field cacheDelegate = field(CachingExecutor.class, "delegate");
    private static final Field executorTransaction = field(BaseExecutor.class, "transaction");
    private static final Field configurationChain = field(Configuration.class, "interceptorChain");
    private static final Field chainInterceptors = field(InterceptorChain.class, "interceptors");

    interface Owner {
        boolean isClosed();

        Object query(Transaction transaction, Method method, Object[] args) throws Throwable;
    }

    static final class Binding {
        private final Configuration configuration;
        private final OwnerReference reference;
        private final boolean primary;
        private final boolean inheritTimeout;
        private Object executionTarget;

        Binding(Configuration configuration) {
            this.configuration = configuration;
            this.reference = new OwnerReference();
            this.primary = true;
            this.inheritTimeout = false;
        }

        private Binding(Binding parent, boolean inheritTimeout) {
            configuration = parent.configuration;
            reference = parent.reference;
            primary = false;
            this.inheritTimeout = inheritTimeout;
        }

        Binding fork() {
            return new Binding(this, false);
        }

        Binding forkForCursor() {
            return new Binding(this, true);
        }

        void target(Object executionTarget) {
            this.executionTarget = executionTarget;
        }

        void attach(Owner owner) {
            State state = reference.state;
            reference.state = new State(owner, state.transaction());
        }

        void detach() {
            reference.state = CLOSED_STATE;
        }
    }

    private static final class OwnerReference {
        private volatile State state = new State(null, null);
    }

    private record State(Owner owner, Transaction transaction) {
    }

    private static final Owner CLOSED = new Owner() {
        @Override
        public boolean isClosed() {
            return true;
        }

        @Override
        public Object query(Transaction transaction, Method method, Object[] args) {
            throw new IllegalStateException("Transaction completed before the lazy query could be acquired");
        }
    };
    private static final State CLOSED_STATE = new State(CLOSED, null);

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    @SuppressWarnings("unchecked")
    static void install(Configuration configuration) {
        synchronized (configuration) {
            try {
                List<Interceptor> interceptors = (List<Interceptor>) chainInterceptors
                        .get(configurationChain.get(configuration));
                if (interceptors.stream().noneMatch(i -> i instanceof BindingInterceptor binding
                        && binding.configuration == configuration)) {
                    // Bind the standard executor before application plugins can wrap it in an
                    // arbitrary decorator. Preserve the relative order of all application plugins.
                    interceptors.add(0, new BindingInterceptor(configuration));
                }
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Could not install the MyBatis lazy-loading executor binding", failure);
            }
        }
    }

    static boolean isBindingInterceptor(Interceptor interceptor) {
        return interceptor instanceof BindingInterceptor;
    }

    static Scope creating(Binding binding) {
        return enter(creating, binding);
    }

    static Scope executing(Object target) {
        return enter(executing, target);
    }

    private static <T> Scope enter(ThreadLocal<T> local, T binding) {
        T previous = local.get();
        if (binding == null) {
            local.remove();
        } else {
            local.set(binding);
        }
        return () -> {
            if (previous == null) {
                local.remove();
            } else {
                local.set(previous);
            }
        };
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static final class BindingInterceptor implements Interceptor {
        private final Configuration configuration;

        private BindingInterceptor(Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public Object plugin(Object target) {
            Binding binding = creating.get();
            if (binding == null || binding.configuration != configuration || !(target instanceof Executor delegate)) {
                return target;
            }
            // This creation scope owns one executor. Application plugins may create
            // auxiliary executors while wrapping it; those must not inherit its owner.
            creating.remove();
            try {
                Object base = delegate instanceof CachingExecutor ? cacheDelegate.get(delegate) : delegate;
                if (base instanceof BaseExecutor baseExecutor) {
                    if (binding.primary) {
                        // Refresh only when opening/reopening the owning session. Cursor and
                        // short-lived query bindings must retain the owner's transaction contract.
                        State state = binding.reference.state;
                        binding.reference.state = new State(state.owner(), delegate.getTransaction());
                    } else if (binding.inheritTimeout && binding.reference.state.transaction() != null) {
                        // openSession(Connection) creates a new transaction without the owner's
                        // timeout. Keep its factory-specific resources and completion behavior.
                        executorTransaction.set(baseExecutor, new InheritedTransactionTimeout(
                                baseExecutor.getTransaction(), binding.reference.state.transaction()));
                    }
                    Executor loaderExecutor = (Executor) Proxy.newProxyInstance(Executor.class.getClassLoader(),
                            new Class<?>[] { Executor.class }, (proxy, method, args) -> {
                                State state = binding.reference.state;
                                Owner owner = state.owner();
                                if (owner != null) {
                                    // Deferred children may retain an executor whose short-lived query has
                                    // already closed. Their lifetime is the owning session's lifetime.
                                    if (method.getName().equals("isClosed")) {
                                        return owner.isClosed();
                                    }
                                    if (method.getName().equals("query")
                                            && (executing.get() != binding.executionTarget || delegate.isClosed())) {
                                        return owner.query(state.transaction(), method, args);
                                    }
                                }
                                try {
                                    return method.invoke(delegate, args);
                                } catch (Throwable failure) {
                                    throw ExceptionUtil.unwrapThrowable(failure);
                                }
                            });
                    baseExecutor.setExecutorWrapper(loaderExecutor);
                }
                return target;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Could not bind the MyBatis lazy-loading executor", failure);
            }
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }
    }
}
