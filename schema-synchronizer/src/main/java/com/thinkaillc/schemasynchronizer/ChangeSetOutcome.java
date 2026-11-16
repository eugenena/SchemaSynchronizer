// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.time.Duration;

/**
 * What happened to one change set during a synchronization. Adoption test suites assert on
 * these instead of counting applied change sets, because a legacy database can satisfy a
 * change set through its {@code verificationSql} (adopted), through history
 * ({@code alreadyRecorded}), or by executing it ({@code executed}) - all without changing
 * correctness.
 *
 * @param changeSetId the change set identifier from the definition
 * @param status how the change set was resolved
 * @param executedStatements statements the run executed (0 for adopted, recorded, and skipped)
 * @param skippedStatements statements skipped because the object already exists
 * @param durationMs execution time in milliseconds (0 when nothing was executed)
 */
public record ChangeSetOutcome(
        String changeSetId,
        Status status,
        int executedStatements,
        int skippedStatements,
        long durationMs) {

    /** How a change set was resolved by a synchronization. */
    public enum Status {
        /** The change set statements were executed and it was recorded in history. */
        EXECUTED,
        /** The change set verificationSql proved the schema change already present, so it was
         * recorded without executing its statements. This is how adoption of an existing
         * database works when the objects already match. */
        VERIFIED_ADOPTED,
        /** The change set was already recorded in the history table from an earlier run. */
        ALREADY_RECORDED,
        /** Dry run: the change set would execute, and its statements are in plannedSql. */
        SKIPPED_DRY_RUN,
        /** Executing the change set failed; the synchronization aborted and the failure is
         * attached to the original exception (see ChangeSetExecutionException). */
        FAILED
    }

    /** Convenience factory for runs that executed statements. */
    public static ChangeSetOutcome executed(String changeSetId, int executedStatements, long durationMs) {
        return executed(changeSetId, executedStatements, 0, durationMs);
    }

    /** Convenience factory for runs that executed statements and skipped already-present ones (2.3.0+). */
    public static ChangeSetOutcome executed(String changeSetId, int executedStatements,
                                            int skippedStatements, long durationMs) {
        return new ChangeSetOutcome(changeSetId, Status.EXECUTED, executedStatements, skippedStatements, durationMs);
    }

    /** Convenience factory for the verification-adoption path. */
    public static ChangeSetOutcome verifiedAdopted(String changeSetId) {
        return new ChangeSetOutcome(changeSetId, Status.VERIFIED_ADOPTED, 0, 0, 0L);
    }

    /** Convenience factory for change sets already recorded in history. */
    public static ChangeSetOutcome alreadyRecorded(String changeSetId) {
        return new ChangeSetOutcome(changeSetId, Status.ALREADY_RECORDED, 0, 0, 0L);
    }

    /** Convenience factory for dry runs. */
    public static ChangeSetOutcome skippedDryRun(String changeSetId) {
        return new ChangeSetOutcome(changeSetId, Status.SKIPPED_DRY_RUN, 0, 0, 0L);
    }

    /** Convenience factory for a failed change set. */
    public static ChangeSetOutcome failed(String changeSetId, int executedStatements, long durationMs) {
        return failed(changeSetId, executedStatements, 0, durationMs);
    }

    /** Convenience factory for a failed change set that had already skipped statements (2.3.0+). */
    public static ChangeSetOutcome failed(String changeSetId, int executedStatements,
                                          int skippedStatements, long durationMs) {
        return new ChangeSetOutcome(changeSetId, Status.FAILED, executedStatements, skippedStatements, durationMs);
    }

    /** Execution time as a duration, for logs. */
    public Duration duration() {
        return Duration.ofMillis(durationMs);
    }
}
