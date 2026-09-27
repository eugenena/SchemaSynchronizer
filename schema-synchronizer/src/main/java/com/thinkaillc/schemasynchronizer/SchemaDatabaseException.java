// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.SQLException;

/**
 * The database or driver failed while synchronizing. {@link #getCause()} is the original
 * {@link SQLException}, so {@link SQLException#getSQLState()}, {@link SQLException#getErrorCode()},
 * and chained exceptions are available unchanged. Whether a retry helps depends on the SQLState.
 */
public class SchemaDatabaseException extends SchemaSynchronizationException {
    private static final long serialVersionUID = 1L;

    /**
     * @param message description of the operation that failed
     * @param cause   the driver failure; must not be null
     */
    public SchemaDatabaseException(String message, SQLException cause) {
        super(message, java.util.Objects.requireNonNull(cause, "cause"));
    }

    /** The driver failure this exception wraps. */
    @Override
    public synchronized SQLException getCause() {
        return (SQLException) super.getCause();
    }

    /** SQLState of the wrapped driver failure, or null when the driver reported none. */
    public String getSQLState() {
        return getCause().getSQLState();
    }

    /** Vendor error code of the wrapped driver failure. */
    public int getErrorCode() {
        return getCause().getErrorCode();
    }
}
