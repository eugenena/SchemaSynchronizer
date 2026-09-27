// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * Standalone entry point that serializes a source database to a schema definition.
 *
 * <p>Usage:
 * <pre>
 * SchemaSerializer &lt;jdbc-url&gt; &lt;user&gt; &lt;password-or--&gt; &lt;schema&gt; &lt;output-path&gt;
 * </pre>
 * Use {@code -} for the password to read {@code SCHEMA_DB_PASSWORD}.
 */
public final class SchemaSerializer {

    private SchemaSerializer() {
    }

    /**
     * Serializes the source database; see {@link SchemaSnapshotWriter#main(String[])}.
     *
     * @throws SchemaSynchronizationException (or a subclass) when serialization fails
     */
    public static void main(String[] args) {
        SchemaSnapshotWriter.main(args);
    }
}
