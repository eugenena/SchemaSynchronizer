// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * The synchronization lock for this schema and history table could not be acquired within the
 * wait limit (30 seconds), usually because another instance is synchronizing. Nothing was
 * changed; the operation is safe to retry.
 */
public class SchemaLockUnavailableException extends SchemaSynchronizationException {
    private static final long serialVersionUID = 1L;

    /** @param message description of the lock that could not be acquired */
    public SchemaLockUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message description of the lock that could not be acquired
     * @param cause   underlying failure (for example an interrupt), kept as {@link #getCause()}
     */
    public SchemaLockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
