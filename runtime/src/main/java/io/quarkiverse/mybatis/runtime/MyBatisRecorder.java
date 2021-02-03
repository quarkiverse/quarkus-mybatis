package io.quarkiverse.mybatis.runtime;

import java.io.PrintWriter;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.runtime.DataSources;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MyBatisRecorder {
    private static final Logger LOG = Logger.getLogger(MyBatisRecorder.class);

    public RuntimeValue<SqlSessionFactory> createSqlSessionFactory(
            String environment, String transactionFactory, String dataSourceName, String mapUnderscoreToCamelCase,
            List<String> mappers) {
        Configuration configuration = new Configuration();

        TransactionFactory factory;
        if (transactionFactory.equals("MANAGED")) {
            factory = new ManagedTransactionFactory();
        } else {
            factory = new JdbcTransactionFactory();
        }

        if ("true".equals(mapUnderscoreToCamelCase)) {
            configuration.setMapUnderscoreToCamelCase(true);
        }

        Environment.Builder environmentBuilder = new Environment.Builder(environment)
                .transactionFactory(factory)
                .dataSource(new QuarkusDataSource(dataSourceName));

        configuration.setEnvironment(environmentBuilder.build());
        for (String mapper : mappers) {
            try {
                configuration.addMapper(Resources.classForName(mapper));
            } catch (ClassNotFoundException e) {
                LOG.debug("Can not find the mapper class " + mapper);
            }
        }

        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        return new RuntimeValue<>(sqlSessionFactory);
    }

    public RuntimeValue<SqlSessionManager> createSqlSessionManager(RuntimeValue<SqlSessionFactory> sqlSessionFactory) {
        SqlSessionManager sqlSessionManager = SqlSessionManager.newInstance(sqlSessionFactory.getValue());
        return new RuntimeValue<>(sqlSessionManager);
    }

    public Supplier<Object> MyBatisMapperSupplier(String name, RuntimeValue<SqlSessionManager> sqlSessionManager) {
        return () -> {
            try {
                return sqlSessionManager.getValue().getMapper(Resources.classForName(name));
            } catch (ClassNotFoundException e) {
                return null;
            }
        };
    }

    public void runInitialSql(RuntimeValue<SqlSessionFactory> sqlSessionFactory, String sql) {
        try (SqlSession session = sqlSessionFactory.getValue().openSession()) {
            Connection conn = session.getConnection();
            Reader reader = Resources.getResourceAsReader(sql);
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setLogWriter(null);
            runner.runScript(reader);
            reader.close();
        } catch (Exception e) {
            LOG.warn("Error executing SQL Script " + sql);
        }
    }

    public void register(RuntimeValue<SqlSessionFactory> sqlSessionFactory, BeanContainer beanContainer) {
        beanContainer.instance(MyBatisProducers.class).setSqlSessionFactory(sqlSessionFactory.getValue());
    }
}

class QuarkusDataSource implements DataSource {
    private String dataSourceName;
    private AgroalDataSource dataSource;

    public QuarkusDataSource(String dataSourceName) {
        this.dataSourceName = dataSourceName;
        this.dataSource = null;
    }

    private DataSource getDataSource() {
        if (dataSource == null) {
            dataSource = DataSources.fromName(dataSourceName);
        }
        return dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    @Override
    public Connection getConnection(String user, String passwd) throws SQLException {
        return getDataSource().getConnection(user, passwd);
    }

    @Override
    public <T> T unwrap(Class<T> aClass) throws SQLException {
        return getDataSource().unwrap(aClass);
    }

    @Override
    public boolean isWrapperFor(Class<?> aClass) throws SQLException {
        return getDataSource().isWrapperFor(aClass);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return getDataSource().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter printWriter) throws SQLException {
        getDataSource().setLogWriter(printWriter);
    }

    @Override
    public void setLoginTimeout(int timeout) throws SQLException {
        getDataSource().setLoginTimeout(timeout);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return getDataSource().getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return getDataSource().getParentLogger();
    }
}
