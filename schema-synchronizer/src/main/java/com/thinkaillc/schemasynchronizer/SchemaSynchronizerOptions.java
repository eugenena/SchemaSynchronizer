// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/** Runtime policy for one SchemaSynchronizer invocation. */
public record SchemaSynchronizerOptions(
        String schema,
        String historyTable,
        long advisoryLockId,
        boolean dryRun,
        boolean failOnPending,
        boolean requireDefinition
) {
    public static SchemaSynchronizerOptions defaults() {
        return new SchemaSynchronizerOptions("public", "schema_synchronizer_history", 7_249_031_147L,
                false, true, true);
    }

    public SchemaSynchronizerOptions {
        // Allow SQL Server / Oracle lengths at construction; PostgreSQL/MySQL enforce
        // their shorter limit when synchronizing against a live dialect.
        schema = SqlIdentifiers.requireIdentifierPreservingCase(
                schema, "schema", SqlIdentifiers.EXTENDED_MAX_LENGTH);
        historyTable = SqlIdentifiers.requireIdentifier(
                historyTable, "history table", SqlIdentifiers.EXTENDED_MAX_LENGTH);
    }
}
