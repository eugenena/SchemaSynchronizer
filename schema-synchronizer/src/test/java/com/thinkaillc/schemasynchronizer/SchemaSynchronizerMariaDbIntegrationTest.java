// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "schema.test.mariadb.jdbc.url", matches = ".+")
class SchemaSynchronizerMariaDbIntegrationTest {

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS maria_items");
            statement.execute("DROP TABLE IF EXISTS schema_synchronizer_history");
        }
    }

    @Test
    void createsReplaysWidensAndReportsDestructiveDrift() throws Exception {
        SchemaSynchronizer synchronizer = synchronizer();
        SchemaDefinition initial = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR(40) NOT NULL")));

        try (Connection connection = connection()) {
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
            assertThat(synchronizer.synchronizeWithResult(connection, initial).pendingSql())
                    .anyMatch(sql -> sql.contains("DROP COLUMN notes"));
        }
    }

    private SchemaDefinition definition(List<SchemaDefinition.ColumnDef> columns) {
        return new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL AUTO_INCREMENT, "
                                + "label VARCHAR(40) NOT NULL, PRIMARY KEY (id))",
                        columns,
                        List.of("CREATE INDEX IF NOT EXISTS idx_maria_items_label ON maria_items (label)"))),
                List.of());
    }

    private SchemaSynchronizer synchronizer() throws Exception {
        String catalog;
        try (Connection connection = connection()) {
            catalog = connection.getCatalog();
        }
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(catalog, "schema_synchronizer_history", 7_249_031_147L,
                        false, false, true));
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("schema.test.mariadb.jdbc.url"),
                System.getProperty("schema.test.mariadb.jdbc.user"),
                System.getProperty("schema.test.mariadb.jdbc.password"));
    }
}
