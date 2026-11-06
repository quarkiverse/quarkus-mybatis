package io.quarkiverse.mybatis.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.transaction.Transaction;

/** Borrows the owning transaction's connection and remaining timeout without taking over its completion. */
final class BorrowedTransaction implements Transaction {
    private final Transaction owner;

    BorrowedTransaction(Transaction owner) {
        this.owner = owner;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return owner.getConnection();
    }

    @Override
    public Integer getTimeout() throws SQLException {
        return owner.getTimeout();
    }

    @Override
    public void commit() {
        // Only the owning session completes this transaction.
    }

    @Override
    public void rollback() {
        // A deferred executor must not roll back its owning session.
    }

    @Override
    public void close() {
        // The owning session retains the JDBC connection.
    }
}
