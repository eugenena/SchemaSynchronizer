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
                + "touched DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), "
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
                        new SchemaDefinition.ColumnDef("touched",
                                "DATETIME(3) DEFAULT NOW(3) ON UPDATE CURRENT_TIMESTAMP(3)")),
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
                "DEFAULT 0x00FF", "DEFAULT 0x6100", "ON UPDATE CURRENT_TIMESTAMP(3)");
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
