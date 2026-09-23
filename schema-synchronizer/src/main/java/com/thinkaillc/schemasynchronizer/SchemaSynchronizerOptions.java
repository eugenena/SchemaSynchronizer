// Copyright 2026 Eugene Naoumov
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
        schema = SqlIdentifiers.requireIdentifierPreservingCase(schema, "schema");
        historyTable = SqlIdentifiers.requireIdentifier(historyTable, "history table");
    }
}
