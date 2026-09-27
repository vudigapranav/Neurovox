package com.neurovox.db;

import java.sql.SQLException;

/** Unchecked wrapper for database failures, so callers are not forced to handle {@link SQLException} everywhere. */
public final class DataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage(), cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
