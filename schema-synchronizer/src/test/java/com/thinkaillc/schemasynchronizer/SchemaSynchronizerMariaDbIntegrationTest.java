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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "schema.test.mariadb.jdbc.url", matches = ".+")
class SchemaSynchronizerMariaDbIntegrationTest {

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS maria_items");
            statement.execute("DROP TABLE IF EXISTS mariaxitems");
            statement.execute("DROP TABLE IF EXISTS schema_synchronizer_history");
        }
    }

    @Test
    void namesAreNotPatternsAndDecimalPrecisionIsCompared(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "amount DECIMAL(10,4) UNSIGNED NOT NULL, plain DECIMAL, wide DECIMAL(12,4), uns INT UNSIGNED DEFAULT 1)");
            // `_` is a metadata wildcard: this table must not lend its columns to maria_items.
            statement.execute("CREATE TABLE mariaxitems (id BIGINT NOT NULL PRIMARY KEY, extra INT)");
        }
        SchemaDefinition.TableDef sibling = new SchemaDefinition.TableDef(
                "CREATE TABLE IF NOT EXISTS mariaxitems (id BIGINT NOT NULL PRIMARY KEY, extra INT)",
                List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("extra", "INT")), List.of());
        SchemaDefinition declared = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("amount", "DECIMAL(10,2) UNSIGNED"),
                                new SchemaDefinition.ColumnDef("plain", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("wide", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 5"),
                                new SchemaDefinition.ColumnDef("extra", "INT")), List.of()),
                "mariaxitems", sibling), List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, declared);
            assertThat(result.columnsAdded()).isEqualTo(1);
            // Only the UNSIGNED default is applied; a narrower DECIMAL must not be reached through DROP NOT NULL.
            assertThat(result.columnsAltered()).isEqualTo(1);
            assertThat(result.pendingSql()).anyMatch(line -> line.contains("amount"))
                    .anyMatch(line -> line.contains("wide"))
                    .noneMatch(line -> line.matches("(?s).*\\b(?:plain|uns|extra)\\b.*"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name, column_type, is_nullable, column_default "
                     + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                     + "AND table_name = 'maria_items' ORDER BY ordinal_position")) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (rows.next()) {
                columns.put(rows.getString(1), rows.getString(2) + " " + rows.getString(3));
                if (rows.getString(1).equals("uns")) {
                    assertThat(rows.getString(4)).isEqualTo("5");
                }
            }
            assertThat(columns).containsEntry("amount", "decimal(10,4) unsigned NO")
                    .containsEntry("plain", "decimal(10,0) YES")
                    .containsEntry("wide", "decimal(12,4) YES")
                    .containsEntry("uns", "int(10) unsigned YES")
                    .containsKey("extra");
        }
        SchemaDefinition converged = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("amount", "DECIMAL(10,4) UNSIGNED NOT NULL"),
                                new SchemaDefinition.ColumnDef("plain", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("wide", "DECIMAL(12,4)"),
                                new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 5"),
                                new SchemaDefinition.ColumnDef("extra", "INT")), List.of()),
                "mariaxitems", sibling), List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, converged);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }
        // Connector/J finds nothing for an escaped metadata name under NO_BACKSLASH_ESCAPES.
        Path snapshot = tempDir.resolve("no-backslash-escapes.json");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_BACKSLASH_ESCAPES')");
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, converged);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
            SchemaSnapshotWriter.writeSnapshot(connection, connection.getCatalog(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("maria_items").columns())
                .extracting(SchemaDefinition.ColumnDef::name)
                .containsExactly("id", "amount", "plain", "wide", "uns", "extra");
    }

    @Test
    void zerofillDisplayWidthIsNeitherResetNorSnapshotted(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "narrow INT(5) ZEROFILL NOT NULL, standard INT ZEROFILL NOT NULL)");
        }
        SchemaDefinition relaxed = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("narrow", "INT UNSIGNED ZEROFILL"),
                                new SchemaDefinition.ColumnDef("standard", "INT ZEROFILL")), List.of())), List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, relaxed);
            assertThat(result.columnsAltered()).isEqualTo(1);
            assertThat(result.pendingSql()).singleElement().asString().contains("narrow").contains("display width (5)");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name, column_type, is_nullable FROM information_schema.columns "
                     + "WHERE table_schema = DATABASE() AND table_name = 'maria_items' AND column_name <> 'id'")) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (rows.next()) {
                columns.put(rows.getString(1), rows.getString(2) + " " + rows.getString(3));
            }
            assertThat(columns).containsEntry("narrow", "int(5) unsigned zerofill NO")
                    .containsEntry("standard", "int(10) unsigned zerofill YES");
        }
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> SchemaSnapshotWriter.writeSnapshot(connection, connection.getCatalog(),
                    tempDir.resolve("zerofill.json"))).hasMessageContaining("ZEROFILL display width (5)");
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
                    + "digits VARBINARY(4) DEFAULT 5, "
                    + "bumped DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP, "
                    + "touched DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                    + "stamped DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "latin VARCHAR(10) CHARACTER SET latin1 COLLATE latin1_bin DEFAULT 'x' COMMENT 'it''s', "
                    + "hidden VARBINARY(4) INVISIBLE DEFAULT 'ab', yes_no TINYINT(1) DEFAULT 0, "
                    + "packed VARBINARY(10) COMPRESSED DEFAULT 'ab', "
                    + "due DATETIME DEFAULT (CURRENT_TIMESTAMP + INTERVAL 1 DAY), "
                    + "flags TINYINT(1) UNSIGNED DEFAULT 1, huge DOUBLE DEFAULT 10E299, uns INT UNSIGNED DEFAULT 1, "
                    + "amount DECIMAL(5,2) UNSIGNED DEFAULT 1.50, "
                    + "zfill DECIMAL(8,2) UNSIGNED ZEROFILL DEFAULT 1.5, zint INT UNSIGNED ZEROFILL)");
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
                                new SchemaDefinition.ColumnDef("digits", "VARBINARY(4) DEFAULT 5"),
                                new SchemaDefinition.ColumnDef("bumped",
                                        "DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP"),
                                new SchemaDefinition.ColumnDef("touched",
                                        "DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)"),
                                new SchemaDefinition.ColumnDef("stamped",
                                        "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"),
                                new SchemaDefinition.ColumnDef("latin", "VARCHAR(10) DEFAULT 'x'"),
                                new SchemaDefinition.ColumnDef("hidden", "VARBINARY(4) DEFAULT 'ab'"),
                                new SchemaDefinition.ColumnDef("yes_no", "TINYINT(1) DEFAULT 0"),
                                new SchemaDefinition.ColumnDef("packed", "VARBINARY(10) DEFAULT 'ab'"),
                                new SchemaDefinition.ColumnDef("due",
                                        "DATETIME DEFAULT current_timestamp() + interval 1 day"),
                                new SchemaDefinition.ColumnDef("flags", "TINYINT(1) UNSIGNED DEFAULT 1"),
                                new SchemaDefinition.ColumnDef("huge", "DOUBLE DEFAULT 10E299"),
                                new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 1.0"),
                                new SchemaDefinition.ColumnDef("amount", "DECIMAL(5,2) UNSIGNED DEFAULT 1.5"),
                                new SchemaDefinition.ColumnDef("zfill", "DECIMAL(8,2) UNSIGNED ZEROFILL DEFAULT 1.5"),
                                new SchemaDefinition.ColumnDef("zint", "INT UNSIGNED ZEROFILL")),
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
                "DEFAULT 0x00410000", "DEFAULT 0x00FF", "DEFAULT 0x6162", "ON UPDATE CURRENT_TIMESTAMP(3)",
                "CHARACTER SET latin1 COLLATE latin1_bin", "COMMENT 'it''s'", "INVISIBLE",
                "VARBINARY(10) COMPRESSED", "TINYINT(1) UNSIGNED DEFAULT 1", "NUMERIC(5,2) UNSIGNED DEFAULT 1.50",
                "zfill NUMERIC(8,2) UNSIGNED ZEROFILL", "zint INT UNSIGNED ZEROFILL");
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

    @Test
    void compressedColumnsKeepTheirDefaultAndNeverLoseCompression() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "packed VARBINARY(10) COMPRESSED NOT NULL DEFAULT 'ab', note VARCHAR(10) COMPRESSED)");
        }
        SchemaDefinition same = compressedDefinition("VARBINARY(10) NOT NULL DEFAULT 'ab'", "VARCHAR(10)");
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer(true).synchronizeWithResult(connection, same);
            assertThat(result.pendingSql()).isEmpty();
            assertThat(result.changed()).isFalse();
        }
        SchemaDefinition widened = compressedDefinition("VARBINARY(20) NOT NULL DEFAULT 'ab'", "VARCHAR(20)");
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, widened);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).hasSize(2).allMatch(sql -> sql.contains("COMPRESSED attribute"));
        }
        // Metadata reads are scoped to the configured schema, not the session's current database.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            String schema = connection.getCatalog();
            statement.execute("USE information_schema");
            assertThat(SchemaSnapshotWriter.mysqlDataTypes(connection, schema, "maria_items"))
                    .containsKeys("id", "packed", "note");
            assertThat(SchemaSnapshotWriter.mysqlBinaryDefaults(connection, schema, "maria_items", true))
                    .containsEntry("packed", "0x6162");
        }
    }

    @Test
    void aTemporaryTableShadowingTheBaseTableFailsInsteadOfBeingRead() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "raw VARBINARY(4) DEFAULT 'ab')");
            statement.execute("CREATE TEMPORARY TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, "
                    + "raw VARBINARY(4) DEFAULT 'zz')");
            SchemaDefinition declared = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                    new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                            List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                    new SchemaDefinition.ColumnDef("raw", "VARBINARY(4) DEFAULT 'ab'")),
                            List.of())),
                    List.of());
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> synchronizer().synchronizeWithResult(connection, declared))
                    .hasStackTraceContaining("TEMPORARY table named maria_items");
            statement.execute("DROP TEMPORARY TABLE maria_items");

            // Without binary defaults the check runs before the first DDL on the table.
            statement.execute("DROP TABLE maria_items");
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY)");
            statement.execute("CREATE TEMPORARY TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY)");
            SchemaDefinition added = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                    new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                            List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                    new SchemaDefinition.ColumnDef("note", "VARCHAR(20)")),
                            List.of())),
                    List.of());
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> synchronizer().synchronizeWithResult(connection, added))
                    .hasStackTraceContaining("TEMPORARY table named maria_items");
            statement.execute("DROP TEMPORARY TABLE maria_items");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                     + "WHERE table_schema = DATABASE() AND table_name = 'maria_items'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }

        // A missing declared table: CREATE TABLE IF NOT EXISTS would create it behind the TEMPORARY one.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE maria_items");
            statement.execute("CREATE TEMPORARY TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY)");
            SchemaDefinition missing = new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                    new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                            List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")), List.of())),
                    List.of());
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> synchronizer().synchronizeWithResult(connection, missing))
                    .hasStackTraceContaining("TEMPORARY table named maria_items");
            statement.execute("DROP TEMPORARY TABLE maria_items");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = DATABASE() AND table_name = 'maria_items'")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    private SchemaDefinition compressedDefinition(String packed, String note) {
        return new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("packed", packed),
                                new SchemaDefinition.ColumnDef("note", note)),
                        List.of())),
                List.of());
    }

    @Test
    void metadataIsReadFromTheConfiguredSchemaWhateverTheDriverCaches() throws Exception {
        String catalog;
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE maria_items (id BIGINT NOT NULL PRIMARY KEY, label VARCHAR(40))");
            catalog = connection.getCatalog();
        }
        SchemaDefinition narrower = labelDefinition("VARCHAR(20)");
        String url = System.getProperty("schema.test.mariadb.jdbc.url");
        String user = System.getProperty("schema.test.mariadb.jdbc.user");
        String password = System.getProperty("schema.test.mariadb.jdbc.password");
        try (Connection switched = DriverManager.getConnection(
                url.replaceFirst("(//[^/]+/)[^?]*", "$1information_schema"), user, password)) {
            switched.createStatement().execute("USE `" + catalog + "`");
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(switched, narrower);
            assertThat(result.tablesCreated()).isZero();
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).singleElement().asString().contains("MODIFY COLUMN label");
        }
        String schemaTerm = url + (url.contains("?") ? "&" : "?")
                + (url.startsWith("jdbc:mariadb:") ? "useCatalogTerm=Schema" : "databaseTerm=SCHEMA");
        try (Connection schemaMode = DriverManager.getConnection(schemaTerm, user, password)) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(schemaMode,
                    labelDefinition("VARCHAR(40)"));
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_type FROM information_schema.columns WHERE "
                     + "table_schema = DATABASE() AND table_name = 'maria_items' AND column_name = 'label'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("varchar(40)");
        }
    }

    private SchemaDefinition labelDefinition(String label) {
        return new SchemaDefinition(2, "mariadb", Map.of("maria_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS maria_items (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("label", label)), List.of())),
                List.of());
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
