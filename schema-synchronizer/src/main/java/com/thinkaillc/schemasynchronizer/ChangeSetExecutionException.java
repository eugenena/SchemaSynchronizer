// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

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
}
