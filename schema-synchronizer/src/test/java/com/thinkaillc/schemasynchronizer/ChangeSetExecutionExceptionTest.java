// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ChangeSetExecutionExceptionTest {

    @Test
    void withPriorOutcomesPrependsTheCompletedPhase() {
        ChangeSetExecutionException failure = new ChangeSetExecutionException(
                "schema change 'after' failed: boom",
                List.of(ChangeSetOutcome.failed("after", 1, 0, 5)), new SQLException("boom"));

        ChangeSetExecutionException merged = failure.withPriorOutcomes(
                List.of(ChangeSetOutcome.executed("before", 3, 0, 9)));

        assertThat(merged).hasMessage(failure.getMessage());
        assertThat(merged.outcomes())
                .extracting(ChangeSetOutcome::changeSetId, ChangeSetOutcome::status)
                .containsExactly(
                        tuple("before", ChangeSetOutcome.Status.EXECUTED),
                        tuple("after", ChangeSetOutcome.Status.FAILED));
    }

    @Test
    void withPriorOutcomesKeepsSuppressedCleanupErrors() {
        ChangeSetExecutionException failure = new ChangeSetExecutionException(
                "schema change 'after' failed: boom",
                List.of(ChangeSetOutcome.failed("after", 1, 0, 5)), new SQLException("boom"));
        SQLException rollback = new SQLException("rollback failed");
        SQLException lockRelease = new SQLException("lock release failed");
        failure.addSuppressed(rollback);
        failure.addSuppressed(lockRelease);

        ChangeSetExecutionException merged = failure.withPriorOutcomes(
                List.of(ChangeSetOutcome.executed("before", 3, 0, 9)));

        // The merge must not drop what rollback and cleanup attached to the original failure.
        assertThat(merged.getSuppressed()).containsExactly(rollback, lockRelease);
        assertThat(merged.getCause()).isSameAs(failure);
        assertThat(merged.getCause().getCause()).isInstanceOf(SQLException.class);
    }

    @Test
    void withPriorOutcomesWithoutPriorReturnsTheSameFailure() {
        ChangeSetExecutionException failure = new ChangeSetExecutionException(
                "schema change 'one' failed: boom",
                List.of(ChangeSetOutcome.failed("one", 1, 0, 5)), new SQLException("boom"));

        assertThat(failure.withPriorOutcomes(List.of())).isSameAs(failure);
        assertThat(failure.withPriorOutcomes(null)).isSameAs(failure);
    }
}