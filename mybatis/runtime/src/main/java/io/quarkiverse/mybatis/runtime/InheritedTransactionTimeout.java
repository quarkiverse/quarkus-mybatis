package io.quarkiverse.mybatis.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.transaction.Transaction;

/** Keeps a cursor transaction's resources while honoring the owning transaction's remaining timeout. */
final class InheritedTransactionTimeout implements Transaction {
    private final Transaction delegate;
    private final Transaction owner;

    InheritedTransactionTimeout(Transaction delegate, Transaction owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.getConnection();
    }

    @Override
    public Integer getTimeout() throws SQLException {
        Integer timeout = delegate.getTimeout();
        Integer ownerTimeout = owner.getTimeout();
        // Do not relax a stricter limit provided by a custom cursor transaction.
        // JDBC uses zero for no limit. Keep null/zero boxed to avoid null unboxing.
        if (timeout == null) {
            return ownerTimeout;
        }
        if (ownerTimeout == null) {
            return timeout;
        }
        if (timeout == 0) {
            return ownerTimeout;
        }
        if (ownerTimeout == 0) {
            return timeout;
        }
        return Math.min(timeout, ownerTimeout);
    }

    @Override
    public void commit() throws SQLException {
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        delegate.rollback();
    }

    @Override
    public void close() throws SQLException {
        delegate.close();
    }
}
