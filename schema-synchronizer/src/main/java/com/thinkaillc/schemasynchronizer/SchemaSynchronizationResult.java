// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.List;

/**
 * Outcome of one synchronization, for deployment logs, dry runs, and tests.
 *
 * @param changeSetsApplied change sets executed (or adopted through verificationSql) and recorded in
 *                          history; 0 in a dry run
 * @param tablesCreated     tables created from their {@code createSql} (in a dry run: would be created)
 * @param columnsAdded      columns added (in a dry run: would be added)
 * @param columnsAltered    non-destructive column alterations executed (in a dry run: would be executed)
 * @param plannedSql        statements a dry run would have executed; empty otherwise
 * @param pendingSql        unsafe or destructive differences left for an operator, as reviewed SQL
 *                          (identifiers quoted) or {@code --} comments; never executed
 * @param dryRun            whether this was a dry run, in which case nothing was changed
 * @param lockReleased      false when releasing a session-level synchronization lock failed: the work
 *                          above is still committed, but the session may hold the lock until the
 *                          connection closes, so close or discard the connection. PostgreSQL locks are
 *                          transaction-scoped and always report true; with a caller's transaction
 *                          ({@code autoCommit=false}) they are held until the caller commits or rolls back.
 * @param cleanupWarnings   failures while releasing the lock or restoring the connection's auto-commit
 *                          mode after the outcome was decided, as {@code "ExceptionType: message"}; a
 *                          failed auto-commit restore is reported here with {@code lockReleased} true
 */
public record SchemaSynchronizationResult(
        int changeSetsApplied,
        int tablesCreated,
        int columnsAdded,
        int columnsAltered,
        List<String> plannedSql,
        List<String> pendingSql,
        boolean dryRun,
        boolean lockReleased,
        List<String> cleanupWarnings
) {
    /** Copies the lists; a null list is empty. */
    public SchemaSynchronizationResult {
        plannedSql = plannedSql == null ? List.of() : List.copyOf(plannedSql);
        pendingSql = pendingSql == null ? List.of() : List.copyOf(pendingSql);
        cleanupWarnings = cleanupWarnings == null ? List.of() : List.copyOf(cleanupWarnings);
    }

    /** A result of a run that was not a dry run and cleaned up normally. */
    public SchemaSynchronizationResult(int changeSetsApplied, int tablesCreated, int columnsAdded,
                                       int columnsAltered, List<String> plannedSql, List<String> pendingSql) {
        this(changeSetsApplied, tablesCreated, columnsAdded, columnsAltered, plannedSql, pendingSql,
                false, true, List.of());
    }

    /**
     * Whether anything was applied. In a dry run the table and column counts are what would be
     * applied, so check {@link #dryRun()} first.
     */
    public boolean changed() {
        return changeSetsApplied + tablesCreated + columnsAdded + columnsAltered > 0;
    }
}
