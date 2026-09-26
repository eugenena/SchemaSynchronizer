// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void validateRejectsCrossSchemaChangeSetSql() {
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "cross", "bad", List.of("UPDATE other.items SET note = 'x'"));

        assertThatThrownBy(() -> new ChangeSetExecutor().validate(List.of(change), mariaOptions()))
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
                "SELECT pg_terminate_backend(1)");
        SchemaSynchronizerOptions dryRun = new SchemaSynchronizerOptions(
                "test", "schema_synchronizer_history", 7_249_031_147L, true, true, true);

        ChangeSetExecutor.Result result = new ChangeSetExecutor().apply(
                connection, List.of(change), dryRun, DatabaseDialect.POSTGRESQL);

        assertThat(result.applied()).isZero();
        assertThat(result.plannedSql()).containsExactly("ALTER TABLE items ADD COLUMN note TEXT");
        assertThat(verificationCalls.get()).isZero();
        verify(statement, never()).execute(anyString());
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
