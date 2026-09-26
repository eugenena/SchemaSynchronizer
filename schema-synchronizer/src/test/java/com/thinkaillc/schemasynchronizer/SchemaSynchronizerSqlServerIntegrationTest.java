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
        SchemaSnapshotWriter.main(new String[]{
                jdbcUrl(),
                System.getProperty("schema.test.sqlserver.jdbc.user"),
                System.getProperty("schema.test.sqlserver.jdbc.password"),
                "dbo",
                snapshot.toString()
        });
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
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions("dbo", "schema_synchronizer_history", 7_249_031_147L,
                        false, false, true));
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
