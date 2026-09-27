// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * Runtime policy for one SchemaSynchronizer invocation.
 *
 * @param schema            the managed namespace: the PostgreSQL or SQL Server schema, the MySQL/MariaDB
 *                          database, or the Oracle user. {@code [A-Za-z_][A-Za-z0-9_]*}; PostgreSQL and
 *                          Oracle fold it like an unquoted identifier, MySQL/MariaDB and SQL Server
 *                          require the spelling the server reports.
 * @param historyTable      change-set history table in that namespace, folded like an unquoted identifier
 * @param advisoryLockId    legacy lock key. The synchronization lock is derived from
 *                          {@code (schema, historyTable)} on every dialect; this id only selects the
 *                          additional lock that 1.x instances took (PostgreSQL advisory
 *                          key, Oracle DBMS_LOCK id), which 2.0 keeps acquiring so that a rolling
 *                          upgrade still excludes older instances. Keep it equal to the value those
 *                          instances used (default {@code 7249031147}).
 * @param dryRun            plan without executing DDL, change sets, or verificationSql
 * @param failOnPending     fail with {@link SchemaDefinitionException} when unsafe differences remain
 * @param requireDefinition fail when the classpath definition is missing instead of skipping
 */
public record SchemaSynchronizerOptions(
        String schema,
        String historyTable,
        long advisoryLockId,
        boolean dryRun,
        boolean failOnPending,
        boolean requireDefinition
) {
    /** Schema {@code public}, history table {@code schema_synchronizer_history}, fail on pending, require the definition. */
    public static SchemaSynchronizerOptions defaults() {
        return new SchemaSynchronizerOptions("public", "schema_synchronizer_history", 7_249_031_147L,
                false, true, true);
    }

    /**
     * Validates the names.
     *
     * @throws SchemaDefinitionException when {@code schema} or {@code historyTable} is not an identifier
     */
    public SchemaSynchronizerOptions {
        // Allow SQL Server / Oracle lengths at construction; PostgreSQL/MySQL enforce
        // their shorter limit when synchronizing against a live dialect.
        try {
            schema = SqlIdentifiers.requireIdentifierPreservingCase(
                    schema, "schema", SqlIdentifiers.EXTENDED_MAX_LENGTH);
            historyTable = SqlIdentifiers.requireIdentifier(
                    historyTable, "history table", SqlIdentifiers.EXTENDED_MAX_LENGTH);
        } catch (IllegalArgumentException invalid) {
            throw new SchemaDefinitionException(invalid.getMessage(), invalid);
        }
    }
}
