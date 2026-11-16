// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeSetExecutorTest {

    @Test
    void mariaDbRequiresVerificationForRecoveringAnImplicitCommit() throws Exception {
        Connection connection = connectionWithoutHistory();
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "one", "unsafe replay", List.of("ALTER TABLE items ADD COLUMN note TEXT"));

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(
                connection, List.of(change), mariaOptions(), DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires verificationSql");
    }

    @Test
    void mariaDbRejectsUnverifiedMultiStatementChangesBeforeExecution() throws Exception {
        Connection connection = connectionWithoutHistory();
        Statement statement = mock(Statement.class);
        ResultSet verification = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT FALSE")).thenReturn(verification);
        when(verification.next()).thenReturn(true, false);
        when(verification.getBoolean(1)).thenReturn(false);
        when(verification.wasNull()).thenReturn(false);
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "two", "partial commit risk",
                List.of("ALTER TABLE items ADD COLUMN note TEXT", "UPDATE items SET note = ''"),
                "SELECT FALSE");

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(
                connection, List.of(change), mariaOptions(), DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one statement");
    }

    @Test
    void verificationSqlMayUseTableAliasesButNotChangeTheSessionNamespace() {
        SchemaDefinition.ChangeSet aliased = new SchemaDefinition.ChangeSet(
                "aliased", "verify with joins", List.of("CREATE TABLE widgets (id INT)"),
                "SELECT count(*) = 1 FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid "
                        + "WHERE r.relname = 'widgets'",
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA);
        assertThat(new ChangeSetExecutor().validate(List.of(aliased), mariaOptions(), DatabaseDialect.POSTGRESQL))
                .hasSize(1);

        SchemaDefinition.ChangeSet sessionChange = new SchemaDefinition.ChangeSet(
                "session", "verify switches schema", List.of("CREATE TABLE widgets (id INT)"),
                "SELECT set_config('search_path', 'other', false) IS NOT NULL",
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA);
        assertThatThrownBy(() -> new ChangeSetExecutor().validate(List.of(sessionChange), mariaOptions(),
                DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
    }

    @Test
    void validateRejectsCrossSchemaChangeSetSql() {
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "cross", "bad", List.of("UPDATE other.items SET note = 'x'"));

        assertThatThrownBy(() -> new ChangeSetExecutor().validate(List.of(change), mariaOptions(),
                DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
    }

    @Test
    void dryRunDoesNotExecuteVerificationSql() throws Exception {
        Connection connection = connectionWithoutHistory();
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        AtomicInteger verificationCalls = new AtomicInteger();
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            verificationCalls.incrementAndGet();
            throw new AssertionError("dry-run must not run verificationSql: " + invocation.getArgument(0));
        });

        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "dry", "preview",
                List.of("ALTER TABLE items ADD COLUMN note TEXT"),
                "SELECT count(*) > 0 FROM items");
        SchemaSynchronizerOptions dryRun = new SchemaSynchronizerOptions(
                "test", "schema_synchronizer_history", 7_249_031_147L, true, true, true);

        ChangeSetExecutor.Result result = new ChangeSetExecutor().apply(
                connection, List.of(change), dryRun, DatabaseDialect.POSTGRESQL);

        assertThat(result.applied()).isZero();
        assertThat(result.plannedSql()).containsExactly("ALTER TABLE items ADD COLUMN note TEXT");
        assertThat(verificationCalls.get()).isZero();
        verify(statement, never()).execute(anyString());

        // The pre-DDL checks treat an unrecorded change set as unverified instead of querying.
        SchemaDefinition.ChangeSet twoStatements = new SchemaDefinition.ChangeSet("two", "preview",
                List.of("ALTER TABLE items ADD COLUMN a INT", "ALTER TABLE items ADD COLUMN b INT"),
                "SELECT count(*) > 0 FROM items");
        assertThat(new ChangeSetExecutor().unappliedUnverified(connection, List.of(change, twoStatements), dryRun,
                DatabaseDialect.MARIADB)).containsExactly(change, twoStatements);
        new ChangeSetExecutor().validateHistory(connection, List.of(twoStatements), dryRun, DatabaseDialect.MARIADB);
        assertThat(verificationCalls.get()).isZero();
    }

    /** A real in-memory H2 connection, so history, verification, and duplicate skips all behave. */
    private Connection h2() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:cse_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** H2's default schema exists without setup, unlike the MariaDB test options. */
    private SchemaSynchronizerOptions h2Options() {
        return new SchemaSynchronizerOptions(
                "public", "schema_synchronizer_history", 7_249_031_147L, false, true, true);
    }

    private static void execute(Connection connection, String... sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String one : sql) {
                statement.execute(one);
            }
        }
    }

    @Test
    void outcomesKeepApplicationOrderWhenRecordedChangeSetsInterleave() throws Exception {
        SchemaDefinition.ChangeSet recorded = new SchemaDefinition.ChangeSet(
                "recorded", "applied by an earlier run", List.of("CREATE TABLE items_recorded (id INT)"));
        SchemaDefinition.ChangeSet fresh = new SchemaDefinition.ChangeSet(
                "fresh", "applied now", List.of("CREATE TABLE items_fresh (id INT)"));
        ChangeSetExecutor executor = new ChangeSetExecutor();
        try (Connection connection = h2()) {
            executor.apply(connection, List.of(recorded), h2Options(), DatabaseDialect.H2);

            ChangeSetExecutor.Result result = executor.apply(connection,
                    List.of(fresh, recorded), h2Options(), DatabaseDialect.H2);

            assertThat(result.outcomes())
                    .extracting(ChangeSetOutcome::changeSetId, ChangeSetOutcome::status)
                    .containsExactly(
                            tuple("fresh", ChangeSetOutcome.Status.EXECUTED),
                            tuple("recorded", ChangeSetOutcome.Status.ALREADY_RECORDED));
        }
    }

    @Test
    void dryRunOutcomesAlsoKeepApplicationOrder() throws Exception {
        SchemaDefinition.ChangeSet recorded = new SchemaDefinition.ChangeSet(
                "recorded", "applied by an earlier run", List.of("CREATE TABLE items_recorded (id INT)"));
        SchemaDefinition.ChangeSet planned = new SchemaDefinition.ChangeSet(
                "planned", "previewed", List.of("CREATE TABLE items_planned (id INT)"));
        ChangeSetExecutor executor = new ChangeSetExecutor();
        SchemaSynchronizerOptions dryRun = new SchemaSynchronizerOptions(
                "public", "schema_synchronizer_history", 7_249_031_147L, true, true, true);
        try (Connection connection = h2()) {
            executor.apply(connection, List.of(recorded), h2Options(), DatabaseDialect.H2);

            ChangeSetExecutor.Result result = executor.apply(connection,
                    List.of(planned, recorded), dryRun, DatabaseDialect.H2);

            assertThat(result.outcomes())
                    .extracting(ChangeSetOutcome::changeSetId, ChangeSetOutcome::status)
                    .containsExactly(
                            tuple("planned", ChangeSetOutcome.Status.SKIPPED_DRY_RUN),
                            tuple("recorded", ChangeSetOutcome.Status.ALREADY_RECORDED));
        }
    }

    @Test
    void verificationFailureCarriesTheOutcomesRecordedSoFar() throws Exception {
        SchemaDefinition.ChangeSet recorded = new SchemaDefinition.ChangeSet(
                "recorded", "applied by an earlier run", List.of("CREATE TABLE items_recorded (id INT)"));
        SchemaDefinition.ChangeSet failing = new SchemaDefinition.ChangeSet(
                "failing", "verification fails afterwards",
                List.of("CREATE TABLE items_failing (id INT)"), "SELECT FALSE");
        ChangeSetExecutor executor = new ChangeSetExecutor();
        try (Connection connection = h2()) {
            executor.apply(connection, List.of(recorded), h2Options(), DatabaseDialect.H2);

            assertThatThrownBy(() -> executor.apply(connection, List.of(recorded, failing),
                    h2Options(), DatabaseDialect.H2))
                    .isInstanceOf(ChangeSetExecutionException.class)
                    .hasMessageContaining("verification failed after schema change 'failing'")
                    .satisfies(failure -> assertThat(((ChangeSetExecutionException) failure).outcomes())
                            .extracting(ChangeSetOutcome::changeSetId, ChangeSetOutcome::status)
                            .containsExactly(
                                    tuple("recorded", ChangeSetOutcome.Status.ALREADY_RECORDED),
                                    tuple("failing", ChangeSetOutcome.Status.FAILED)));
        }
    }

    @Test
    void skippedDuplicatesAreReportedOnExecutedAndFailedOutcomes() throws Exception {
        SchemaSynchronizerOptions options = h2Options();
        ChangeSetExecutor executor = new ChangeSetExecutor();
        try (Connection connection = h2()) {
            execute(connection, "CREATE TABLE items_partial (id INT)");
            SchemaDefinition.ChangeSet partial = new SchemaDefinition.ChangeSet(
                    "partial", "halfway adopted",
                    List.of("CREATE TABLE items_partial (id INT)", "CREATE TABLE items_rest (id INT)"),
                    "SELECT (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                            + " WHERE TABLE_NAME IN ('ITEMS_PARTIAL', 'ITEMS_REST')) >= 2");

            ChangeSetExecutor.Result result = executor.apply(connection, List.of(partial), options,
                    DatabaseDialect.H2);

            assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(ChangeSetOutcome.Status.EXECUTED);
                assertThat(outcome.executedStatements()).isEqualTo(1);
                assertThat(outcome.skippedStatements()).isEqualTo(1);
            });

            SchemaDefinition.ChangeSet broken = new SchemaDefinition.ChangeSet(
                    "broken", "fails after a skipped statement",
                    List.of("CREATE TABLE items_partial (id INT)", "CREATE TABLE items_broken (id BAD_TYPE)"),
                    "SELECT FALSE");

            assertThatThrownBy(() -> executor.apply(connection, List.of(broken), options, DatabaseDialect.H2))
                    .isInstanceOf(ChangeSetExecutionException.class)
                    .satisfies(failure -> assertThat(((ChangeSetExecutionException) failure).outcomes())
                            .singleElement()
                            .satisfies(outcome -> {
                                assertThat(outcome.status()).isEqualTo(ChangeSetOutcome.Status.FAILED);
                                assertThat(outcome.executedStatements()).isZero();
                                assertThat(outcome.skippedStatements()).isEqualTo(1);
                            }));
        }
    }

    private Connection connectionWithoutHistory() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(connection.getCatalog()).thenReturn("test");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), eq("schema_synchronizer_history"), any(String[].class)))
                .thenReturn(tables);
        when(tables.next()).thenReturn(false);
        return connection;
    }

    private SchemaSynchronizerOptions mariaOptions() {
        return new SchemaSynchronizerOptions(
                "test", "schema_synchronizer_history", 7_249_031_147L, false, true, true);
    }
}
