// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * The schema definition cannot be applied as written: it is malformed, a change set violates the
 * non-destructive SQL policy, an applied change set was edited, the live schema conflicts with the
 * declaration (for example a table whose name differs only by case on a case-sensitive backend),
 * or unsafe differences need manual resolution while {@code failOnPending} is set. Retrying
 * without changing the definition or the database gives the same result.
 *
 * <p>{@link #getCause()} holds the internal validation failure when there is one.
 */
public class SchemaDefinitionException extends SchemaSynchronizationException {
    private static final long serialVersionUID = 1L;

    /** @param message description of the rejected definition or schema state */
    public SchemaDefinitionException(String message) {
        super(message);
    }

    /**
     * @param message description of the rejected definition or schema state
     * @param cause   underlying validation failure, kept as {@link #getCause()}
     */
    public SchemaDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
