// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@LiveDatabase(engine = "Oracle", properties = {
        "schema.test.oracle.jdbc.url",
        "schema.test.oracle.jdbc.user",
        "schema.test.oracle.jdbc.password"})
class SchemaSynchronizerOracleIntegrationTest {

    @BeforeEach
    @AfterEach
    void cleanDatabase() throws Exception {
        try (Connection connection = connection()) {
            LiveTestSupport.cleanOracleSchema(connection);
        }
    }

    @Test
    void historyDescriptionBytesAreMeasuredInTheDatabaseCharacterSet() throws Exception {
        try (Connection connection = connection()) {
            String charset = LiveTestSupport.scalar(connection,
                    "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'");
            int expected = "AL32UTF8".equals(charset) ? 501 : 251;
            assertThat(ChangeSetExecutor.storedBytes(connection, DatabaseDialect.ORACLE).of("é".repeat(250) + "a"))
                    .as(charset).isEqualTo(expected);
        }
    }

    @Test
    void tableNamesAreNotMetadataPatterns() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) NOT NULL, PRIMARY KEY (id))");
            // `_` is a metadata wildcard: this table must not lend its columns to oracle_items.
            statement.execute("CREATE TABLE oraclexitems (id NUMBER(10) NOT NULL, extra NUMBER(10), PRIMARY KEY (id))");
        }
        List<SchemaDefinition.ColumnDef> columns = List.of(new SchemaDefinition.ColumnDef("id", "NUMBER(10) NOT NULL"),
                new SchemaDefinition.ColumnDef("extra", "NUMBER(10)"));
        SchemaDefinition declared = new SchemaDefinition(2, "oracle", Map.of(
                "oracle_items", new SchemaDefinition.TableDef(
                        "CREATE TABLE oracle_items (id NUMBER(10) NOT NULL, PRIMARY KEY (id))", columns, List.of()),
                "oraclexitems", new SchemaDefinition.TableDef(
                        "CREATE TABLE oraclexitems (id NUMBER(10) NOT NULL, extra NUMBER(10), PRIMARY KEY (id))",
                        columns, List.of())), List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer(true).synchronizeWithResult(connection, declared);
            assertThat(result.columnsAdded()).isEqualTo(1);
            assertThat(result.pendingSql()).isEmpty();
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, declared).changed()).isFalse();
        }

        // A bare DEC is NUMBER(38,0): it matches that column and never rounds a wider-scale one.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE oracle_items ADD (plain NUMBER(38,0), wide NUMBER(20,4))");
            statement.execute("INSERT INTO oracle_items (id, wide) VALUES (1, 1.2345)");
        }
        List<SchemaDefinition.ColumnDef> numeric = new java.util.ArrayList<>(columns);
        numeric.add(new SchemaDefinition.ColumnDef("plain", "DEC"));
        numeric.add(new SchemaDefinition.ColumnDef("wide", "DEC"));
        SchemaDefinition bare = new SchemaDefinition(2, "oracle", Map.of(
                "oracle_items", new SchemaDefinition.TableDef(
                        "CREATE TABLE oracle_items (id NUMBER(10) NOT NULL, PRIMARY KEY (id))", numeric, List.of()),
                "oraclexitems", declared.tables().get("oraclexitems")), List.of());
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer(false).synchronizeWithResult(connection, bare);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).singleElement().asString().contains("\"WIDE\"");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT wide FROM oracle_items WHERE id = 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("1.2345");
        }
    }

    @Test
    void createsSerializesReplaysWidensIndexesAndReportsDestructiveDrift(@TempDir Path tempDir) throws Exception {
        SchemaSynchronizer synchronizer = synchronizer();
        SchemaDefinition initial = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR2(40) NOT NULL")));

        try (Connection connection = connection()) {
            assertThat(DatabaseDialect.detect(connection.getMetaData())).isEqualTo(DatabaseDialect.ORACLE);
            SchemaSynchronizationResult first = synchronizer.synchronizeWithResult(connection, initial);
            assertThat(first.tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).changed()).isFalse();
        }

        SchemaDefinition additive = definition(List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR2(100) NOT NULL"),
                new SchemaDefinition.ColumnDef("notes", "VARCHAR2(255)")));
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
            SchemaSnapshotWriter.writeSnapshot(connection, schema(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.declaredDialect()).isEqualTo(DatabaseDialect.ORACLE);
        assertThat(serialized.tables()).containsKey("oracle_items");

        try (Connection connection = connection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, initial).pendingSql())
                    .anyMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("drop column \"notes\""));
        }
    }

    @Test
    void strictResyncAndSnapshotReplayHaveNoPendingDrift(@TempDir Path tempDir) throws Exception {
        String create = "CREATE TABLE oracle_strict (id NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL, "
                + "code INTEGER NOT NULL, label VARCHAR2(40) DEFAULT 'new' NOT NULL, "
                + "qty NUMBER(10,2) DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "national_name NVARCHAR2(50), amount NUMBER, total NUMERIC, ratio DOUBLE PRECISION, "
                + "flag CHAR(1), token RAW(16), zoned TIMESTAMP(3) WITH TIME ZONE, "
                + "local_ts TIMESTAMP WITH LOCAL TIME ZONE, coarse TIMESTAMP(0), PRIMARY KEY (id))";
        List<String> indexes = List.of(
                "CREATE INDEX idx_oracle_strict_label ON oracle_strict (label, code)",
                "CREATE UNIQUE INDEX idx_oracle_strict_code ON oracle_strict (code);");
        SchemaDefinition initial = strictDefinition(create, List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL"),
                new SchemaDefinition.ColumnDef("code", "INTEGER NOT NULL"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR2(40) DEFAULT 'new' NOT NULL"),
                new SchemaDefinition.ColumnDef("qty", "NUMBER(10,2) DEFAULT 0"),
                new SchemaDefinition.ColumnDef("created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP"),
                new SchemaDefinition.ColumnDef("national_name", "NVARCHAR2(50)"),
                new SchemaDefinition.ColumnDef("amount", "NUMBER"),
                new SchemaDefinition.ColumnDef("total", "NUMERIC"),
                new SchemaDefinition.ColumnDef("ratio", "DOUBLE PRECISION"),
                new SchemaDefinition.ColumnDef("flag", "CHAR(1)"),
                new SchemaDefinition.ColumnDef("token", "RAW(16)"),
                new SchemaDefinition.ColumnDef("zoned", "TIMESTAMP(3) WITH TIME ZONE"),
                new SchemaDefinition.ColumnDef("local_ts", "TIMESTAMP WITH LOCAL TIME ZONE"),
                new SchemaDefinition.ColumnDef("coarse", "TIMESTAMP(0)")), indexes);

        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, initial).tablesCreated()).isEqualTo(1);
            try (var statement = connection.createStatement()) {
                // Live-only function-based index: omitted from reconstruction, never reported as drift.
                statement.execute("CREATE INDEX idx_oracle_strict_desc ON oracle_strict (created_at DESC)");
            }
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true).synchronizeWithResult(connection, initial);
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }

        SchemaDefinition relaxed = strictDefinition(create, List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL"),
                new SchemaDefinition.ColumnDef("code", "INTEGER"),
                new SchemaDefinition.ColumnDef("label", "VARCHAR2(100) DEFAULT 'new' NOT NULL"),
                new SchemaDefinition.ColumnDef("qty", "NUMBER(10,2) DEFAULT 1"),
                new SchemaDefinition.ColumnDef("created_at", "TIMESTAMP"),
                new SchemaDefinition.ColumnDef("national_name", "NVARCHAR2(50)"),
                new SchemaDefinition.ColumnDef("amount", "NUMBER"),
                new SchemaDefinition.ColumnDef("total", "NUMERIC"),
                new SchemaDefinition.ColumnDef("ratio", "DOUBLE PRECISION"),
                new SchemaDefinition.ColumnDef("flag", "CHAR(1)"),
                new SchemaDefinition.ColumnDef("token", "RAW(16)"),
                new SchemaDefinition.ColumnDef("zoned", "TIMESTAMP(3) WITH TIME ZONE"),
                new SchemaDefinition.ColumnDef("local_ts", "TIMESTAMP WITH LOCAL TIME ZONE"),
                new SchemaDefinition.ColumnDef("coarse", "TIMESTAMP(0)")), indexes);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult altered = synchronizer(true).synchronizeWithResult(connection, relaxed);
            assertThat(altered.pendingSql()).isEmpty();
            assertThat(altered.columnsAltered()).isEqualTo(4);
        }
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, relaxed).changed()).isFalse();
        }

        List<SchemaDefinition.ColumnDef> finer = relaxed.tables().get("oracle_strict").columns().stream()
                .map(column -> switch (column.name()) {
                    case "zoned" -> new SchemaDefinition.ColumnDef("zoned", "TIMESTAMP(6) WITH TIME ZONE");
                    case "local_ts" -> new SchemaDefinition.ColumnDef("local_ts", "TIMESTAMP WITH TIME ZONE");
                    default -> column;
                })
                .toList();
        try (Connection connection = connection()) {
            SchemaSynchronizationResult drift = synchronizer(false)
                    .synchronizeWithResult(connection, strictDefinition(create, finer, indexes));
            assertThat(drift.columnsAltered()).isZero();
            assertThat(drift.pendingSql())
                    .anyMatch(sql -> sql.contains("MODIFY (\"ZONED\" TIMESTAMP(6) WITH TIME ZONE"))
                    .anyMatch(sql -> sql.contains("MODIFY (\"LOCAL_TS\" TIMESTAMP WITH TIME ZONE"));
        }

        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, schema(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("oracle_strict").indexes())
                .noneMatch(sql -> sql.toUpperCase(java.util.Locale.ROOT).contains("IDX_ORACLE_STRICT_DESC"));
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            dropExisting(statement, "oracle_strict");
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
    void floatBinaryPrecisionAndLocalTimeZonePrecisionAreCompared(@TempDir Path tempDir) throws Exception {
        String create = "CREATE TABLE oracle_strict (id NUMBER(10) NOT NULL, single_col REAL, double_col FLOAT, "
                + "narrow FLOAT(10), widen REAL, local_ts TIMESTAMP WITH LOCAL TIME ZONE, "
                + "local_ms TIMESTAMP(3) WITH LOCAL TIME ZONE, PRIMARY KEY (id))";
        List<SchemaDefinition.ColumnDef> matching = List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER(10) NOT NULL"),
                new SchemaDefinition.ColumnDef("single_col", "REAL"),
                new SchemaDefinition.ColumnDef("double_col", "DOUBLE PRECISION"),
                new SchemaDefinition.ColumnDef("narrow", "FLOAT(10)"),
                new SchemaDefinition.ColumnDef("widen", "FLOAT(63)"),
                new SchemaDefinition.ColumnDef("local_ts", "TIMESTAMP(6) WITH LOCAL TIME ZONE"),
                new SchemaDefinition.ColumnDef("local_ms", "TIMESTAMP(3) WITH LOCAL TIME ZONE"));
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection,
                    strictDefinition(create, matching, List.of())).tablesCreated()).isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult strict = synchronizer(true)
                    .synchronizeWithResult(connection, strictDefinition(create, matching, List.of()));
            assertThat(strict.pendingSql()).isEmpty();
            assertThat(strict.changed()).isFalse();
        }

        List<SchemaDefinition.ColumnDef> drifted = List.of(
                new SchemaDefinition.ColumnDef("id", "NUMBER(10) NOT NULL"),
                new SchemaDefinition.ColumnDef("single_col", "FLOAT"),
                new SchemaDefinition.ColumnDef("double_col", "REAL"),
                new SchemaDefinition.ColumnDef("narrow", "FLOAT(126)"),
                new SchemaDefinition.ColumnDef("widen", "DOUBLE PRECISION"),
                new SchemaDefinition.ColumnDef("local_ts", "TIMESTAMP(3) WITH LOCAL TIME ZONE"),
                new SchemaDefinition.ColumnDef("local_ms", "TIMESTAMP WITH LOCAL TIME ZONE"));
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer(false)
                    .synchronizeWithResult(connection, strictDefinition(create, drifted, List.of()));
            // A higher binary precision keeps every stored value; a lower one or any temporal change does not.
            assertThat(result.columnsAltered()).isEqualTo(3);
            assertThat(result.pendingSql()).hasSize(3)
                    .anyMatch(sql -> sql.contains("MODIFY (\"DOUBLE_COL\" REAL)"))
                    .anyMatch(sql -> sql.contains("MODIFY (\"LOCAL_TS\" TIMESTAMP(3) WITH LOCAL TIME ZONE)"))
                    .anyMatch(sql -> sql.contains("MODIFY (\"LOCAL_MS\" TIMESTAMP WITH LOCAL TIME ZONE)"));
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT column_name, data_precision FROM user_tab_columns "
                     + "WHERE table_name = 'ORACLE_STRICT' AND data_type = 'FLOAT'")) {
            Map<String, Integer> precisions = new java.util.HashMap<>();
            while (rows.next()) {
                precisions.put(rows.getString(1).toLowerCase(Locale.ROOT), rows.getInt(2));
            }
            assertThat(precisions).containsEntry("single_col", 126).containsEntry("double_col", 126)
                    .containsEntry("narrow", 126).containsEntry("widen", 126);
        }

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            dropExisting(statement, "oracle_strict");
        }
        try (Connection connection = connection()) {
            synchronizer(true).synchronizeWithResult(connection, strictDefinition(create, matching, List.of()));
        }
        Path snapshot = tempDir.resolve("schema-definition.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, schema(), snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("oracle_strict").columns())
                .extracting(SchemaDefinition.ColumnDef::definition)
                .contains("FLOAT(63)", "FLOAT", "FLOAT(10)", "TIMESTAMP(6) WITH LOCAL TIME ZONE",
                        "TIMESTAMP(3) WITH LOCAL TIME ZONE");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            dropExisting(statement, "oracle_strict");
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
    void aliasQualifiedColumnsApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), "
                    + "qty NUMBER(10))");
            statement.execute("INSERT INTO oracle_items (id, note, qty) SELECT 1, 'a', 1 FROM dual "
                    + "UNION ALL SELECT 2, 'b', 2 FROM dual");
        }
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-alias-update", "alias-qualified columns",
                        List.of("UPDATE oracle_items i SET note = (SELECT t.note FROM oracle_items t "
                                + "WHERE t.id = i.id + 1), qty = i.qty + 10 WHERE i.id = 1"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 1 AND note = 'b' AND qty = 11")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(1);
        }
    }

    @Test
    void setBranchesRecordsSequencesAndSuppliedPackagesApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), "
                    + "qty NUMBER(10), created_at DATE)");
            statement.execute("CREATE TABLE oracle_staged (id NUMBER(10), note VARCHAR2(40))");
            statement.execute("CREATE TABLE oracle_tags (id NUMBER(10), name VARCHAR2(40))");
            statement.execute("INSERT INTO oracle_staged (id, note) VALUES (1, ' a ')");
            statement.execute("INSERT INTO oracle_tags (id, name) VALUES (2, 'b')");
            statement.execute("CREATE SEQUENCE oracle_items_seq START WITH 100");
        }
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-union", "correlations per set-operation branch",
                        List.of("INSERT INTO oracle_items (id, note) SELECT s.id, s.note FROM oracle_staged s "
                                + "UNION ALL SELECT t.id, t.name FROM oracle_tags t"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 2 AND note = 'b'"),
                new SchemaDefinition.ChangeSet("002-oracle-trim", "TRIM ... FROM takes an operand",
                        List.of("UPDATE oracle_items i SET note = TRIM(BOTH ' ' FROM i.note), "
                                + "created_at = DATE '2020-01-02' WHERE i.id = 1"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 1 AND note = 'a'"),
                new SchemaDefinition.ChangeSet("003-oracle-extract", "EXTRACT ... FROM takes an operand",
                        List.of("UPDATE oracle_items i SET qty = EXTRACT(YEAR FROM i.created_at) WHERE i.id = 1"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 1 AND qty = 2020"),
                new SchemaDefinition.ChangeSet("004-oracle-trigger", "REFERENCING alias, %TYPE, sequence, DBMS_OUTPUT",
                        List.of("CREATE OR REPLACE TRIGGER oracle_items_fill BEFORE INSERT ON oracle_items "
                                + "REFERENCING NEW AS n FOR EACH ROW WHEN (n.qty IS NULL) "
                                + "DECLARE v oracle_items.note%TYPE; BEGIN v := :n.note; "
                                + "IF :n.id IS NULL THEN :n.id := oracle_items_seq.NEXTVAL; END IF; "
                                + ":n.qty := 7; :n.note := UPPER(v); DBMS_OUTPUT.PUT_LINE(v); END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_FILL' "
                                + "AND object_type = 'TRIGGER' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("005-oracle-function", "loop record, %ROWTYPE, routine-qualified parameter",
                        List.of("CREATE OR REPLACE FUNCTION oracle_items_total(p NUMBER) RETURN NUMBER IS "
                                + "v oracle_items%ROWTYPE; total NUMBER := 0; BEGIN "
                                + "SELECT * INTO v FROM oracle_items WHERE id = 1; "
                                + "FOR r IN (SELECT id FROM oracle_items) LOOP total := total + r.id; END LOOP; "
                                + "RETURN oracle_items_total.p + total + v.qty; END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_TOTAL' "
                                + "AND object_type = 'FUNCTION' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("006-oracle-fire", "trigger fills the row",
                        List.of("INSERT INTO oracle_items (note) VALUES ('w')"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 100 AND note = 'W' AND qty = 7"),
                new SchemaDefinition.ChangeSet("007-oracle-call", "call the function",
                        List.of("UPDATE oracle_tags t SET name = TO_CHAR(oracle_items_total(1)) WHERE t.id = 2"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE id = 2 AND name = '2124'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(7);
        }
    }

    @Test
    void collectionMethodsCursorQueriesLabelsAndCompoundTriggersApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), qty NUMBER(10))");
            statement.execute("CREATE TABLE oracle_tags (id NUMBER(10), name VARCHAR2(40))");
            statement.execute("INSERT INTO oracle_items (id, note, qty) VALUES (1, 'a', 0)");
            statement.execute("INSERT INTO oracle_items (id, note, qty) VALUES (2, 'b', 0)");
        }
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-collections", "collection methods, OPEN FOR SELECT, labels",
                        List.of("CREATE OR REPLACE FUNCTION oracle_items_collect RETURN VARCHAR2 IS "
                                + "TYPE t_ids IS TABLE OF NUMBER; t t_ids := t_ids(); c SYS_REFCURSOR; "
                                + "TYPE a_t IS RECORD (city VARCHAR2(9)); TYPE h_t IS RECORD (addr a_t); h h_t; "
                                + "n NUMBER; i PLS_INTEGER; total NUMBER := 0; BEGIN h.addr.city := 'x'; "
                                + "OPEN c FOR SELECT id FROM oracle_items ORDER BY id; "
                                + "LOOP FETCH c INTO n; EXIT WHEN c%NOTFOUND; t.EXTEND; t(t.LAST) := n; END LOOP; "
                                + "CLOSE c; t.EXTEND(2, 1); t.TRIM(1); t.DELETE(3); "
                                + "i := t.FIRST; WHILE i IS NOT NULL LOOP total := total + t(i); i := t.NEXT(i); END LOOP; "
                                + "IF t.EXISTS(3) THEN total := -1; END IF; "
                                + "<<outer>> DECLARE n NUMBER := 10; BEGIN DECLARE n NUMBER := 20; BEGIN "
                                + "total := total + outer.n; END; END; "
                                + "i := t.LAST; total := total + t.PRIOR(i) + t.COUNT; t.DELETE; "
                                + "RETURN (total + t.COUNT) || oracle_items_collect.h.addr.city; END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_COLLECT' "
                                + "AND object_type = 'FUNCTION' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("002-oracle-compound", "compound trigger declarations reach every section",
                        List.of("CREATE OR REPLACE TRIGGER oracle_items_batch FOR UPDATE ON oracle_items COMPOUND TRIGGER "
                                + "TYPE t_ids IS TABLE OF NUMBER; g t_ids := t_ids(); v oracle_items%ROWTYPE; "
                                + "BEFORE STATEMENT IS BEGIN g.DELETE; END BEFORE STATEMENT; "
                                + "AFTER EACH ROW IS BEGIN g.EXTEND; g(g.LAST) := :NEW.id; END AFTER EACH ROW; "
                                + "AFTER STATEMENT IS BEGIN v.note := 'n=' || g.COUNT; "
                                + "IF g.EXISTS(1) THEN UPDATE oracle_tags SET name = v.note WHERE id = 1; END IF; "
                                + "END AFTER STATEMENT; END oracle_items_batch;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_BATCH' "
                                + "AND object_type = 'TRIGGER' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("003-oracle-tags", "tag rows",
                        List.of("INSERT INTO oracle_tags (id, name) SELECT id, 'x' FROM oracle_items"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE name = 'x'"),
                new SchemaDefinition.ChangeSet("004-oracle-call", "call the function",
                        List.of("UPDATE oracle_tags SET name = TO_CHAR(oracle_items_collect) WHERE id = 2"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE id = 2 AND name = '16x'"),
                new SchemaDefinition.ChangeSet("005-oracle-fire", "fire the compound trigger",
                        List.of("UPDATE oracle_items SET qty = 1"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE id = 1 AND name = 'n=2'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(5);
        }
    }

    @Test
    void nestedSubprogramItemsAndDbmsLobConstantsApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), qty NUMBER(10))");
            statement.execute("INSERT INTO oracle_items (id, note, qty) VALUES (1, 'a', 2)");
            statement.execute("CREATE TABLE oracle_tags (id NUMBER(10), name VARCHAR2(40))");
            statement.execute("INSERT INTO oracle_tags (id, name) VALUES (1, 'x')");
        }
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-nested", "nested subprograms with their own parameters",
                        List.of("CREATE OR REPLACE FUNCTION oracle_items_nested(p_id NUMBER) RETURN VARCHAR2 IS "
                                + "v oracle_items%ROWTYPE; mode_ro NUMBER := DBMS_LOB.LOB_READONLY; "
                                + "PROCEDURE bump(x NUMBER); "
                                + "FUNCTION item_label(r oracle_items%ROWTYPE) RETURN VARCHAR2 IS n NUMBER := 1; "
                                + "BEGIN n := item_label.n + r.qty; RETURN r.note || n; END item_label; "
                                + "PROCEDURE bump(x NUMBER) IS BEGIN v.qty := v.qty + x; END bump; "
                                + "BEGIN SELECT * INTO v FROM oracle_items WHERE id = p_id; bump(DBMS_LOB.LOB_READWRITE); "
                                + "RETURN item_label(v) || mode_ro; END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_NESTED' "
                                + "AND object_type = 'FUNCTION' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("002-oracle-nested-call", "call the function",
                        List.of("UPDATE oracle_tags SET name = oracle_items_nested(id) WHERE id = 1"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE id = 1 AND name = 'a40'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(2);
        }
    }

    @Test
    void schemaQualifiedAnchorsRecordFieldAnchorsAndSysDataTypesApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), qty NUMBER(10))");
            statement.execute("CREATE TABLE oracle_tags (id NUMBER(10), name VARCHAR2(40))");
            statement.execute("INSERT INTO oracle_tags (id, name) VALUES (1, 'x')");
        }
        String schema = schema();
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-anchors", "schema-qualified and record-field anchors",
                        List.of("CREATE OR REPLACE FUNCTION oracle_items_anchors(p " + schema + ".oracle_items.note%TYPE) "
                                + "RETURN " + schema + ".oracle_items.note%TYPE IS "
                                + "TYPE a_t IS RECORD (c VARCHAR2(9)); TYPE h_t IS RECORD (a a_t); r h_t; x r.a.c%TYPE; "
                                + "v " + schema + ".oracle_items%ROWTYPE; n SYS.ODCINUMBERLIST := SYS.ODCINUMBERLIST(1, 2, 3); "
                                + "BEGIN r.a.c := 'z'; x := r.a.c; RETURN p || x || n.COUNT; END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_ANCHORS' "
                                + "AND object_type = 'FUNCTION' AND status = 'VALID'"),
                new SchemaDefinition.ChangeSet("002-oracle-sys-types", "SYS collection and XMLTYPE values",
                        List.of("UPDATE oracle_tags SET name = oracle_items_anchors('a') "
                                + "|| (SELECT COUNT(*) FROM TABLE(SYS.ODCINUMBERLIST(1, 2))) "
                                + "|| (SELECT MAX(column_value) FROM TABLE(SYS.ODCIVARCHAR2LIST('k', 'q'))) "
                                + "|| SYS.XMLTYPE('<b/>').getStringVal() || XMLTYPE.createXML('<c/>').getStringVal() "
                                + "WHERE id = 1"),
                        "SELECT COUNT(*) FROM oracle_tags WHERE id = 1 AND name = 'az32q<b/><c/>'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(2);
        }
    }

    @Test
    void aliasQualifiedObjectColumnsSysDualAndHeaderAnchorsApply() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TYPE oracle_addr_t AS OBJECT (city VARCHAR2(20))");
            statement.execute("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), "
                    + "doc XMLTYPE, addr oracle_addr_t)");
            statement.execute("INSERT INTO oracle_items (id, doc, addr) VALUES (1, XMLTYPE('<a/>'), oracle_addr_t('Oslo'))");
            statement.execute("CREATE TABLE oracle_tags (id NUMBER(10), name VARCHAR2(40))");
            statement.execute("INSERT INTO oracle_tags (id, name) VALUES (1, 'x')");
        }
        String schema = schema();
        SchemaDefinition definition = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-alias-method", "alias.column.method()",
                        List.of("UPDATE oracle_items i SET note = RTRIM(i.doc.getStringVal(), CHR(10)) WHERE i.id = 1"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 1 AND note = '<a/>'"),
                new SchemaDefinition.ChangeSet("002-oracle-alias-attribute", "alias named like a schema, SYS.DUAL read",
                        List.of("UPDATE oracle_items system SET note = note || system.addr.city "
                                + "WHERE system.id = (SELECT 1 FROM SYS.DUAL)"),
                        "SELECT COUNT(*) FROM oracle_items WHERE id = 1 AND note = '<a/>Oslo'"),
                new SchemaDefinition.ChangeSet("003-oracle-header-anchors", "header and nested header anchors",
                        List.of("CREATE OR REPLACE FUNCTION oracle_items_header(p " + schema + ".oracle_items.note%TYPE) "
                                + "RETURN " + schema + ".oracle_items.note%TYPE IS "
                                + "TYPE a_t IS RECORD (city VARCHAR2(20)); TYPE h_t IS RECORD (addr a_t); h h_t; "
                                + "FUNCTION q(x h.addr.city%TYPE) RETURN VARCHAR2 IS BEGIN RETURN x; END; "
                                + "BEGIN h.addr.city := 'Rome'; UPDATE oracle_tags SET name = h.addr.city WHERE id = 1; "
                                + "RETURN q(p); END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_HEADER' "
                                + "AND object_type = 'FUNCTION' AND status = 'VALID'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, definition).changeSetsApplied())
                    .isEqualTo(3);
        }
        try (Connection connection = connection();
             var call = connection.prepareCall("{? = call oracle_items_header(?)}")) {
            call.registerOutParameter(1, java.sql.Types.VARCHAR);
            call.setString(2, "p");
            call.execute();
            assertThat(call.getString(1)).isEqualTo("p");
            try (var rows = connection.createStatement().executeQuery("SELECT name FROM oracle_tags WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Rome");
            }
        }
    }

    @Test
    void multiStatementPlSqlTriggerIsOneChangeSetStatement() throws Exception {
        SchemaDefinition withTrigger = new SchemaDefinition(2, "oracle", Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-oracle-items", "table",
                        List.of("CREATE TABLE oracle_items (id NUMBER(10) PRIMARY KEY, note VARCHAR2(40), "
                                + "qty NUMBER(10))"),
                        "SELECT COUNT(*) FROM user_tables WHERE table_name = 'ORACLE_ITEMS'"),
                new SchemaDefinition.ChangeSet("002-oracle-trigger", "PL/SQL trigger with inner semicolons",
                        List.of("CREATE OR REPLACE TRIGGER oracle_items_fill BEFORE INSERT ON oracle_items "
                                + "FOR EACH ROW BEGIN "
                                + "IF :NEW.note IS NULL THEN :NEW.note := 'a;b'; END IF; "
                                + "FOR i IN 1..3 LOOP :NEW.qty := i; END LOOP; END;"),
                        "SELECT COUNT(*) FROM user_objects WHERE object_name = 'ORACLE_ITEMS_FILL' "
                                + "AND object_type = 'TRIGGER' AND status = 'VALID'")));
        try (Connection connection = connection()) {
            assertThat(synchronizer().synchronizeWithResult(connection, withTrigger).changeSetsApplied())
                    .isEqualTo(2);
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO oracle_items (id) VALUES (1)");
            try (var rows = statement.executeQuery("SELECT note, qty FROM oracle_items WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("a;b");
                assertThat(rows.getInt(2)).isEqualTo(3);
            }
        }
    }

    private SchemaDefinition strictDefinition(String create, List<SchemaDefinition.ColumnDef> columns,
                                              List<String> indexes) {
        return new SchemaDefinition(2, "oracle", Map.of("oracle_strict",
                new SchemaDefinition.TableDef(create, columns, indexes)), List.of());
    }

    /** Mid-test drop of a table the test created: a missing table is a failure, not something to swallow. */
    private void dropExisting(java.sql.Statement statement, String table) throws Exception {
        statement.execute("DROP TABLE " + table + " PURGE");
    }

    private SchemaDefinition definition(List<SchemaDefinition.ColumnDef> columns) {
        return new SchemaDefinition(2, "oracle", Map.of("oracle_items",
                new SchemaDefinition.TableDef(
                        "CREATE TABLE oracle_items (id NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL, "
                                + "label VARCHAR2(40) NOT NULL, PRIMARY KEY (id))",
                        columns,
                        List.of("CREATE INDEX idx_oracle_items_label ON oracle_items (label)"))),
                List.of());
    }

    private SchemaSynchronizer synchronizer() {
        return synchronizer(false);
    }

    private SchemaSynchronizer synchronizer(boolean failOnPending) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(schema(), "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
    }

    private String schema() {
        String configured = System.getProperty("schema.test.oracle.jdbc.schema");
        if (configured != null && !configured.isBlank()) {
            return configured.toLowerCase(Locale.ROOT);
        }
        return System.getProperty("schema.test.oracle.jdbc.user").toLowerCase(Locale.ROOT);
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                System.getProperty("schema.test.oracle.jdbc.url"),
                System.getProperty("schema.test.oracle.jdbc.user"),
                System.getProperty("schema.test.oracle.jdbc.password"));
    }
}
