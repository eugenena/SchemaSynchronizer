// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SQL policy gates only change sets missing from history: a recorded change set with a
 * matching checksum never runs again, so it is not re-checked, while every unrecorded one is
 * checked before anything executes.
 */
class ChangeSetHistoryPolicyTest {

    private static final SchemaDefinition.ChangeSet LEGACY_DROP = new SchemaDefinition.ChangeSet(
            "000-legacy", "applied before the policy", List.of("DROP TABLE old_items"));
    private static final SchemaDefinition.ChangeSet LEGACY_UNPARSEABLE = new SchemaDefinition.ChangeSet(
            "000-legacy-path", "applied before the policy", List.of("UPDATE items SET note = 'C:\\'"));
    private static final SchemaDefinition.ChangeSet SAFE = new SchemaDefinition.ChangeSet(
            "001-safe", "additive", List.of("ALTER TABLE items ADD COLUMN note TEXT"));
    private static final SchemaDefinition.ChangeSet FORBIDDEN = new SchemaDefinition.ChangeSet(
            "002-forbidden", "destructive", List.of("DROP TABLE items"));

    private final List<String> executed = new ArrayList<>();

    @Test
    void appliedChangeSetWithMatchingChecksumIsNotRecheckedOrExecuted() throws Exception {
        Map<String, String> history = Map.of(LEGACY_DROP.id(), checksum(LEGACY_DROP),
                LEGACY_UNPARSEABLE.id(), checksum(LEGACY_UNPARSEABLE));
        List<SchemaDefinition.ChangeSet> changes = List.of(LEGACY_DROP, LEGACY_UNPARSEABLE);

        for (SchemaSynchronizerOptions options : List.of(options(false), options(true))) {
            assertThatCode(() -> new ChangeSetExecutor().validateHistory(connection(history), changes, options,
                    DatabaseDialect.POSTGRESQL)).as("dryRun=%s", options.dryRun()).doesNotThrowAnyException();
            ChangeSetExecutor.Result result = new ChangeSetExecutor().apply(connection(history), changes, options,
                    DatabaseDialect.POSTGRESQL);
            assertThat(result.applied()).isZero();
            assertThat(result.plannedSql()).isEmpty();
        }
        assertThat(executed).isEmpty();
    }

    @Test
    void unappliedChangeSetIsRejectedBeforeAnythingExecutes() throws Exception {
        for (SchemaSynchronizerOptions options : List.of(options(false), options(true))) {
            assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connection(Map.of()),
                    List.of(LEGACY_DROP), options, DatabaseDialect.POSTGRESQL))
                    .as("dryRun=%s", options.dryRun())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
            assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connection(Map.of()),
                    List.of(LEGACY_UNPARSEABLE), options, DatabaseDialect.POSTGRESQL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("standard_conforming_strings");
            assertThatThrownBy(() -> new ChangeSetExecutor().apply(connection(Map.of()), List.of(SAFE, FORBIDDEN),
                    options, DatabaseDialect.POSTGRESQL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }
        assertThat(executed).isEmpty();
    }

    @Test
    void checksumMismatchFailsBeforeThePolicy() throws Exception {
        Map<String, String> history = Map.of(LEGACY_DROP.id(), "0".repeat(64));

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connection(history), List.of(LEGACY_DROP),
                options(false), DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(executed).isEmpty();
    }

    @Test
    void eachChangeSetIsJudgedByItsOwnHistoryEntry() throws Exception {
        Map<String, String> appliedForbidden = Map.of(LEGACY_DROP.id(), checksum(LEGACY_DROP));
        assertThatCode(() -> new ChangeSetExecutor().validateHistory(connection(appliedForbidden),
                List.of(LEGACY_DROP, SAFE), options(false), DatabaseDialect.POSTGRESQL))
                .doesNotThrowAnyException();
        ChangeSetExecutor.Result dryRun = new ChangeSetExecutor().apply(connection(appliedForbidden),
                List.of(LEGACY_DROP, SAFE), options(true), DatabaseDialect.POSTGRESQL);
        assertThat(dryRun.plannedSql()).containsExactly("ALTER TABLE items ADD COLUMN note TEXT");

        Map<String, String> appliedSafe = Map.of(SAFE.id(), checksum(SAFE));
        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connection(appliedSafe),
                List.of(SAFE, FORBIDDEN), options(false), DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThat(executed).isEmpty();
    }

