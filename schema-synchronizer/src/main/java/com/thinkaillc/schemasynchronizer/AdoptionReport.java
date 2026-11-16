// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adoption test assertions over a synchronization result. A legacy database can satisfy a
 * change set three ways without changing correctness: its objects already match the change
 * set (verified-adopted), the change set is already recorded in history, or the change set
 * executes now. Counting applied change sets cannot tell those apart; this report can.
 *
 * <p>Build from a successful run with {@link #from(SchemaSynchronizationResult)}, or from a
 * failed run's {@link ChangeSetExecutionException#outcomes()}.
 */
public final class AdoptionReport {

    private final SchemaSynchronizationResult result;
    private final List<ChangeSetOutcome> outcomes;

    private AdoptionReport(SchemaSynchronizationResult result, List<ChangeSetOutcome> outcomes) {
        this.result = result;
        this.outcomes = List.copyOf(outcomes);
    }

    /** Wraps a successful synchronization result. */
    public static AdoptionReport from(SchemaSynchronizationResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        return new AdoptionReport(result, result.changeSets());
    }

    /** Wraps the partial outcomes of a run that aborted on a change set. */
    public static AdoptionReport from(ChangeSetExecutionException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure is required");
        }
        return new AdoptionReport(null, failure.outcomes());
    }

    /** Every recorded outcome, in application order. */
    public List<ChangeSetOutcome> all() {
        return outcomes;
    }

    /** The synchronization result, or null when built from a failed run. */
    public SchemaSynchronizationResult result() {
        return result;
    }

    /** Outcomes grouped by status. */
    public Map<ChangeSetOutcome.Status, List<ChangeSetOutcome>> byStatus() {
        return outcomes.stream().collect(Collectors.groupingBy(ChangeSetOutcome::status,
                () -> new EnumMap<>(ChangeSetOutcome.Status.class), Collectors.toList()));
    }

    /** Count per status, for assertions that used to be magic numbers. */
    public Map<ChangeSetOutcome.Status, Long> counts() {
        return outcomes.stream().collect(Collectors.groupingBy(ChangeSetOutcome::status,
                () -> new EnumMap<>(ChangeSetOutcome.Status.class), Collectors.counting()));
    }

    /** Change sets whose statements executed. */
    public List<ChangeSetOutcome> executed() {
        return byStatus(ChangeSetOutcome.Status.EXECUTED);
    }

    /** Change sets recorded through verificationSql: the schema already matched. */
    public List<ChangeSetOutcome> verifiedAdopted() {
        return byStatus(ChangeSetOutcome.Status.VERIFIED_ADOPTED);
    }

    /** Change sets already recorded in history from an earlier run. */
    public List<ChangeSetOutcome> alreadyRecorded() {
        return byStatus(ChangeSetOutcome.Status.ALREADY_RECORDED);
    }

    /** Change sets a dry run would execute. */
    public List<ChangeSetOutcome> skippedDryRun() {
        return byStatus(ChangeSetOutcome.Status.SKIPPED_DRY_RUN);
    }

    /** Change sets that failed, normally empty (a failure aborts the run). */
    public List<ChangeSetOutcome> failed() {
        return byStatus(ChangeSetOutcome.Status.FAILED);
    }

    /** The outcome of one change set, when the definition contains it. */
    public Optional<ChangeSetOutcome.Status> statusOf(String changeSetId) {
        return outcomes.stream().filter(o -> o.changeSetId().equals(changeSetId))
                .map(ChangeSetOutcome::status).findFirst();
    }

    /** Ids recorded in the history table by this run: executed plus verified-adopted. */
    public List<String> recordedIds() {
        return outcomes.stream()
                .filter(o -> o.status() == ChangeSetOutcome.Status.EXECUTED
                        || o.status() == ChangeSetOutcome.Status.VERIFIED_ADOPTED)
                .map(ChangeSetOutcome::changeSetId)
                .toList();
    }

    private List<ChangeSetOutcome> byStatus(ChangeSetOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).toList();
    }
}
