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

@EnabledIfSystemProperty(named = "schema.test.mysql.jdbc.url", matches = ".+")
class SchemaSynchronizerMySqlIntegrationTest {

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS mysql_items");
            statement.execute("DROP TABLE IF EXISTS mysql_flag");
            statement.execute("DROP TABLE IF EXISTS schema_synchronizer_history");
        }
    }

    @Test
    void createsSerializesReplaysWidensIndexesAndReportsDestructiveDrift(@TempDir Path tempDir) throws Exception {
        SchemaSynchronizer synchronizer = synchronizer();
        SchemaDefinition initial = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR(40) NOT NULL")));

        try (Connection connection = connection()) {
            assertThat(DatabaseDialect.detect(connection.getMetaData())).isEqualTo(DatabaseDialect.MYSQL);
            SchemaSynchronizationResult first = synchronizer.synchronizeWithResult(connection, initial);
            assertThat(first.tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).changed()).isFalse();
        }

        SchemaDefinition additive = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR(100) NOT NULL"),
                new SchemaDefinition.ColumnDef("notes", "VARCHAR(255)")));
        try (Connection connection = connection()) {
            SchemaSynchronizationResult changed = synchronizer.synchronizeWithResult(connection, additive);
            assertThat(changed.columnsAdded()).isEqualTo(1);
            assertThat(changed.columnsAltered()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, additive).changed()).isFalse();
        }

        Path snapshot = tempDir.resolve("schema-definition.json");
        SchemaSnapshotWriter.main(new String[]{
                System.getProperty("schema.test.mysql.jdbc.url"),
                System.getProperty("schema.test.mysql.jdbc.user"),
                System.getProperty("schema.test.mysql.jdbc.password"),
                catalog(),
                snapshot.toString()
        });
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.declaredDialect()).isEqualTo(DatabaseDialect.MYSQL);
        assertThat(serialized.tables()).containsKey("mysql_items");

        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).pendingSql())
                    .anyMatch(sql -> sql.contains("DROP COLUMN notes"));
        }
    }

    private SchemaDefinition definition(List<SchemaDefinition.ColumnDef> columns) {
        return new SchemaDefinition(2, "mysql", Map.of("mysql_items",
                new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS mysql_items (id BIGINT NOT NULL AUTO_INCREMENT, "
                                + "label VARCHAR(40) NOT NULL, PRIMARY KEY (id))",
                        columns,
                        List.of("CREATE INDEX idx_mysql_items_label ON mysql_items (label)"))),
                List.of());
    }

    @Test
    void appliesChangeSetAndCreatesHistoryWithAppliedBy() throws Exception {
        // Exercises createHistory() + MySQL ADD COLUMN applied_by (no IF NOT EXISTS).
        SchemaDefinition withChange = new SchemaDefinition(2, "mysql", Map.of(), List.of(
                new SchemaDefinition.ChangeSet(
                        "001-mysql-flag",
                        "add flag table via change set",
                        List.of("CREATE TABLE IF NOT EXISTS mysql_flag (id BIGINT PRIMARY KEY)"),
                        "SELECT COUNT(*) = 1 FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_name = 'mysql_flag'",
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)));
        SchemaSynchronizer synchronizer = synchronizer();
        try (Connection connection = connection()) {
            SchemaSynchronizationResult first = synchronizer.synchronizeWithResult(connection, withChange);
            assertThat(first.changeSetsApplied()).isEqualTo(1);
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT applied_by FROM schema_synchronizer_history WHERE change_id = '001-mysql-flag'")) {
            assertThat(rows.next()).isTrue();
            // Column must exist; value may be null depending on JDBC URL user.
            rows.getString(1);
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, withChange).changeSetsApplied())
                    .isZero();
        }
    }

    private SchemaSynchronizer synchronizer() throws Exception {
        String catalog = catalog();
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(catalog, "schema_synchronizer_history", 7_249_031_147L,
                        false, false, true));
    }

    private String catalog() throws Exception {
        try (Connection connection = connection()) {
            return connection.getCatalog();
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("schema.test.mysql.jdbc.url"),
                System.getProperty("schema.test.mysql.jdbc.user"),
                System.getProperty("schema.test.mysql.jdbc.password"));
    }
}
