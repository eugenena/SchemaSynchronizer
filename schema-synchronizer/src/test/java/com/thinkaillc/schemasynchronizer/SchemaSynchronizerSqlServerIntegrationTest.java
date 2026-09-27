// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "schema.test.sqlserver.jdbc.url", matches = ".+")
class SchemaSynchronizerSqlServerIntegrationTest {

    @BeforeEach
    void ensureDatabase() throws Exception {
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute(
                    "IF DB_ID(N'schema_synchronizer_test') IS NULL CREATE DATABASE schema_synchronizer_test");
        }
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("IF OBJECT_ID(N'dbo.sqlserver_items', N'U') IS NOT NULL DROP TABLE dbo.sqlserver_items");
            statement.execute("IF OBJECT_ID(N'dbo.sqlserver_strict', N'U') IS NOT NULL DROP TABLE dbo.sqlserver_strict");
            statement.execute("IF OBJECT_ID(N'dbo.schema_synchronizer_history', N'U') IS NOT NULL "
                    + "DROP TABLE dbo.schema_synchronizer_history");
        }
    }

    @Test
    void createsSerializesReplaysWidensIndexesAndReportsDestructiveDrift(@TempDir Path tempDir) throws Exception {
        SchemaSynchronizer synchronizer = synchronizer();
        SchemaDefinition initial = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL IDENTITY(1,1)"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR(40) NOT NULL")));

        try (Connection connection = connection()) {
            assertThat(DatabaseDialect.detect(connection.getMetaData())).isEqualTo(DatabaseDialect.SQLSERVER);
            SchemaSynchronizationResult first = synchronizer.synchronizeWithResult(connection, initial);
            assertThat(first.tablesCreated()).isEqualTo(1);
            assertThat(first.pendingSql()).isEmpty();
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).changed()).isFalse();
        }

        SchemaDefinition additive = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL IDENTITY(1,1)"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR(100) NOT NULL"),
                new SchemaDefinition.ColumnDef("notes", "VARCHAR(255)")));
        try (Connection connection = connection()) {
            SchemaSynchronizationResult changed = synchronizer.synchronizeWithResult(connection, additive);
            assertThat(changed.columnsAdded()).isEqualTo(1);
            assertThat(changed.columnsAltered()).isEqualTo(1);
            assertThat(changed.pendingSql()).isEmpty();
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, additive).changed()).isFalse();
        }

        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, "dbo", snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.declaredDialect()).isEqualTo(DatabaseDialect.SQLSERVER);
        assertThat(serialized.tables()).containsKey("sqlserver_items");
        assertThat(serialized.tables()).doesNotContainKeys(
                "msreplication_options", "spt_monitor", "spt_fallback_db");

        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).pendingSql())
                    .anyMatch(sql -> sql.contains("DROP COLUMN notes"));
        }
    }

    @Test
    void strictResyncAndSnapshotReplayHaveNoPendingDrift(@TempDir Path tempDir) throws Exception {
        String create = "CREATE TABLE sqlserver_strict (id BIGINT IDENTITY(1,1) NOT NULL, code INT NOT NULL, "
                + "label VARCHAR(40) NOT NULL DEFAULT 'new', qty INT DEFAULT 0, "
                + "created_at DATETIME2 DEFAULT SYSUTCDATETIME(), national_name NVARCHAR(50), "
                + "notes NVARCHAR(MAX), stamp DATETIME2(3), clock TIME(0), zoned DATETIMEOFFSET, legacy DATETIME, "
                + "PRIMARY KEY (id))";
        List<String> indexes = List.of(
                "CREATE INDEX idx_sqlserver_strict_label ON sqlserver_strict (label, code DESC)");
        SchemaDefinition initial = strictDefinition(create, columns("VARCHAR(40) NOT NULL DEFAULT 'new'",
                "INT NOT NULL", "INT DEFAULT 0"), indexes);

        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, initial).tablesCreated()).isEqualTo(1);
            try (var statement = connection.createStatement()) {
                // Live-only filtered index: omitted from reconstruction, never reported as drift.
                statement.execute("CREATE INDEX idx_sqlserver_strict_filtered ON sqlserver_strict (national_name) "
                        + "WHERE national_name IS NOT NULL");
            }
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, initial);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }

        List<SchemaDefinition.ColumnDef> finer = columns("VARCHAR(40) NOT NULL DEFAULT 'new'", "INT NOT NULL",
                "INT DEFAULT 0").stream()
                .map(column -> column.name().equals("stamp")
                        ? new SchemaDefinition.ColumnDef("stamp", "DATETIME2") : column)
                .toList();
        try (Connection connection = connection()) {
            SchemaSynchronizationResult drift = synchronizer(false)
                    .synchronizeWithResult(connection, strictDefinition(create, finer, indexes));
            assertThat(drift.columnsAltered()).isZero();
            assertThat(drift.pendingSql()).anyMatch(sql -> sql.contains("ALTER COLUMN stamp DATETIME2"));
        }

        // Indexed VARCHAR with a DEFAULT constraint: a length-only widen is executable on SQL Server.
        SchemaDefinition widened = strictDefinition(create, columns("VARCHAR(100) NOT NULL DEFAULT 'new'",
                "INT NOT NULL", "INT DEFAULT 0"), indexes);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult altered = synchronizer(true).synchronizeWithResult(connection, widened);
            assertThat(altered.pendingSql()).isEmpty();
            assertThat(altered.columnsAltered()).isEqualTo(1);
        }

        // Base-type change under a DEFAULT constraint, nullability change on an indexed column, and a
        // default change are all reported (never attempted) — the run must not fail mid-startup.
        SchemaDefinition blocked = strictDefinition(create, columns("VARCHAR(100) NOT NULL DEFAULT 'new'",
                "INT", "BIGINT DEFAULT 1"), indexes);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult reported = synchronizer(false).synchronizeWithResult(connection, blocked);
            assertThat(reported.columnsAltered()).isZero();
            assertThat(reported.pendingSql())
                    .anyMatch(sql -> sql.contains("ALTER COLUMN qty BIGINT NULL") && sql.contains("DEFAULT constraint"))
                    .anyMatch(sql -> sql.contains("ALTER COLUMN code INT NULL") && sql.contains("used by an index"))
                    .anyMatch(sql -> sql.startsWith("-- pending: SQL Server DEFAULT for sqlserver_strict.qty"));
        }

        // A computed column referencing label blocks even a length-only widen.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE sqlserver_strict ADD label_len AS LEN(label)");
        }
        SchemaDefinition widenedAgain = strictDefinition(create, columns("VARCHAR(120) NOT NULL DEFAULT 'new'",
                "INT NOT NULL", "INT DEFAULT 0"), indexes);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult computed = synchronizer(false).synchronizeWithResult(connection, widenedAgain);
            assertThat(computed.columnsAltered()).isZero();
            assertThat(computed.pendingSql())
                    .anyMatch(sql -> sql.contains("ALTER COLUMN label VARCHAR(120)") && sql.contains("computed column"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE sqlserver_strict DROP COLUMN label_len");
        }

        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, "dbo", snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("sqlserver_strict").indexes())
                .noneMatch(sql -> sql.contains("idx_sqlserver_strict_filtered"));
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE dbo.sqlserver_strict");
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, serialized).tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult replayed = synchronizer(true).synchronizeWithResult(connection, serialized);
            assertThat(replayed.pendingSql()).isEmpty();
            assertThat(replayed.changed()).isFalse();
        }
    }

    @Test
    void appliesChangeSetsWithAndWithoutACallerOwnedTransaction() throws Exception {
        SchemaDefinition withChange = new SchemaDefinition(2, "sqlserver", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-sqlserver-items", "create via change set",
                        List.of("CREATE TABLE sqlserver_items (id BIGINT NOT NULL PRIMARY KEY, label VARCHAR(40))"),
                        "SELECT CAST(CASE WHEN OBJECT_ID('dbo.sqlserver_items') IS NULL THEN 0 ELSE 1 END AS BIT)",
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA),
                new SchemaDefinition.ChangeSet("002-sqlserver-flag", "add flag",
                        List.of("ALTER TABLE sqlserver_items ADD flag INT NULL"),
                        "SELECT CAST(CASE WHEN COL_LENGTH('dbo.sqlserver_items', 'flag') IS NULL THEN 0 ELSE 1 END "
                                + "AS BIT)",
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)));
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, withChange).changeSetsApplied())
                    .isEqualTo(2);
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE dbo.sqlserver_items");
            statement.execute("DELETE FROM dbo.schema_synchronizer_history");
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            assertThat(synchronizer(true).synchronizeWithResult(connection, withChange).changeSetsApplied())
                    .isEqualTo(2);
            connection.commit();
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COL_LENGTH('dbo.sqlserver_items', 'flag')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject(1)).isNotNull();
        }
    }

    private static List<SchemaDefinition.ColumnDef> columns(String label, String code, String qty) {
        return List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL IDENTITY(1,1)"),
                new SchemaDefinition.ColumnDef("code", code),
                new SchemaDefinition.ColumnDef("label", label),
                new SchemaDefinition.ColumnDef("qty", qty),
                new SchemaDefinition.ColumnDef("created_at", "DATETIME2 DEFAULT SYSUTCDATETIME()"),
                new SchemaDefinition.ColumnDef("national_name", "NVARCHAR(50)"),
                new SchemaDefinition.ColumnDef("notes", "NVARCHAR(MAX)"),
                new SchemaDefinition.ColumnDef("stamp", "DATETIME2(3)"),
                new SchemaDefinition.ColumnDef("clock", "TIME(0)"),
                new SchemaDefinition.ColumnDef("zoned", "DATETIMEOFFSET"),
                new SchemaDefinition.ColumnDef("legacy", "DATETIME"));
    }

    private SchemaDefinition strictDefinition(String create, List<SchemaDefinition.ColumnDef> columns,
                                              List<String> indexes) {
        return new SchemaDefinition(2, "sqlserver", Map.of("sqlserver_strict",
                new SchemaDefinition.TableDef(create, columns, indexes)), List.of());
    }

    private SchemaDefinition definition(List<SchemaDefinition.ColumnDef> columns) {
        return new SchemaDefinition(2, "sqlserver", Map.of("sqlserver_items",
                new SchemaDefinition.TableDef(
                        "CREATE TABLE sqlserver_items (id BIGINT NOT NULL IDENTITY(1,1), "
                                + "label VARCHAR(40) NOT NULL, PRIMARY KEY (id))",
                        columns,
                        List.of("CREATE INDEX idx_sqlserver_items_label ON sqlserver_items (label)"))),
                List.of());
    }

    private SchemaSynchronizer synchronizer() {
        return synchronizer(false);
    }

    private SchemaSynchronizer synchronizer(boolean failOnPending) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions("dbo", "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(),
                System.getProperty("schema.test.sqlserver.jdbc.user"),
                System.getProperty("schema.test.sqlserver.jdbc.password"));
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("schema.test.sqlserver.jdbc.url"),
                System.getProperty("schema.test.sqlserver.jdbc.user"),
                System.getProperty("schema.test.sqlserver.jdbc.password"));
    }

    private String jdbcUrl() {
        String base = System.getProperty("schema.test.sqlserver.jdbc.url");
        if (base.contains("databaseName=")) {
            return base.replaceAll("(?i)databaseName=[^;]*", "databaseName=schema_synchronizer_test");
        }
        return base + (base.contains(";") ? "" : ";") + "databaseName=schema_synchronizer_test";
    }
}
