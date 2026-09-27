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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "schema.test.mysql.jdbc.url", matches = ".+")
class SchemaSynchronizerMySqlIntegrationTest {

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS mysql_items");
            statement.execute("DROP TABLE IF EXISTS mysql_flag");
            statement.execute("DROP TABLE IF EXISTS mysql_strict");
            statement.execute("DROP TABLE IF EXISTS mysqlxstrict");
            statement.execute("DROP TABLE IF EXISTS schema_synchronizer_history");
        }
    }

    @Test
    void namesAreNotPatternsAndDecimalPrecisionIsCompared(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "amount DECIMAL(10,4) UNSIGNED NOT NULL, plain DECIMAL, wide DECIMAL(12,4), uns INT UNSIGNED DEFAULT 1)");
            // `_` is a metadata wildcard: this table must not lend its columns to mysql_strict.
            statement.execute("CREATE TABLE mysqlxstrict (id BIGINT NOT NULL PRIMARY KEY, extra INT)");
        }
        SchemaDefinition.TableDef sibling = new SchemaDefinition.TableDef(
                "CREATE TABLE IF NOT EXISTS mysqlxstrict (id BIGINT NOT NULL PRIMARY KEY, extra INT)",
                List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("extra", "INT")), List.of());
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("amount", "DECIMAL(10,2) UNSIGNED"),
                                new SchemaDefinition.ColumnDef("plain", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("wide", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 5"),
                                new SchemaDefinition.ColumnDef("extra", "INT")), List.of()),
                "mysqlxstrict", sibling), List.of());
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
                     + "AND table_name = 'mysql_strict' ORDER BY ordinal_position")) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (rows.next()) {
                columns.put(rows.getString(1), rows.getString(2) + " " + rows.getString(3) + " " + rows.getString(4));
            }
            assertThat(columns).containsEntry("amount", "decimal(10,4) unsigned NO null")
                    .containsEntry("plain", "decimal(10,0) YES null")
                    .containsEntry("wide", "decimal(12,4) YES null")
                    .containsEntry("uns", "int unsigned YES 5")
                    .containsEntry("extra", "int YES null");
        }
        SchemaDefinition converged = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("amount", "DECIMAL(10,4) UNSIGNED NOT NULL"),
                                new SchemaDefinition.ColumnDef("plain", "DECIMAL"),
                                new SchemaDefinition.ColumnDef("wide", "DECIMAL(12,4)"),
                                new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 5"),
                                new SchemaDefinition.ColumnDef("extra", "INT")), List.of()),
                "mysqlxstrict", sibling), List.of());
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
            SchemaSnapshotWriter.writeSnapshot(connection, catalog(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("mysql_strict").columns())
                .extracting(SchemaDefinition.ColumnDef::name)
                .containsExactly("id", "amount", "plain", "wide", "uns", "extra");
    }

    @Test
    void zerofillDisplayWidthIsNeitherResetNorSnapshotted(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "narrow INT(5) ZEROFILL NOT NULL, standard INT ZEROFILL NOT NULL)");
        }
        SchemaDefinition relaxed = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
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
                     + "WHERE table_schema = DATABASE() AND table_name = 'mysql_strict' AND column_name <> 'id'")) {
            Map<String, String> columns = new java.util.HashMap<>();
            while (rows.next()) {
                columns.put(rows.getString(1), rows.getString(2) + " " + rows.getString(3));
            }
            assertThat(columns).containsEntry("narrow", "int(5) unsigned zerofill NO")
                    .containsEntry("standard", "int(10) unsigned zerofill YES");
        }
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> SchemaSnapshotWriter.writeSnapshot(connection, catalog(),
                    tempDir.resolve("zerofill.json"))).hasMessageContaining("ZEROFILL display width (5)");
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
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, catalog(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.declaredDialect()).isEqualTo(DatabaseDialect.MYSQL);
        assertThat(serialized.tables()).containsKey("mysql_items");

        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).pendingSql())
                    .anyMatch(sql -> sql.contains("DROP COLUMN notes"));
        }
    }

    @Test
    void strictResyncAndSnapshotReplayHaveNoPendingDrift(@TempDir Path tempDir) throws Exception {
        String create = "CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL AUTO_INCREMENT, "
                + "code INT NOT NULL, label VARCHAR(40) NOT NULL DEFAULT 'new', qty INT DEFAULT 0, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, nick NVARCHAR(20), stamp DATETIME(3), "
                + "marker VARCHAR(20) DEFAULT 'CURRENT_TIMESTAMP', alias NATIONAL VARCHAR(10), "
                + "token VARCHAR(36) DEFAULT (uuid()), born DATE DEFAULT (CURRENT_DATE), "
                + "tag VARCHAR(10) DEFAULT (concat('a','b')), quip VARCHAR(20) DEFAULT (concat('it''s','a\\\\b')), "
                + "ts3 DATETIME(3) DEFAULT NOW(3), price DECIMAL(10,2) DEFAULT 1, "
                + "fixed_at DATETIME(3) DEFAULT '2020-01-01 00:00:00', flag TINYINT DEFAULT FALSE, "
                + "word VARCHAR(10) DEFAULT 'NULL', note VARCHAR(10) DEFAULT NULL, "
                + "motto VARCHAR(20) DEFAULT 'my IDENTITY', seen TIMESTAMP NULL, "
                + "ok BOOLEAN DEFAULT FALSE, bits BIT(8) DEFAULT b'101', "
                + "raw VARBINARY(10) DEFAULT 'ab', fixed BINARY(4) DEFAULT 'ab', "
                + "zeros VARBINARY(8) DEFAULT 0x00275C0A, padded BINARY(4) DEFAULT 0x0041, "
                + "required VARBINARY(8) NOT NULL DEFAULT 0x00FF, one BINARY(2) NOT NULL DEFAULT 'a', "
                + "digits VARBINARY(4) DEFAULT 007, "
                + "touched DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
                + "latin VARCHAR(10) CHARACTER SET latin1 COLLATE latin1_bin DEFAULT 'x' COMMENT 'it''s', "
                + "hidden INT INVISIBLE, yes_no TINYINT(1) DEFAULT 0, "
                + "due DATETIME DEFAULT (CURRENT_TIMESTAMP + INTERVAL 1 DAY), "
                + "flags TINYINT(1) UNSIGNED DEFAULT 1, huge DOUBLE DEFAULT 10E299, uns INT UNSIGNED DEFAULT 1.0, "
                + "amount DECIMAL(5,2) UNSIGNED DEFAULT 1.50, "
                + "zfill DECIMAL(8,2) UNSIGNED ZEROFILL DEFAULT 1.5, zint INT UNSIGNED ZEROFILL, "
                + "PRIMARY KEY (id))";
        SchemaDefinition initial = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(create, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL AUTO_INCREMENT"),
                        new SchemaDefinition.ColumnDef("code", "INT NOT NULL"),
                        new SchemaDefinition.ColumnDef("label", "VARCHAR(40) NOT NULL DEFAULT 'new'"),
                        new SchemaDefinition.ColumnDef("qty", "INT DEFAULT 0"),
                        new SchemaDefinition.ColumnDef("created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"),
                        new SchemaDefinition.ColumnDef("nick", "NVARCHAR(20)"),
                        new SchemaDefinition.ColumnDef("stamp", "DATETIME(3)"),
                        new SchemaDefinition.ColumnDef("marker", "VARCHAR(20) DEFAULT 'CURRENT_TIMESTAMP'"),
                        new SchemaDefinition.ColumnDef("alias", "NATIONAL VARCHAR(10)"),
                        new SchemaDefinition.ColumnDef("token", "VARCHAR(36) DEFAULT (uuid())"),
                        new SchemaDefinition.ColumnDef("born", "DATE DEFAULT (CURRENT_DATE)"),
                        new SchemaDefinition.ColumnDef("tag", "VARCHAR(10) DEFAULT (concat(_utf8mb4'a',_utf8mb4'b'))"),
                        new SchemaDefinition.ColumnDef("quip",
                                "VARCHAR(20) DEFAULT (concat(_utf8mb4'it''s',_utf8mb4'a\\\\b'))"),
                        new SchemaDefinition.ColumnDef("ts3", "DATETIME(3) DEFAULT NOW(3)"),
                        new SchemaDefinition.ColumnDef("price", "DECIMAL(10,2) DEFAULT 1"),
                        new SchemaDefinition.ColumnDef("fixed_at", "DATETIME(3) DEFAULT '2020-01-01 00:00:00'"),
                        new SchemaDefinition.ColumnDef("flag", "TINYINT DEFAULT FALSE"),
                        new SchemaDefinition.ColumnDef("word", "VARCHAR(10) DEFAULT 'NULL'"),
                        new SchemaDefinition.ColumnDef("note", "VARCHAR(10) DEFAULT NULL"),
                        new SchemaDefinition.ColumnDef("motto", "VARCHAR(20) DEFAULT 'my IDENTITY'"),
                        new SchemaDefinition.ColumnDef("seen", "TIMESTAMP NULL"),
                        new SchemaDefinition.ColumnDef("ok", "BOOLEAN DEFAULT FALSE"),
                        new SchemaDefinition.ColumnDef("bits", "BIT(8) DEFAULT b'101'"),
                        new SchemaDefinition.ColumnDef("raw", "VARBINARY(10) DEFAULT 'ab'"),
                        new SchemaDefinition.ColumnDef("fixed", "BINARY(4) DEFAULT 'ab'"),
                        new SchemaDefinition.ColumnDef("zeros", "VARBINARY(8) DEFAULT X'00275C0A'"),
                        new SchemaDefinition.ColumnDef("padded", "BINARY(4) DEFAULT 0x0041"),
                        new SchemaDefinition.ColumnDef("required", "VARBINARY(8) NOT NULL DEFAULT 0x00FF"),
                        new SchemaDefinition.ColumnDef("one", "BINARY(2) NOT NULL DEFAULT 'a'"),
                        new SchemaDefinition.ColumnDef("digits", "VARBINARY(4) DEFAULT 007"),
                        new SchemaDefinition.ColumnDef("touched",
                                "DATETIME(3) DEFAULT NOW(3) ON UPDATE CURRENT_TIMESTAMP(3)"),
                        new SchemaDefinition.ColumnDef("latin", "VARCHAR(10) DEFAULT 'x'"),
                        new SchemaDefinition.ColumnDef("hidden", "INT"),
                        new SchemaDefinition.ColumnDef("yes_no", "TINYINT(1) DEFAULT 0"),
                        new SchemaDefinition.ColumnDef("due", "DATETIME DEFAULT (now() + interval 1 day)"),
                        new SchemaDefinition.ColumnDef("flags", "TINYINT(1) UNSIGNED DEFAULT 1"),
                        new SchemaDefinition.ColumnDef("huge", "DOUBLE DEFAULT 10E299"),
                        new SchemaDefinition.ColumnDef("uns", "INT UNSIGNED DEFAULT 1.0"),
                        new SchemaDefinition.ColumnDef("amount", "DECIMAL(5,2) UNSIGNED DEFAULT 1.5"),
                        new SchemaDefinition.ColumnDef("zfill", "DECIMAL(8,2) UNSIGNED ZEROFILL DEFAULT 1.5"),
                        new SchemaDefinition.ColumnDef("zint", "INT UNSIGNED ZEROFILL")),
                        List.of("CREATE INDEX idx_mysql_strict_label ON mysql_strict (label, code DESC)"))),
                List.of());

        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, initial).tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, initial);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }

        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, catalog(), snapshot);
        }
        assertThat(java.nio.file.Files.readString(snapshot)).contains("DEFAULT 0x00275C0A", "DEFAULT 0x00410000",
                "DEFAULT 0x00FF", "DEFAULT 0x6100", "ON UPDATE CURRENT_TIMESTAMP(3)",
                "CHARACTER SET latin1 COLLATE latin1_bin", "COMMENT 'it''s'", "INT INVISIBLE",
                // MySQL 8.0.19+ keeps the TINYINT(1) display width only when signed (MariaDB also when unsigned).
                "flags TINYINT UNSIGNED DEFAULT 1", "amount NUMERIC(5,2) UNSIGNED DEFAULT 1.50",
                // Connector/J omits ZEROFILL from TYPE_NAME; the snapshot restores it from COLUMN_TYPE.
                "zfill NUMERIC(8,2) UNSIGNED ZEROFILL", "zint INT UNSIGNED ZEROFILL");
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE mysql_strict");
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, serialized).tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult replayed = synchronizer(true).synchronizeWithResult(connection, serialized);
            assertThat(replayed.pendingSql()).isEmpty();
            assertThat(replayed.changed()).isFalse();
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name, column_type, collation_name, column_comment, "
                     + "extra FROM information_schema.columns WHERE table_schema = DATABASE() "
                     + "AND table_name = 'mysql_strict' AND column_name IN ('latin', 'hidden', 'yes_no', 'flags') "
                     + "ORDER BY column_name")) {
            List<String> replayedColumns = new java.util.ArrayList<>();
            while (rows.next()) {
                replayedColumns.add(rows.getString(1) + " " + rows.getString(2) + " " + rows.getString(3) + " "
                        + rows.getString(4) + " " + rows.getString(5));
            }
            assertThat(replayedColumns).containsExactly("flags tinyint unsigned null  ", "hidden int null  INVISIBLE",
                    "latin varchar(10) latin1_bin it's ", "yes_no tinyint(1) null  ");
        }
    }

    @Test
    void aModifyNeverResetsTinyint1DisplayWidth() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "a TINYINT(1) NOT NULL, b TINYINT(1) NOT NULL, c TINYINT DEFAULT 0, d TINYINT(1) NOT NULL)");
        }
        SchemaDefinition relaxed = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("a", "TINYINT"),
                                new SchemaDefinition.ColumnDef("b", "BOOLEAN"),
                                new SchemaDefinition.ColumnDef("c", "BOOLEAN DEFAULT 1"),
                                // Another integer type is not a display-width change (BOOLEAN to SMALLINT is a type change).
                                new SchemaDefinition.ColumnDef("d", "SMALLINT NOT NULL")),
                        List.of())),
                List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, relaxed);
            assertThat(result.columnsAltered()).isEqualTo(1);
            assertThat(result.pendingSql()).hasSize(3);
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN d SMALLINT")
                    && !sql.contains("display width"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN a")
                    && sql.contains("TINYINT(1) display width"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("c BOOLEAN DEFAULT 1")
                    && sql.contains("display width to (1)"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name, column_type, is_nullable "
                     + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                     + "AND table_name = 'mysql_strict' AND column_name IN ('a', 'b', 'c', 'd') ORDER BY column_name")) {
            List<String> columns = new java.util.ArrayList<>();
            while (rows.next()) {
                columns.add(rows.getString(1) + " " + rows.getString(2) + " " + rows.getString(3));
            }
            assertThat(columns).containsExactly("a tinyint(1) NO", "b tinyint(1) YES", "c tinyint YES", "d tinyint(1) NO");
        }
    }

    @Test
    void appliedDefaultsThatTheServerRewritesConvergeOnTheNextSync() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, ts3 DATETIME(3), "
                    + "price DECIMAL(10,2), fixed_at DATETIME(3), flag TINYINT)");
        }
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("ts3", "DATETIME(3) DEFAULT NOW(3)"),
                                new SchemaDefinition.ColumnDef("price", "DECIMAL(10,2) DEFAULT 1"),
                                new SchemaDefinition.ColumnDef("fixed_at", "DATETIME(3) DEFAULT '2020-01-01 00:00:00'"),
                                new SchemaDefinition.ColumnDef("flag", "TINYINT DEFAULT FALSE")),
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
    void timestampDeclarationsAreRefusedWhenImplicitTimestampDefaultsAreOn() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "ts TIMESTAMP NOT NULL DEFAULT '2020-01-01 00:00:00')");
        }
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("ts", "TIMESTAMP NOT NULL")),
                        List.of())),
                List.of());
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            assertThatThrownBy(() -> synchronizer().synchronizeWithResult(connection, declared))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("modifying column mysql_strict.ts");
        }
        SchemaDefinition converged = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("ts", "TIMESTAMP NOT NULL DEFAULT '2020-01-01 00:00:00'")),
                        List.of()),
                "mysql_named", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS mysql_named (id BIGINT NOT NULL PRIMARY KEY, `timestamp` DATETIME)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("timestamp", "DATETIME")), List.of())),
                List.of());
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, converged);
            assertThat(result.pendingSql()).isEmpty();
            assertThat(result.tablesCreated()).isEqualTo(1);
        } finally {
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS mysql_named");
            }
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT EXTRA FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mysql_strict' AND COLUMN_NAME = 'ts'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).doesNotContainIgnoringCase("on update");
        }
    }

    @Test
    void timestampRefusalPrecedesCreatedTablesAndChangeSets() throws Exception {
        // The createSql has no TIMESTAMP; the column list adds one right after CREATE TABLE.
        SchemaDefinition.TableDef strict = new SchemaDefinition.TableDef(
                "CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("ts", "TIMESTAMP NULL")), List.of());
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            assertThatThrownBy(() -> synchronizer().synchronizeWithResult(connection,
                    new SchemaDefinition(2, "mysql", Map.of("mysql_strict", strict), List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("creating table mysql_strict");
        }
        assertThat(tableExists("mysql_strict")).isFalse();

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, ts TIMESTAMP NULL)");
        }
        SchemaDefinition.TableDef indexed = new SchemaDefinition.TableDef(strict.createSql(), strict.columns(),
                List.of("CREATE INDEX idx_strict_ts ON mysql_strict (ts)"));
        SchemaDefinition.ChangeSet unrelated = new SchemaDefinition.ChangeSet("001-mysql-flag", "creates mysql_flag",
                List.of("CREATE TABLE IF NOT EXISTS mysql_flag (id BIGINT PRIMARY KEY)"),
                "SELECT COUNT(*) = 1 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mysql_flag'");
        SchemaDefinition.ChangeSet touching = new SchemaDefinition.ChangeSet("002-strict-index", "indexes ts",
                List.of("CREATE INDEX idx_strict_ts ON mysql_strict (ts)"),
                "SELECT COUNT(*) = 1 FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'mysql_strict' AND index_name = 'idx_strict_ts'");
        // A change set that does not name a TIMESTAMP table leaves a matching TIMESTAMP column alone.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection,
                    new SchemaDefinition(2, "mysql", Map.of("mysql_strict", strict), List.of(unrelated)));
            assertThat(result.changeSetsApplied()).isEqualTo(1);
        }
        SchemaDefinition withChange = new SchemaDefinition(2, "mysql", Map.of("mysql_strict", indexed),
                List.of(unrelated, touching));
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            assertThatThrownBy(() -> synchronizer().synchronizeWithResult(connection, withChange))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TIMESTAMP columns of mysql_strict after BEFORE_SCHEMA change set "
                            + "002-strict-index");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics "
                     + "WHERE table_schema = DATABASE() AND index_name = 'idx_strict_ts'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult applied = synchronizer().synchronizeWithResult(connection, withChange);
            assertThat(applied.changeSetsApplied()).isEqualTo(1);
        }
        // Once the change set is recorded, the matching TIMESTAMP column needs no DDL.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("SET SESSION explicit_defaults_for_timestamp = 0");
            SchemaSynchronizationResult again = synchronizer().synchronizeWithResult(connection, withChange);
            assertThat(again.changeSetsApplied()).isZero();
            assertThat(again.columnsAdded() + again.columnsAltered() + again.tablesCreated()).isZero();
            assertThat(again.pendingSql()).containsExactly("DROP TABLE mysql_flag; -- pending: table absent from definition");
        }
    }

    @Test
    void defaultsTheCharsetCannotStoreArePendingInAnySqlMode() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "label VARCHAR(10) DEFAULT 'a', cafe VARCHAR(10) DEFAULT 'a') DEFAULT CHARSET = latin1");
        }
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(null, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("label", "VARCHAR(10) DEFAULT '日本'"),
                        new SchemaDefinition.ColumnDef("cafe", "VARCHAR(10) DEFAULT 'café'"),
                        new SchemaDefinition.ColumnDef("note", "VARCHAR(10) DEFAULT '日本'")),
                        List.of("CREATE INDEX idx_strict_note ON mysql_strict (note)")),
                // A legacy character set that stores the default does not block an unrelated widening.
                "mysqlxstrict", new SchemaDefinition.TableDef(null, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("label", "VARCHAR(20) DEFAULT '中文'")), List.of())),
                List.of());
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysqlxstrict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "label VARCHAR(10) DEFAULT '中文') DEFAULT CHARSET = gbk");
        }
        for (String sqlMode : List.of("''", "'STRICT_TRANS_TABLES'")) {
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                statement.execute("SET SESSION sql_mode = " + sqlMode);
                SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, declared);
                assertThat(result.pendingSql()).hasSize(3);
                assertThat(result.pendingSql()).anySatisfy(line -> assertThat(line)
                        .contains("MODIFY COLUMN label").contains("latin1 character set"));
                assertThat(result.pendingSql()).anySatisfy(line -> assertThat(line)
                        .contains("ADD COLUMN note").contains("latin1 character set"));
                assertThat(result.pendingSql()).anySatisfy(line -> assertThat(line)
                        .contains("CREATE INDEX idx_strict_note").contains("mysql_strict.note was not added"));
            }
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT CHARACTER_MAXIMUM_LENGTH, COLUMN_DEFAULT "
                     + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                     + "AND TABLE_NAME = 'mysqlxstrict' AND COLUMN_NAME = 'label'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(20);
            assertThat(rows.getString(2)).isEqualTo("中文");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COLUMN_NAME, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mysql_strict' ORDER BY COLUMN_NAME")) {
            Map<String, String> defaults = new java.util.HashMap<>();
            while (rows.next()) {
                defaults.put(rows.getString(1), rows.getString(2));
            }
            assertThat(defaults).doesNotContainKey("note");
            assertThat(defaults.get("label")).isEqualTo("a");
            assertThat(defaults.get("cafe")).isEqualTo("café");
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    @Test
    void nationalCharsetCollationAndPrecisionDriftArePending() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "wide VARCHAR(40) CHARACTER SET utf8mb4, "
                    + "binned VARCHAR(40) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin, "
                    + "stamp DATETIME(3), tag VARCHAR(10) DEFAULT (concat('a','b')), "
                    + "tiny TINYINT(1) NOT NULL DEFAULT 0, "
                    + "labeled VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'a', "
                    + "upd DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) "
                    + "DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci");
        }
        String create = "CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)";
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(create, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("wide", "NVARCHAR(40)"),
                        new SchemaDefinition.ColumnDef("binned", "NVARCHAR(80)"),
                        new SchemaDefinition.ColumnDef("stamp", "DATETIME"),
                        new SchemaDefinition.ColumnDef("tag", "VARCHAR(10) DEFAULT (concat('a','b'))"),
                        new SchemaDefinition.ColumnDef("tiny", "BIT(1) DEFAULT 0"),
                        new SchemaDefinition.ColumnDef("labeled", "VARCHAR(20) DEFAULT 'COLLATE'"),
                        new SchemaDefinition.ColumnDef("upd",
                                "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")),
                        List.of())),
                List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(connection, declared);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN wide")
                    && sql.contains("utf8mb4"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN binned")
                    && sql.contains("utf8mb3_bin"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN stamp"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN tag")
                    && sql.contains("is not auto-applied because the server may store it rewritten"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN tiny BIT(1)"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN labeled")
                    && sql.contains("utf8mb4_bin"));
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN upd")
                    && sql.contains("ON UPDATE differs"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COLLATION_NAME, CHARACTER_MAXIMUM_LENGTH "
                     + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                     + "AND TABLE_NAME = 'mysql_strict' AND COLUMN_NAME = 'binned'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("utf8mb3_bin");
            assertThat(rows.getInt(2)).isEqualTo(40);
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mysql_strict' AND COLUMN_NAME = 'labeled'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("utf8mb4_bin");
        }
    }

    @Test
    void binaryDefaultsConvergeForADdlOnlyAccount(@TempDir Path tempDir) throws Exception {
        String adminUser = System.getProperty("schema.test.mysql.jdbc.admin.user");
        assumeTrue(adminUser != null && !adminUser.isBlank(), "needs schema.test.mysql.jdbc.admin.user");
        String catalog = catalog();
        List<SchemaDefinition.ChangeSet> history = List.of(new SchemaDefinition.ChangeSet("001-mysql-flag",
                "creates the history table",
                List.of("CREATE TABLE IF NOT EXISTS mysql_flag (id BIGINT PRIMARY KEY)"),
                "SELECT COUNT(*) = 1 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'mysql_flag'",
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA));
        try (Connection connection = connection()) {
            synchronizer().synchronizeWithResult(connection, new SchemaDefinition(2, "mysql", Map.of(), history));
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE mysql_flag");
        }
        try (Connection admin = adminConnection(adminUser); var statement = admin.createStatement()) {
            statement.execute("DROP USER IF EXISTS ss_ddl_only");
            statement.execute("CREATE USER ss_ddl_only IDENTIFIED BY 'ss_ddl_only_pw'");
            statement.execute("GRANT CREATE, ALTER, INDEX, REFERENCES ON `" + catalog + "`.* TO ss_ddl_only");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + catalog
                    + "`.schema_synchronizer_history TO ss_ddl_only");
        }
        String create = "CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                + "nn VARBINARY(8) NOT NULL DEFAULT 0x00FF, one VARBINARY(4) DEFAULT 'ab', "
                + "fixed BINARY(4) NOT NULL DEFAULT 'ab')";
        List<SchemaDefinition.ColumnDef> columns = List.of(
                new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                new SchemaDefinition.ColumnDef("nn", "VARBINARY(8) NOT NULL DEFAULT 0x00FF"),
                new SchemaDefinition.ColumnDef("one", "VARBINARY(4) DEFAULT 'ab'"),
                new SchemaDefinition.ColumnDef("fixed", "BINARY(4) NOT NULL DEFAULT 'ab'"));
        SchemaDefinition declared = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(create, columns, List.of())), history);
        List<SchemaDefinition.ColumnDef> widened = new java.util.ArrayList<>(columns);
        widened.add(new SchemaDefinition.ColumnDef("added", "VARBINARY(4) NOT NULL DEFAULT X'FF80'"));
        SchemaDefinition added = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(create, widened, List.of())), history);
        try {
            try (Connection ddlOnly = ddlOnlyConnection()) {
                assertThat(synchronizer(true).synchronizeWithResult(ddlOnly, declared).tablesCreated()).isEqualTo(1);
            }
            try (Connection ddlOnly = ddlOnlyConnection()) {
                SchemaSynchronizationResult again = synchronizer(true).synchronizeWithResult(ddlOnly, declared);
                assertThat(again.changed()).isFalse();
                assertThat(again.pendingSql()).isEmpty();
            }
            try (Connection ddlOnly = ddlOnlyConnection()) {
                assertThat(synchronizer(true).synchronizeWithResult(ddlOnly, added).columnsAdded()).isEqualTo(1);
            }
            try (Connection ddlOnly = ddlOnlyConnection()) {
                assertThat(synchronizer(true).synchronizeWithResult(ddlOnly, added).pendingSql()).isEmpty();
            }
            // An empty table has no row for DEFAULT(col); a different NOT NULL default is still drift.
            List<SchemaDefinition.ColumnDef> drifted = new java.util.ArrayList<>(widened);
            drifted.set(1, new SchemaDefinition.ColumnDef("nn", "VARBINARY(8) NOT NULL DEFAULT ''"));
            try (Connection ddlOnly = ddlOnlyConnection()) {
                assertThat(synchronizer().synchronizeWithResult(ddlOnly, new SchemaDefinition(2, "mysql",
                        Map.of("mysql_strict", new SchemaDefinition.TableDef(create, drifted, List.of())), history))
                        .pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN nn"));
            }
            Path snapshot = tempDir.resolve("ddl-only.json");
            try (Connection ddlOnly = ddlOnlyConnection()) {
                SchemaSnapshotWriter.writeSnapshot(ddlOnly, catalog, snapshot);
            }
            assertThat(java.nio.file.Files.readString(snapshot)).contains("DEFAULT 0x00FF",
                    "DEFAULT 0x6162", "DEFAULT 0x61620000", "DEFAULT 0xFF80");
        } finally {
            try (Connection admin = adminConnection(adminUser); var statement = admin.createStatement()) {
                statement.execute("DROP USER IF EXISTS ss_ddl_only");
            }
        }
    }

    @Test
    void nonAsciiBinaryStringDefaultsAreNeverRewrittenByAWiden() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                    + "v VARBINARY(4) NOT NULL DEFAULT X'C3A9')");
        }
        String create = "CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)";
        SchemaDefinition widened = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef(create, List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("v", "VARBINARY(8) NOT NULL DEFAULT 'é'")), List.of())),
                List.of());
        String url = System.getProperty("schema.test.mysql.jdbc.url");
        try (Connection latin1 = DriverManager.getConnection(
                url + (url.contains("?") ? "&" : "?") + "characterEncoding=ISO-8859-1",
                System.getProperty("schema.test.mysql.jdbc.user"),
                System.getProperty("schema.test.mysql.jdbc.password"))) {
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(latin1, widened);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("MODIFY COLUMN v"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW CREATE TABLE mysql_strict")) {
            assertThat(rows.next()).isTrue();
            // Rendered through the UTF-8 session: still the two bytes C3 A9 and still VARBINARY(4).
            assertThat(rows.getString(2)).contains("varbinary(4) NOT NULL DEFAULT 'é'");
        }
    }

    @Test
    void metadataIsReadFromTheConfiguredSchemaWhateverTheDriverCaches() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mysql_strict (id BIGINT NOT NULL PRIMARY KEY, label VARCHAR(40))");
        }
        String catalog = catalog();
        SchemaDefinition narrower = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("label", "VARCHAR(20)")), List.of())),
                List.of());
        String url = System.getProperty("schema.test.mysql.jdbc.url");
        // Connector/J keeps the URL database as getCatalog() after USE.
        String otherDatabase = url.replaceFirst("(//[^/]+/)[^?]*", "$1information_schema");
        try (Connection switched = DriverManager.getConnection(otherDatabase,
                System.getProperty("schema.test.mysql.jdbc.user"), System.getProperty("schema.test.mysql.jdbc.password"))) {
            switched.createStatement().execute("USE `" + catalog + "`");
            SchemaSynchronizationResult result = synchronizer().synchronizeWithResult(switched, narrower);
            assertThat(result.tablesCreated()).isZero();
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).singleElement().asString().contains("MODIFY COLUMN label");
        }
        String schemaTerm = url + (url.contains("?") ? "&" : "?")
                + (url.startsWith("jdbc:mariadb:") ? "useCatalogTerm=Schema" : "databaseTerm=SCHEMA");
        SchemaDefinition same = new SchemaDefinition(2, "mysql", Map.of("mysql_strict",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS mysql_strict (id BIGINT NOT NULL PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("label", "VARCHAR(40)")), List.of())),
                List.of());
        // In schema-term mode the schema argument is a LIKE pattern: a sibling database whose name
        // differs only where the schema has `_` must stay invisible.
        String adminUser = System.getProperty("schema.test.mysql.jdbc.admin.user");
        String decoy = catalog.contains("_") && adminUser != null && !adminUser.isBlank()
                ? catalog.replaceFirst("_", "x") : null;
        try {
            if (decoy != null) {
                try (Connection admin = adminConnection(adminUser); var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS `" + decoy + "`");
                    statement.execute("CREATE DATABASE `" + decoy + "`");
                    statement.execute("CREATE TABLE `" + decoy + "`.mysql_strict (id BIGINT NOT NULL PRIMARY KEY, "
                            + "label VARCHAR(10), ghost INT NOT NULL)");
                    statement.execute("CREATE TABLE `" + decoy + "`.ghost_table (id INT)");
                    statement.execute("GRANT SELECT ON `" + decoy + "`.* TO "
                            + System.getProperty("schema.test.mysql.jdbc.user"));
                }
            }
            try (Connection schemaMode = DriverManager.getConnection(schemaTerm,
                    System.getProperty("schema.test.mysql.jdbc.user"), System.getProperty("schema.test.mysql.jdbc.password"))) {
                SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(schemaMode, same);
                assertThat(strict.pendingSql()).isEmpty();
                assertThat(strict.changed()).isFalse();
            }
        } finally {
            if (decoy != null) {
                try (Connection admin = adminConnection(adminUser); var statement = admin.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS `" + decoy + "`");
                }
            }
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_type FROM information_schema.columns WHERE "
                     + "table_schema = DATABASE() AND table_name = 'mysql_strict' AND column_name = 'label'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("varchar(40)");
        }
    }

    private Connection adminConnection(String adminUser) throws Exception {
        return DriverManager.getConnection(System.getProperty("schema.test.mysql.jdbc.url"),
                adminUser, System.getProperty("schema.test.mysql.jdbc.admin.password"));
    }

    private Connection ddlOnlyConnection() throws Exception {
        return DriverManager.getConnection(System.getProperty("schema.test.mysql.jdbc.url"),
                "ss_ddl_only", "ss_ddl_only_pw");
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
        return synchronizer(false);
    }

    private SchemaSynchronizer synchronizer(boolean failOnPending) throws Exception {
        String catalog = catalog();
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(catalog, "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
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
