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

    @Test
    void nationalAndTemporalDeclarationsConvergeAndDriftIsPending() throws Exception {
        String create = "CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL AUTO_INCREMENT, "
                + "nick NVARCHAR(20), stamp DATETIME(3), clock TIME(2), "
                + "marker VARCHAR(20) DEFAULT 'CURRENT_TIMESTAMP', PRIMARY KEY (id))";
        SchemaDefinition declared = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef(create, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                        new SchemaDefinition.ColumnDef("nick", "NVARCHAR(20)"),
                        new SchemaDefinition.ColumnDef("stamp", "DATETIME(3)"),
                        new SchemaDefinition.ColumnDef("clock", "TIME(2)"),
                        new SchemaDefinition.ColumnDef("marker", "VARCHAR(20) DEFAULT 'CURRENT_TIMESTAMP'")),
                        List.of())),
                List.of());
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, declared).tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, declared);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE maria_items MODIFY COLUMN nick VARCHAR(20) CHARACTER SET utf8mb4");
        }
        SchemaDefinition drifted = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef(create, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                        new SchemaDefinition.ColumnDef("nick", "NVARCHAR(20)"),
                        new SchemaDefinition.ColumnDef("stamp", "DATETIME(6)"),
                        new SchemaDefinition.ColumnDef("clock", "TIME(2)"),
                        new SchemaDefinition.ColumnDef("marker", "VARCHAR(20) DEFAULT 'CURRENT_TIMESTAMP'")),
                        List.of())),
                List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, drifted);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN nick")
                    && sql.contains("utf8mb4"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN stamp"));
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

    @Test
    void appliedDefaultsThatTheServerRewritesConvergeOnTheNextSync() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, ts3 DATETIME(3), "
                    + "price DECIMAL(10,2), fixed_at DATETIME(3), flag TINYINT, ok BOOLEAN DEFAULT FALSE, "
                    + "bits BIT(8) DEFAULT b'101', raw VARBINARY(10) DEFAULT 'ab', fixed BINARY(4) DEFAULT 'ab')");
        }
        SchemaDefinition declared = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("ts3", "DATETIME(3) DEFAULT NOW(3)"),
                                new SchemaDefinition.ColumnDef("price", "DECIMAL(10,2) DEFAULT 1"),
                                new SchemaDefinition.ColumnDef("fixed_at", "DATETIME(3) DEFAULT '2020-01-01 00:00:00'"),
                                new SchemaDefinition.ColumnDef("flag", "TINYINT DEFAULT FALSE"),
                                new SchemaDefinition.ColumnDef("ok", "BOOLEAN DEFAULT FALSE"),
                                new SchemaDefinition.ColumnDef("bits", "BIT(8) DEFAULT b'101'"),
                                new SchemaDefinition.ColumnDef("raw", "VARBINARY(10) DEFAULT X'6162'"),
                                new SchemaDefinition.ColumnDef("fixed", "BINARY(4) DEFAULT 'ab'")),
                        List.of())),
                List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult first = synchronizer().synchronizeWithResult(connection, declared);
            assertThat(first.pendingSql()).isEmpty();
            assertThat(first.columnsAltered()).isEqualTo(4);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult second = synchronizer(true).synchronizeWithResult(connection, declared);
            assertThat(second.pendingSql()).isEmpty();
            assertThat(second.changed()).isFalse();
        }
    }

    @Test
    void binaryDefaultsAreReadExactlyAndOnUpdateIsCompared(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "raw VARBINARY(4) DEFAULT X'FF', zeros VARBINARY(8) DEFAULT 0x00275C0A, "
                    + "padded BINARY(4) DEFAULT 0x0041, note VARCHAR(20), "
                    + "strict VARBINARY(8) NOT NULL DEFAULT 0x00FF, one VARBINARY(4) NOT NULL DEFAULT 'ab', "
                    + "touched DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                    + "stamped DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }
        SchemaDefinition declared = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("raw", "VARBINARY(4) DEFAULT X'FF'"),
                                new SchemaDefinition.ColumnDef("zeros", "VARBINARY(8) DEFAULT X'00275C0A'"),
                                new SchemaDefinition.ColumnDef("padded", "BINARY(4) DEFAULT 0x0041"),
                                new SchemaDefinition.ColumnDef("note", "VARCHAR(20) DEFAULT 'nul\u0000x'"),
                                new SchemaDefinition.ColumnDef("strict", "VARBINARY(8) NOT NULL DEFAULT X'00FF'"),
                                new SchemaDefinition.ColumnDef("one", "VARBINARY(4) NOT NULL DEFAULT 'ab'"),
                                new SchemaDefinition.ColumnDef("touched",
                                        "DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)"),
                                new SchemaDefinition.ColumnDef("stamped",
                                        "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")),
                        List.of())),
                List.of());
        // information_schema reports X'FF' as '?' before MariaDB 11.8; the bytes are read from SHOW CREATE TABLE.
        for (int run = 0; run < 2; run++) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, declared);
                assertThat(result.columnsAltered()).isZero();
                assertThat(result.pendingSql()).hasSize(2);
                assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN note"));
                assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN stamped")
                        && sql.contains("ON UPDATE differs"));
            }
        }
        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, connection.getCatalog(), snapshot);
        }
        assertThat(java.nio.file.Files.readString(snapshot)).contains("DEFAULT 0xFF", "DEFAULT 0x00275C0A",
                "DEFAULT 0x00410000", "DEFAULT 0x00FF", "DEFAULT 0x6162", "ON UPDATE CURRENT_TIMESTAMP(3)");
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE maria_items");
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

    private SchemaSynchronizer synchronizer() throws Exception {
        return synchronizer(false);
    }

    private SchemaSynchronizer synchronizer(boolean failOnPending) throws Exception {
        String catalog;
        try (Connection connection = connection()) {
            catalog = connection.getCatalog();
        }
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(catalog, "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("schema.test.mariadb.jdbc.url"),
                System.getProperty("schema.test.mariadb.jdbc.user"),
                System.getProperty("schema.test.mariadb.jdbc.password"));
    }
}
