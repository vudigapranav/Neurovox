package com.neurovox.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The SQLite database file and how connections to it are opened.
 *
 * <p>Each unit of work opens its own short-lived connection, which is simple and safe with the server's
 * worker threads. Every connection turns on foreign-key enforcement (SQLite leaves it off by default,
 * per connection) and waits up to 5 s if another connection holds the write lock.
 */
public final class Database {

    /** A piece of work that uses a connection and may throw {@link SQLException}. */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Path file;
    private final String url;

    private Database(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.url = "jdbc:sqlite:" + this.file;
    }

    /**
     * Opens (creating if needed) the database at {@code file} and brings its schema up to date.
     * Safe to call any number of times on the same file.
     */
    public static Database open(Path file) {
        Database database = new Database(file);
        try {
            Path parent = database.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create folder for " + database.file, e);
        }
        database.initialize();
        return database;
    }

    /** Applies any pending migrations. Idempotent: an up-to-date database is left unchanged. */
    public void initialize() {
        try (Connection connection = connect()) {
            try (Statement statement = connection.createStatement()) {
                // Write-ahead logging lets readers continue while a write is in progress; the setting is stored in the file.
                statement.execute("PRAGMA journal_mode = WAL");
            }
            Migrations.apply(connection);
        } catch (SQLException e) {
            throw new DataAccessException("Could not initialise the database at " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** Opens a new connection with foreign keys enforced. The caller must close it. */
    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Runs read-only work on a fresh connection. */
    public <T> T read(Work<T> work) {
        try (Connection connection = connect()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DataAccessException("Database read failed", e);
        }
    }

    /**
     * Runs work inside one transaction: everything is committed together, or, if anything throws,
     * nothing is kept.
     */
    public <T> T transaction(Work<T> work) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Database transaction failed", e);
        }
    }
}
