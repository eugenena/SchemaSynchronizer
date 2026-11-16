// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.List;

/**
 * A change set failed while it was executing. The outcomes recorded before the failure stay
 * reachable through {@link #outcomes()}, so adoption reports survive failed runs: everything
 * before the failing change set is known to have been applied, adopted, or recorded.
 *
 * <p>The failed change set is the last entry in {@code outcomes()} with the status
 * {@link ChangeSetOutcome.Status#FAILED}.
 */
public class ChangeSetExecutionException extends SchemaSynchronizationException {

    private final List<ChangeSetOutcome> outcomes;

    /** Carries the outcomes recorded up to and including the failed change set. */
    public ChangeSetExecutionException(String message, List<ChangeSetOutcome> outcomes, Throwable cause) {
        super(message, cause);
        this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /** Outcomes for every change set handled before the run aborted, newest last. */
    public List<ChangeSetOutcome> outcomes() {
        return outcomes;
    }

    /**
     * A copy of this failure whose outcomes are prefixed by an earlier phase's outcomes, for
     * dialects where that phase's work is already committed when a later phase aborts (2.3.0+).
     * The copy keeps this exception as its cause and re-attaches its suppressed errors, so
     * rollback, lock-release, and connection-restore failures reported on the original survive
     * the merge.
     */
    public ChangeSetExecutionException withPriorOutcomes(List<ChangeSetOutcome> prior) {
        if (prior == null || prior.isEmpty()) {
            return this;
        }
        List<ChangeSetOutcome> combined = new ArrayList<>(prior);
        combined.addAll(outcomes);
        ChangeSetExecutionException merged = new ChangeSetExecutionException(getMessage(), combined, this);
        for (Throwable suppressed : getSuppressed()) {
            SchemaExceptions.suppress(merged, suppressed);
        }
        return merged;
    }
}
