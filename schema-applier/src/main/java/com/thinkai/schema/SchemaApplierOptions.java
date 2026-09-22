package com.thinkai.schema;

/** Runtime policy for one SchemaApplier invocation. */
public record SchemaApplierOptions(
        String schema,
        String historyTable,
        long advisoryLockId,
        boolean dryRun,
        boolean failOnPending,
        boolean requireDefinition
) {
    public static SchemaApplierOptions defaults() {
        return new SchemaApplierOptions("public", "thinkai_schema_history", 7_249_031_147L,
                false, true, true);
    }

    public SchemaApplierOptions {
        schema = SqlIdentifiers.requireIdentifier(schema, "schema");
        historyTable = SqlIdentifiers.requireIdentifier(historyTable, "history table");
    }
}