    @Test
    void withoutAHistoryTableEveryChangeSetIsChecked() throws Exception {
        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connectionWithoutHistoryTable(),
                List.of(SAFE, LEGACY_DROP), options(false), DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThat(executed).isEmpty();
    }

    @Test
    void implicitDdlEnginesCheckThePolicyBeforeRunningVerificationSql() throws Exception {
        SchemaDefinition.ChangeSet forbiddenVerified = new SchemaDefinition.ChangeSet("003-two", "two statements",
                List.of("ALTER TABLE items ADD COLUMN a INT", "DROP TABLE items"), "SELECT 1 = 1");

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(connectionWithoutHistoryTable(),
                List.of(forbiddenVerified), options(false, "appdb"), DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThat(executed).isEmpty();
    }

    @Test
    void offlineValidationChecksEveryChangeSet() {
        assertThatThrownBy(() -> SchemaDefinitionValidator.validate(new SchemaDefinition(Map.of(),
                List.of(LEGACY_DROP)), "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThat(new ChangeSetExecutor().validateStructure(List.of(LEGACY_DROP, LEGACY_UNPARSEABLE))).hasSize(2);
    }

    private static String checksum(SchemaDefinition.ChangeSet change) throws Exception {
        return ChangeSetExecutor.checksum(change);
    }

    private static SchemaSynchronizerOptions options(boolean dryRun) {
        return options(dryRun, "public");
    }

    private static SchemaSynchronizerOptions options(boolean dryRun, String schema) {
        return new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L, dryRun, true,
                true);
    }

    /** A connection whose history table holds {@code history}; every other statement is recorded as executed. */
    private Connection connection(Map<String, String> history) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), eq("schema_synchronizer_history"), any(String[].class)))
                .thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_SCHEM")).thenReturn("public");
        when(tables.getString("TABLE_NAME")).thenReturn("schema_synchronizer_history");
        Statement statement = recordingStatement();
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.startsWith("SELECT change_id, checksum FROM")) {
                executed.add(sql);
                throw new AssertionError("unexpected query: " + sql);
            }
            return historyRows(history);
        });
        when(connection.createStatement()).thenReturn(statement);
        return connection;
    }

    private Connection connectionWithoutHistoryTable() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(connection.getCatalog()).thenReturn("appdb");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), eq("schema_synchronizer_history"), any(String[].class)))
                .thenReturn(tables);
        when(tables.next()).thenReturn(false);
        Statement statement = recordingStatement();
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            throw new AssertionError("unexpected query: " + invocation.getArgument(0));
        });
        when(connection.createStatement()).thenReturn(statement);
        return connection;
    }

    private Statement recordingStatement() throws Exception {
        Statement statement = mock(Statement.class);
        when(statement.execute(anyString())).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return false;
        });
        return statement;
    }

    private static ResultSet historyRows(Map<String, String> history) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        Iterator<Map.Entry<String, String>> entries = history.entrySet().iterator();
        Map.Entry<String, String>[] current = new Map.Entry[1];
        when(rows.next()).thenAnswer(invocation -> {
            if (!entries.hasNext()) {
                return false;
            }
            current[0] = entries.next();
            return true;
        });
        when(rows.getString(1)).thenAnswer(invocation -> current[0].getKey());
        when(rows.getString(2)).thenAnswer(invocation -> current[0].getValue());
        return rows;
    }
}
