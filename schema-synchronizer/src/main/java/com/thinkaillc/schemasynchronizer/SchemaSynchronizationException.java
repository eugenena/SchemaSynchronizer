// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * Base type of every failure the public {@link SchemaSynchronizer} API reports. It is unchecked;
 * catch the subclass that matches the decision you need to make:
 * <ul>
 *   <li>{@link SchemaLockUnavailableException}: another synchronizer holds the lock; retry later.</li>
 *   <li>{@link SchemaDefinitionException}: the definition is invalid, a change set violates the
 *       SQL policy, or the live schema needs manual work; retrying does not help.</li>
 *   <li>{@link SchemaDatabaseException}: the database rejected a statement or the connection
 *       failed; {@link #getCause()} is the driver's {@link java.sql.SQLException}.</li>
 * </ul>
 * Any other {@link RuntimeException} escaping the API is a defect in this library.
 */
public class SchemaSynchronizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message description of the failure */
    public SchemaSynchronizationException(String message) {
        super(message);
    }

    /**
     * @param message description of the failure
     * @param cause   underlying failure, kept as {@link #getCause()}
     */
    public SchemaSynchronizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
