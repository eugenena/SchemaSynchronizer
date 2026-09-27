// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Declarations must round-trip through each dialect's catalog without permanent drift. */
class DialectDeclarationContractTest {

    private static SchemaDefinition table(String createSql, List<String> indexes) {
        return new SchemaDefinition(Map.of("items", new SchemaDefinition.TableDef(createSql,
                List.of(new SchemaDefinition.ColumnDef("id", "INT NOT NULL"),
                        new SchemaDefinition.ColumnDef("label", "VARCHAR(40)")),
                indexes)));
    }

    private static void validate(SchemaDefinition definition, DatabaseDialect dialect, String schema) {
        SchemaSynchronizer.validateDeclarative(definition, dialect, new SchemaSynchronizerOptions(
                schema, "schema_synchronizer_history", 1L, false, true, true));
    }

    private static void fits(String definition, DatabaseDialect dialect) {
        SchemaSynchronizer.requireMySqlDefaultFits(ColumnDefinitionParser.parse(definition), definition, dialect, "t.c");
    }

    @Test
    void mysqlDefaultsTheServerRejectsFailValidation() {
        // Rejected by MySQL 8.4 and MariaDB 10.3/11.4 with error 1064 or 1067 (verified live), except
        // DOUBLE UNSIGNED DEFAULT '-1', which only MySQL rejects; it is rejected on both.
        for (String rejected : List.of("VARBINARY(4) DEFAULT X'616'", "TINYINT DEFAULT 300", "TINYINT DEFAULT -129",
                "TINYINT UNSIGNED DEFAULT 256", "TINYINT UNSIGNED DEFAULT -1", "SMALLINT DEFAULT 32768",
                "MEDIUMINT DEFAULT 8388608", "INT DEFAULT 2147483648", "BIGINT DEFAULT -9223372036854775809",
                "BIGINT UNSIGNED DEFAULT 18446744073709551616", "TINYINT DEFAULT '300'", "TINYINT DEFAULT 127.5",
                "DECIMAL(5,2) DEFAULT 1234.5", "DECIMAL(5,2) DEFAULT 999.999", "DECIMAL(3) DEFAULT 1000",
                "VARCHAR(3) DEFAULT 'abcd'", "CHAR(2) DEFAULT 'it''s'", "VARCHAR(3) DEFAULT 'abc   '",
                "TINYINT DEFAULT 127.5E0", "BINARY(2) DEFAULT 12345", "VARBINARY(2) DEFAULT X'AABBCC'",
                "VARBINARY(2) DEFAULT 0xAABBC", "VARBINARY(2) DEFAULT 'abc'", "BOOLEAN DEFAULT 300",
                "DECIMAL DEFAULT 12345678901", "DECIMAL(3,1) DEFAULT 99.95E0", "INT DEFAULT 1E100000000",
                "TINYINT UNSIGNED DEFAULT -0.4", "TINYINT UNSIGNED DEFAULT -0.5", "TINYINT UNSIGNED DEFAULT '-0.5'",
                "TINYINT(1) UNSIGNED DEFAULT 256", "TINYINT UNSIGNED ZEROFILL DEFAULT 256",
                "INT UNSIGNED ZEROFILL DEFAULT -1",
                // Quoted E notation rounds half away from zero like any quoted number.
                "TINYINT DEFAULT '-128.5e0'", "TINYINT UNSIGNED DEFAULT '-0.5e0'", "SMALLINT DEFAULT '-32768.5E0'",
                "DECIMAL UNSIGNED DEFAULT -1", "DECIMAL UNSIGNED DEFAULT 12345678901",
                "DECIMAL(5,2) UNSIGNED DEFAULT -0.001", "DECIMAL(5,2) UNSIGNED DEFAULT -0.004E0",
                "DECIMAL(5,2) UNSIGNED DEFAULT '-0.004'", "FLOAT UNSIGNED DEFAULT -1", "FLOAT UNSIGNED DEFAULT -0.4E0",
                "DOUBLE UNSIGNED DEFAULT '-1'", "TINYINT DEFAULT 0x80", "TINYINT UNSIGNED DEFAULT 0x100",
                "MIDDLEINT DEFAULT 8388608", "INT1 DEFAULT 128", "TINYINT UNSIGNED DEFAULT - 1",
                "TINYINT DEFAULT - 129", "DOUBLE DEFAULT 1E309", "FLOAT DEFAULT 1E39", "FLOAT DEFAULT -1E39",
                "DOUBLE DEFAULT 1E100000000", "DECIMAL(5,2) UNSIGNED DEFAULT 1000", "DECIMAL(5,2) UNSIGNED DEFAULT 999.995",
                // Exponents at and beyond int range (precision - scale overflows an int).
                "INT DEFAULT 1E2147483647", "DECIMAL(5,2) DEFAULT 1E2147483647", "INT UNSIGNED DEFAULT -1E2147483647",
                "INT DEFAULT 1E+2147483648", "DOUBLE DEFAULT 1E+2147483648", "INT DEFAULT '1E+2147483648'",
                "FLOAT4 DEFAULT 1E39", "NVARCHAR(3) DEFAULT 'abcd'", "NCHAR(2) DEFAULT 'abc'")) {
            // Each cell must parse, so it is rejected by the default check rather than by the parser.
            assertThatCode(() -> ColumnDefinitionParser.parse(rejected)).as(rejected).doesNotThrowAnyException();
            assertThatThrownBy(() -> fits(rejected, DatabaseDialect.MYSQL)).as(rejected)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fits(rejected, DatabaseDialect.MARIADB)).as(rejected)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String accepted : List.of("VARBINARY(4) DEFAULT X'6162'", "VARBINARY(4) DEFAULT 0x616",
                "VARBINARY(4) DEFAULT X''", "TINYINT DEFAULT 127", "TINYINT DEFAULT -128", "TINYINT DEFAULT TRUE",
                "TINYINT UNSIGNED DEFAULT 255", "INT DEFAULT -2147483648", "BIGINT UNSIGNED DEFAULT 18446744073709551615",
                "TINYINT DEFAULT b'1111111'", "DECIMAL(5,2) DEFAULT 999.99", "DECIMAL(5,2) DEFAULT -0.5",
                "DECIMAL(3) DEFAULT 999", "DECIMAL(5,2) DEFAULT 999.994", "TINYINT DEFAULT 126.5",
                "VARCHAR(3) DEFAULT 'abc'", "VARCHAR(3) DEFAULT 'éèê'", "INT DEFAULT (1 + 2)", "INT",
                "TINYINT DEFAULT -128.5E0", "TINYINT DEFAULT 126.5E0", "BINARY(2) DEFAULT 12",
                "VARBINARY(3) DEFAULT 'it'''", "BOOLEAN DEFAULT 127", "DECIMAL DEFAULT 1234567890",
                "INT DEFAULT 1E-100", "INT DEFAULT 1E-100000000", "VARBINARY(1) DEFAULT 'é'",
                "TINYINT UNSIGNED DEFAULT -0", "TINYINT UNSIGNED DEFAULT -0.0", "TINYINT UNSIGNED DEFAULT '-0.4'",
                "TINYINT UNSIGNED DEFAULT -0.4E0", "TINYINT UNSIGNED DEFAULT -0.5E0", "TINYINT(1) UNSIGNED DEFAULT 1",
                "TINYINT DEFAULT '126.5e0'", "TINYINT DEFAULT 0x7F", "TINYINT UNSIGNED DEFAULT 0xFF",
                "MIDDLEINT DEFAULT 8388607", "TINYINT DEFAULT - 1", "DECIMAL(5,2) UNSIGNED DEFAULT -0.0",
                "FLOAT UNSIGNED DEFAULT 1E30", "DOUBLE DEFAULT -1E300", "DOUBLE DEFAULT 1E308", "FLOAT DEFAULT 3E38",
                "FLOAT(53) DEFAULT 1E300", "DOUBLE DEFAULT 1E-100000000", "DECIMAL(5,2) UNSIGNED DEFAULT 999.99",
                "DECIMAL UNSIGNED DEFAULT 1234567890", "INT DEFAULT 1E-2147483649", "INT DEFAULT 0E+2147483648",
                "REAL DEFAULT 1E39", "NVARCHAR(3) DEFAULT 'abc'",
                // Escape-dependent length: left to the server.
                "VARCHAR(3) DEFAULT 'a\\\\bc'")) {
            assertThatCode(() -> fits(accepted, DatabaseDialect.MYSQL)).as(accepted).doesNotThrowAnyException();
        }
        // MySQL converts X'10' to 16 on an integer column; MariaDB rejects it (verified live on 10.3).
        assertThatCode(() -> fits("TINYINT DEFAULT X'10'", DatabaseDialect.MYSQL)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fits("TINYINT DEFAULT X'10'", DatabaseDialect.MARIADB))
                .hasMessageContaining("MariaDB");
        assertThatCode(() -> fits("TINYINT DEFAULT 0x10", DatabaseDialect.MARIADB)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fits("TINYINT DEFAULT X'80'", DatabaseDialect.MYSQL))
                .isInstanceOf(IllegalArgumentException.class);
        // MySQL evaluates a parenthesized default on insert; MariaDB rejects (300) at DDL (verified live).
        assertThatCode(() -> fits("TINYINT DEFAULT (300)", DatabaseDialect.MYSQL)).doesNotThrowAnyException();
        assertThatCode(() -> fits("VARCHAR(3) DEFAULT ('abcd')", DatabaseDialect.MYSQL)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fits("TINYINT DEFAULT (300)", DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fits("VARBINARY(4) DEFAULT (X'616')", DatabaseDialect.MYSQL))
                .hasMessageContaining("even number");
        // Other engines are not checked here.
        assertThatCode(() -> fits("SMALLINT DEFAULT 99999", DatabaseDialect.POSTGRESQL)).doesNotThrowAnyException();
        // Validation runs it for every declared column.
        assertThatThrownBy(() -> validate(new SchemaDefinition(Map.of("items", new SchemaDefinition.TableDef(
                "CREATE TABLE items (id INT NOT NULL, PRIMARY KEY (id))",
                List.of(new SchemaDefinition.ColumnDef("id", "INT NOT NULL"),
                        new SchemaDefinition.ColumnDef("flag", "TINYINT DEFAULT 300")), List.of()))),
                DatabaseDialect.MYSQL, "app")).hasMessageContaining("items.flag");
    }

    @Test
    void createTableIfNotExistsIsRejectedOnlyWhereUnsupported() {
        SchemaDefinition definition = table(
                "CREATE TABLE IF NOT EXISTS items (id INT NOT NULL, label VARCHAR(40), PRIMARY KEY (id))", List.of());
        assertThatThrownBy(() -> validate(definition, DatabaseDialect.SQLSERVER, "dbo"))
                .hasMessageContaining("CREATE TABLE IF NOT EXISTS");
        assertThatThrownBy(() -> validate(definition, DatabaseDialect.ORACLE, "APP"))
                .hasMessageContaining("CREATE TABLE IF NOT EXISTS");
        assertThatCode(() -> validate(definition, DatabaseDialect.POSTGRESQL, "public")).doesNotThrowAnyException();
        assertThatCode(() -> validate(definition, DatabaseDialect.MYSQL, "app")).doesNotThrowAnyException();
    }

    @Test
    void declaredIndexesMustBeReconstructableOnSqlServerAndOracle() {
        String create = "CREATE TABLE items (id INT NOT NULL, label VARCHAR(40), PRIMARY KEY (id))";
        List<String> rejectedEverywhere = List.of(
                "CREATE INDEX idx_items_label ON items (label) WHERE label IS NOT NULL",
                "CREATE INDEX idx_items_label ON items (label) INCLUDE (id)",
                "CREATE INDEX idx_items_label ON items (UPPER(label))");
        for (String index : rejectedEverywhere) {
            SchemaDefinition definition = table(create, List.of(index));
            assertThatThrownBy(() -> validate(definition, DatabaseDialect.SQLSERVER, "dbo"))
                    .as(index).hasMessageContaining("plain column list");
            assertThatThrownBy(() -> validate(definition, DatabaseDialect.ORACLE, "APP"))
                    .as(index).hasMessageContaining("plain column list");
        }

        SchemaDefinition descending = table(create, List.of("CREATE INDEX idx_items_label ON items (label DESC, id)"));
        assertThatCode(() -> validate(descending, DatabaseDialect.SQLSERVER, "dbo")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(descending, DatabaseDialect.ORACLE, "APP"))
                .hasMessageContaining("without DESC");

        SchemaDefinition plain = table(create, List.of("CREATE UNIQUE INDEX idx_items_label ON items (label ASC, id)"));
        assertThatCode(() -> validate(plain, DatabaseDialect.SQLSERVER, "dbo")).doesNotThrowAnyException();
        assertThatCode(() -> validate(plain, DatabaseDialect.ORACLE, "APP")).doesNotThrowAnyException();

        SchemaDefinition partialOnPostgres = table(create,
                List.of("CREATE INDEX IF NOT EXISTS idx_items_label ON items (label) WHERE label IS NOT NULL"));
        assertThatCode(() -> validate(partialOnPostgres, DatabaseDialect.POSTGRESQL, "public"))
                .doesNotThrowAnyException();
    }

    @Test
    void indexStructureIgnoresAscAndKeywordCaseButNotQuotedIdentifiersOrLiterals() {
        assertThat(IndexDefinition.parse("CREATE INDEX i ON t (A ASC, B DESC)")
                .hasSameStructure(IndexDefinition.parse("create index i on t (a, b desc)"))).isTrue();
        assertThat(IndexDefinition.parse("CREATE INDEX i ON t (\"Foo\")")
                .hasSameStructure(IndexDefinition.parse("CREATE INDEX i ON t (\"foo\")"))).isFalse();
        assertThat(IndexDefinition.parse("CREATE INDEX i ON t (coalesce(a, 'X'))")
                .hasSameStructure(IndexDefinition.parse("CREATE INDEX i ON t (coalesce(a, 'x'))"))).isFalse();
    }

    @Test
    void identifierLimitsFollowTheDialect() {
        String sixtyFour = "a".repeat(64);
        assertThat(DatabaseDialect.MYSQL.maxIdentifierLength()).isEqualTo(64);
        assertThat(DatabaseDialect.MARIADB.maxIdentifierLength()).isEqualTo(64);
        assertThat(DatabaseDialect.POSTGRESQL.maxIdentifierLength()).isEqualTo(63);
        assertThat(DatabaseDialect.SQLSERVER.maxIdentifierLength()).isEqualTo(128);
        assertThatCode(() -> SqlIdentifiers.requireIdentifier(sixtyFour, "table",
                DatabaseDialect.MYSQL.maxIdentifierLength())).doesNotThrowAnyException();
        assertThatThrownBy(() -> SqlIdentifiers.requireIdentifier(sixtyFour, "table",
                DatabaseDialect.POSTGRESQL.maxIdentifierLength())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oracleStripsTrailingSemicolonExceptOnPlsql() {
        assertThat(DatabaseDialect.ORACLE.executableSql("CREATE INDEX i ON t (a);  "))
                .isEqualTo("CREATE INDEX i ON t (a)");
        assertThat(DatabaseDialect.ORACLE.executableSql("BEGIN NULL; END;")).isEqualTo("BEGIN NULL; END;");
        String trigger = "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN NULL; END;";
        assertThat(DatabaseDialect.ORACLE.executableSql(trigger)).isEqualTo(trigger);
        assertThat(DatabaseDialect.ORACLE.executableSql("CREATE TABLE t (a INT)")).isEqualTo("CREATE TABLE t (a INT)");
        assertThat(DatabaseDialect.POSTGRESQL.executableSql("CREATE INDEX i ON t (a);"))
                .isEqualTo("CREATE INDEX i ON t (a);");
    }

    @Test
    void mysqlUnquotedLiteralDefaultsAreQuotedButExpressionsAndNumbersAreNot() {
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("new", "VARCHAR", false)).isEqualTo("'new'");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("it's", "VARCHAR", false)).isEqualTo("'it''s'");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("2020-01-01", "DATE", false)).isEqualTo("'2020-01-01'");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("0", "INT UNSIGNED", false)).isEqualTo("0");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("1.50", "DECIMAL", false)).isEqualTo("1.50");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("uuid()", "VARCHAR", true)).isEqualTo("(uuid())");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("curdate()", "DATE", true)).isEqualTo("(curdate())");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("(1 + 2)", "INT", true)).isEqualTo("(1 + 2)");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("(a) + (b)", "INT", true)).isEqualTo("((a) + (b))");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("concat(_utf8mb4\\'a\\',_utf8mb4\\'b\\')", "VARCHAR",
                true)).isEqualTo("(concat(_utf8mb4'a',_utf8mb4'b'))");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP", "DATETIME", true))
                .isEqualTo("CURRENT_TIMESTAMP");
        // information_schema text for DEFAULT (concat('it''s','x')) and DEFAULT (concat('a\\b','c')).
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("concat(_utf8mb4\\'it\\\\\\'s\\',_utf8mb4\\'x\\')",
                "VARCHAR", true)).isEqualTo("(concat(_utf8mb4'it''s',_utf8mb4'x'))");
        assertThat(SchemaSnapshotWriter.doubleEscapedQuotes("f('a''b', 'c\\'d', 'e\\\\')")).isEqualTo(
                "f('a''b', 'c''d', 'e\\\\')");
        assertThat(SchemaSnapshotWriter.doubleEscapedQuotes("g(x\\y)")).isEqualTo("g(x\\y)");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("concat(_utf8mb4\\'a\\\\\\\\b\\',_utf8mb4\\'c\\')",
                "VARCHAR", true)).isEqualTo("(concat(_utf8mb4'a\\\\b',_utf8mb4'c'))");
        assertThat(ColumnDefinitionParser.normalizeDefault(SchemaSnapshotWriter.mysqlLiteralDefault("uuid()",
                "VARCHAR", true))).isEqualTo(ColumnDefinitionParser.normalizeDefault("(uuid())"));
        assertThat(ColumnDefinitionParser.normalizeDefault(SchemaSnapshotWriter.mysqlLiteralDefault("curdate()",
                "DATE", true))).isEqualTo(ColumnDefinitionParser.normalizeDefault("(CURRENT_DATE)"));
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP", "TIMESTAMP", false))
                .isEqualTo("CURRENT_TIMESTAMP");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP(3)", "DATETIME", false))
                .isEqualTo("CURRENT_TIMESTAMP(3)");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP", "VARCHAR", false))
                .isEqualTo("'CURRENT_TIMESTAMP'");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("current_timestamp", "TEXT", false))
                .isEqualTo("'current_timestamp'");
        assertThat(ColumnDefinitionParser.normalizeDefault(
                SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP", "VARCHAR", false)))
                .isNotEqualTo(ColumnDefinitionParser.normalizeDefault("CURRENT_TIMESTAMP"));
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault(null, "VARCHAR", false)).isNull();
        assertThat(ColumnDefinitionParser.normalizeDefault(
                SchemaSnapshotWriter.mysqlLiteralDefault("new", "VARCHAR", false)))
                .isEqualTo(ColumnDefinitionParser.normalizeDefault("'new'"));
    }

    @Test
    void snapshotDefinitionsUseEachDialectsClauseOrder() {
        assertThat(SchemaSnapshotWriter.buildDefinition("VARCHAR2", 40, null, " NOT NULL", "'x'", false,
                DatabaseDialect.ORACLE)).isEqualTo("VARCHAR2(40) DEFAULT 'x' NOT NULL");
        assertThat(SchemaSnapshotWriter.buildDefinition("NUMBER", 19, 0, " NOT NULL", null, true,
                DatabaseDialect.ORACLE)).isEqualTo("NUMERIC(19,0) GENERATED BY DEFAULT AS IDENTITY NOT NULL");
        assertThat(SchemaSnapshotWriter.buildDefinition("BIGINT", 19, null, " NOT NULL", null, true,
                DatabaseDialect.SQLSERVER)).isEqualTo("BIGINT NOT NULL IDENTITY(1,1)");
        assertThat(SchemaSnapshotWriter.buildDefinition("NVARCHAR", Integer.MAX_VALUE, null, "", null, false,
                DatabaseDialect.SQLSERVER)).isEqualTo("NVARCHAR(MAX)");

        for (String definition : List.of("VARCHAR2(40) DEFAULT 'x' NOT NULL",
                "NUMERIC(19,0) GENERATED BY DEFAULT AS IDENTITY NOT NULL")) {
            assertThat(ColumnDefinitionParser.parse(definition).notNull()).as(definition).isTrue();
        }
    }

    @Test
    void snapshotKeepsFixedLengthsAndOracleUnboundedNumber() {
        assertThat(SchemaSnapshotWriter.columnType("CHAR", 3, null, DatabaseDialect.SQLSERVER)).isEqualTo("CHAR(3)");
        assertThat(SchemaSnapshotWriter.columnType("NCHAR", 2, null, DatabaseDialect.SQLSERVER)).isEqualTo("NCHAR(2)");
        assertThat(SchemaSnapshotWriter.columnType("BINARY", 16, null, DatabaseDialect.SQLSERVER))
                .isEqualTo("BINARY(16)");
        assertThat(SchemaSnapshotWriter.columnType("RAW", 16, null, DatabaseDialect.ORACLE)).isEqualTo("RAW(16)");
        assertThat(SchemaSnapshotWriter.columnType("BPCHAR", 5, null, DatabaseDialect.POSTGRESQL)).isEqualTo("CHAR(5)");
        assertThat(SchemaSnapshotWriter.columnType("NUMBER", 0, -127, DatabaseDialect.ORACLE)).isEqualTo("NUMBER");
        assertThat(SchemaSnapshotWriter.columnType("NUMBER", 10, 2, DatabaseDialect.ORACLE)).isEqualTo("NUMERIC(10,2)");
        assertThat(SchemaSnapshotWriter.columnType("NUMERIC", 0, null, DatabaseDialect.POSTGRESQL)).isEqualTo("NUMERIC");
        assertThat(SchemaSnapshotWriter.columnType("VARCHAR2", 20_000, null, DatabaseDialect.ORACLE))
                .isEqualTo("VARCHAR2(20000)");
        assertThat(SchemaSnapshotWriter.columnType("DATETIME2", 27, 3, DatabaseDialect.SQLSERVER))
                .isEqualTo("DATETIME2(3)");
        assertThat(SchemaSnapshotWriter.columnType("TIME", 16, 0, DatabaseDialect.SQLSERVER)).isEqualTo("TIME(0)");
        assertThat(SchemaSnapshotWriter.columnType("DATETIME", 23, 3, DatabaseDialect.SQLSERVER)).isEqualTo("DATETIME");
        assertThat(SchemaSnapshotWriter.columnType("DATETIME", 23, 3, DatabaseDialect.MYSQL)).isEqualTo("DATETIME(3)");
        assertThat(SchemaSnapshotWriter.columnType("TIMESTAMP", 19, 0, DatabaseDialect.MYSQL)).isEqualTo("TIMESTAMP");
        assertThat(SchemaSnapshotWriter.columnType("TIMESTAMP", 29, 6, DatabaseDialect.POSTGRESQL))
                .isEqualTo("TIMESTAMP");
        assertThat(ColumnDefinitionParser.parse("DATETIME2(3) NOT NULL").baseType()).isEqualTo("DATETIME2");
    }

    @Test
    void nationalAndFixedBinaryTypesNeverSilentlyConvert() {
        ColumnSpec nchar10 = ColumnDefinitionParser.parse("NCHAR(10)");
        assertThat(nchar10.baseType()).isEqualTo("NCHAR");
        LiveColumn liveNchar = new LiveColumn("NCHAR", 10, null, true, null);
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("CHAR(20) NOT NULL"),
                liveNchar).applySql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("VARCHAR(20) NOT NULL"),
                liveNchar).applySql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("NCHAR(20) NOT NULL"),
                liveNchar).applyOps()).contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE);
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("NVARCHAR(20) NOT NULL"),
                liveNchar).applyOps()).contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE);

        LiveColumn liveBinary = new LiveColumn("BINARY", 16, null, true, null);
        NonDestructiveAlterPlanner.Plan resize = NonDestructiveAlterPlanner.plan("t", "c",
                ColumnDefinitionParser.parse("BINARY(32) NOT NULL"), liveBinary);
        assertThat(resize.applySql()).isEmpty();
        assertThat(resize.pendingSql()).isNotEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("VARBINARY(16) NOT NULL"),
                liveBinary).applySql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("VARBINARY(16) NOT NULL"),
                liveBinary).pendingSql()).isNotEmpty();
        assertThat(ColumnDefinitionParser.parse("RAW(32)").baseType()).isEqualTo("VARBINARY");
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("RAW(32) NOT NULL"),
                new LiveColumn("VARBINARY", 16, null, true, null)).applyOps())
                .contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE);
    }

    private static SchemaSynchronizer.MySqlColumnFacts mysqlFacts(String collation, String tableCollation,
                                                                  String charset, String extra, String comment,
                                                                  String generation, String charsetDefault) {
        return new SchemaSynchronizer.MySqlColumnFacts(true, collation, tableCollation, charset, extra, comment,
                generation, charsetDefault, "varchar(20)");
    }

    @Test
    void mysqlModifyColumnIsPendingWhenItWouldResetUndeclaredAttributes() {
        var plain = mysqlFacts("utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci", "utf8mb4", "", "", "",
                "utf8mb4_0900_ai_ci");
        assertThat(SchemaSynchronizer.mySqlBlockReason(plain, "VARCHAR(100)")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb4_bin", "latin1_swedish_ci", "utf8mb4",
                "", "", "", "utf8mb4_0900_ai_ci"), "VARCHAR(100)")).contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb4_bin", "latin1_swedish_ci", "utf8mb4",
                "", "", "", "utf8mb4_0900_ai_ci"),
                "VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")).contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts(null, null, null,
                "DEFAULT_GENERATED on update CURRENT_TIMESTAMP", "", "", null), "TIMESTAMP NULL"))
                .contains("ON UPDATE");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts(null, null, null, "DEFAULT_GENERATED", "", "",
                null), "TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts(null, null, null, "auto_increment", "", "",
                null), "BIGINT")).contains("AUTO_INCREMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci",
                "utf8mb4", "", "note", "", "utf8mb4_0900_ai_ci"), "VARCHAR(100)")).contains("COMMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts(null, null, null, "VIRTUAL GENERATED", "",
                "`a` + 1", null), "INT")).contains("generated");
        assertThat(SchemaSynchronizer.mySqlBlockReason(plain, "NVARCHAR(100)")).contains("utf8mb3");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb3_general_ci", "utf8mb4_0900_ai_ci",
                "utf8mb3", "", "", "", "utf8mb3_general_ci"), "NVARCHAR(100)")).isNull();
        // National widening must not reset a non-default utf8mb3 collation.
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb3_bin", "utf8mb4_0900_ai_ci",
                "utf8mb3", "", "", "", "utf8mb3_general_ci"), "NVARCHAR(100)")).contains("utf8mb3_bin");
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8_bin", "utf8_general_ci",
                "utf8", "", "", "", "utf8_general_ci"), "NCHAR(10)")).contains("utf8_bin");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "varbinary(10) /*M!100301 COMPRESSED*/"), "VARBINARY(20)")).contains("COMPRESSED");
        var tinyint1 = new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null, "", "", "", null, "tinyint(1)");
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "TINYINT")).contains("display width");
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "TINYINT(10)")).contains("display width");
        // Not a TINYINT declaration: a widen to another integer type is not a display-width change.
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "BOOLEANISH")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "SMALLINT NOT NULL")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "INT1")).contains("display width");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(1) unsigned"), "SMALLINT UNSIGNED")).isNull();
        SchemaSynchronizer.MySqlColumnFacts zerofill1 = new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(1) unsigned zerofill");
        assertThat(SchemaSynchronizer.mySqlBlockReason(zerofill1, "TINYINT UNSIGNED"))
                .isEqualTo("ZEROFILL attribute would be dropped by MODIFY COLUMN");
        assertThat(SchemaSynchronizer.mySqlBlockReason(zerofill1, "TINYINT UNSIGNED ZEROFILL"))
                .isEqualTo("ZEROFILL display width (1) would be reset by MODIFY COLUMN");
        // A non-default ZEROFILL width changes the padding; the default width is kept by MODIFY.
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "int(5) unsigned zerofill"), "INT UNSIGNED ZEROFILL"))
                .isEqualTo("ZEROFILL display width (5) would be reset by MODIFY COLUMN");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "int(10) unsigned zerofill"), "INT UNSIGNED ZEROFILL NOT NULL")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "int unsigned"), "INT UNSIGNED DEFAULT 'zerofill'")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "enum('zerofill','x')"), "ENUM('zerofill','x')")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "set('a''s zerofill','x')"), "SET('a''s zerofill','x')")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "BOOLEAN")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "bool NOT NULL")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "TINYINT( 1 ) DEFAULT 0")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(4)"), "TINYINT")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(10) unsigned"), "TINYINT UNSIGNED")).isNull();
        for (String liveType : List.of("tinyint", "tinyint(4)", "tinyint(10)", "tinyint unsigned")) {
            var facts = new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null, "", "", "", null, liveType);
            assertThat(SchemaSynchronizer.mySqlBlockReason(facts, "BOOLEAN DEFAULT 1")).as(liveType)
                    .contains("display width to (1)");
            assertThat(SchemaSynchronizer.mySqlBlockReason(facts, "TINYINT(1)")).as(liveType)
                    .contains("display width to (1)");
        }
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(1) unsigned"), "TINYINT UNSIGNED")).contains("would be reset")
                .contains("declare TINYINT(1) UNSIGNED").doesNotContain("BOOLEAN or");
        SchemaSynchronizer.MySqlColumnFacts unsigned1 = new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(1) unsigned");
        assertThat(SchemaSynchronizer.mySqlBlockReason(unsigned1, "TINYINT(1) UNSIGNED DEFAULT 0")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "tinyint(4) unsigned"), "TINYINT(1) UNSIGNED"))
                .contains("display width to (1)").endsWith("declare TINYINT UNSIGNED");
        assertThat(SchemaSynchronizer.mySqlBlockReason(tinyint1, "TINYINT")).endsWith("declare BOOLEAN or TINYINT(1)");
        // COMPRESSED is matched as MariaDB's versioned comment, not as text inside an ENUM.
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null, null,
                "", "", "", null, "enum('COMPRESSED','PLAIN')"), "ENUM('COMPRESSED','PLAIN')")).isNull();

        // Snapshot createSql keeps what a declaration cannot: charset/collation, COMPRESSED, INVISIBLE, COMMENT.
        assertThat(SchemaSnapshotWriter.mysqlColumnClauses("latin1", "latin1_bin", "utf8mb4_bin", "it's \\ x",
                "on update current_timestamp(), INVISIBLE", "varchar(10) /*!100301 COMPRESSED*/", true))
                .containsExactly(" COMPRESSED CHARACTER SET latin1 COLLATE latin1_bin",
                        " INVISIBLE COMMENT 'it''s \\\\ x'");
        assertThat(SchemaSnapshotWriter.mysqlColumnClauses("utf8mb4", "utf8mb4_bin", "utf8mb4_bin", "",
                "DEFAULT_GENERATED", "varchar(10)", false)).containsExactly("", "");
        assertThat(SchemaSnapshotWriter.mysqlColumnClauses(null, null, "utf8mb4_bin", null,
                "VISIBLE_ISH", "varbinary(4) /*M!100301 COMPRESSED*/", false)).containsExactly("", "");
        // Other attributes still block a national column before the charset check.
        assertThat(SchemaSynchronizer.mySqlBlockReason(mysqlFacts("utf8mb3_general_ci", "utf8mb4_0900_ai_ci",
                "utf8mb3", "", "note", "", "utf8mb3_general_ci"), "NVARCHAR(100)")).contains("COMMENT");

        NonDestructiveAlterPlanner.Plan widen = NonDestructiveAlterPlanner.plan("items", "label",
                ColumnDefinitionParser.parse("VARCHAR(100)"), new LiveColumn("VARCHAR", 40, null, false, null));
        NonDestructiveAlterPlanner.Plan blocked = SchemaSynchronizer.mySqlFamilyColumnPlan("items", "label",
                "VARCHAR(100)", widen, "column collation x differs");
        assertThat(blocked.applySql()).isEmpty();
        assertThat(blocked.pendingSql()).singleElement().asString().contains("-- pending: column collation x");

        // Pending MODIFY still warns about what it would reset.
        NonDestructiveAlterPlanner.Plan tighten = NonDestructiveAlterPlanner.plan("items", "nick",
                ColumnDefinitionParser.parse("VARCHAR(20) NOT NULL"), new LiveColumn("VARCHAR", 20, null, false, null));
        assertThat(tighten.pendingSql()).isNotEmpty();
        assertThat(SchemaSynchronizer.mySqlFamilyColumnPlan("items", "nick", "NVARCHAR(20) NOT NULL", tighten,
                "NVARCHAR/NCHAR is utf8mb3 but the column character set is utf8mb4").pendingSql())
                .singleElement().asString().contains("unsafe type/nullability change; also NVARCHAR/NCHAR is utf8mb3");
        assertThat(SchemaSynchronizer.mySqlFamilyColumnPlan("items", "nick", "VARCHAR(20) NOT NULL", tighten, null)
                .pendingSql()).singleElement().asString().endsWith("unsafe type/nullability change");
        assertThat(SchemaSynchronizer.mySqlNationalDrift(mysqlFacts("utf8mb3_general_ci", "x", "utf8mb3", "", "", "",
                null))).contains("could not be determined");

        // A default SET is auto-applied only when the server stores it exactly as compared; else pending.
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT (concat('a','b'))", "VARCHAR", 10,
                "(concat(_utf8mb4'a',_utf8mb4'b'))")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT (uuid())", "VARCHAR", 10, "'x'")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT (uuid())", "VARCHAR", 10, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT (uuid())", "VARCHAR", 10, "(uuid())")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT 'y'", "VARCHAR", 10, "'x'")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT 'a\\\\b'", "VARCHAR", 10, "'x'")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(10)", "VARCHAR", 10, "(uuid())")).isEqualTo("apply");
        // Server rewrites of the declared form converge instead of re-running MODIFY every sync.
        assertThat(mysqlDefaultPlan("DATETIME(3) DEFAULT NOW(3)", "DATETIME", 3, "CURRENT_TIMESTAMP(3)"))
                .isEqualTo("same");
        assertThat(mysqlDefaultPlan("DATETIME DEFAULT LOCALTIMESTAMP", "DATETIME", 0, "current_timestamp()"))
                .isEqualTo("same");
        assertThat(mysqlDefaultPlan("DATETIME DEFAULT CURRENT_TIMESTAMP(0)", "DATETIME", 0, "CURRENT_TIMESTAMP"))
                .isEqualTo("same");
        assertThat(mysqlDefaultPlan("DATETIME(3) DEFAULT NOW(3)", "DATETIME", 3, null)).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("DATETIME(6) DEFAULT NOW(6)", "DATETIME", 6, "CURRENT_TIMESTAMP(3)"))
                .isEqualTo("apply");
        assertThat(mysqlDefaultPlan("DECIMAL(10,2) DEFAULT 1", "DECIMAL", 10, "1.00")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("DECIMAL(10,2) DEFAULT '1.5'", "DECIMAL", 10, "1.50")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("DECIMAL(10,2) DEFAULT 2", "DECIMAL", 10, "1.00")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("TINYINT DEFAULT FALSE", "TINYINT", null, "0")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("INT DEFAULT (1 + 1)", "INT", null, "2")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DATETIME(3) DEFAULT '2020-01-01 00:00:00'", "DATETIME", 3,
                "'2020-01-01 00:00:00.000'")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("DATETIME(3) DEFAULT '2020-01-01 00:00:00.5'", "DATETIME", 3,
                "'2020-01-01 00:00:00.000'")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("DATE DEFAULT '2020-1-1'", "DATE", null, "'2020-01-01'")).isEqualTo("pending");
        // Values the server rounds, truncates, or reinterprets for the column are never auto-applied.
        assertThat(mysqlDefaultPlan("DECIMAL(5,2) DEFAULT 1.555", "DECIMAL", 5, "1.00")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DECIMAL(5,2) DEFAULT 1.55", "DECIMAL", 5, "1.00")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("INT DEFAULT 1.5", "INT", null, "1")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("INT DEFAULT 1.0", "INT", null, null)).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("FLOAT DEFAULT 1.23456789", "FLOAT", null, "1.23457")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("YEAR DEFAULT 24", "YEAR", null, "2023")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("YEAR DEFAULT '0'", "YEAR", null, "0000")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DATE DEFAULT '2020-01-01 10:00:00'", "DATE", null, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DATE DEFAULT '2020-01-01'", "DATE", null, null)).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("DATETIME DEFAULT '2020-01-01 00:00:00.6'", "DATETIME", 0, null))
                .isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DATETIME DEFAULT '2020-01-01'", "DATETIME", 0, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("DATETIME(3) DEFAULT CURRENT_TIMESTAMP", "DATETIME", 3, null))
                .isEqualTo("pending");
        assertThat(mysqlDefaultPlan("TIME DEFAULT LOCALTIME", "TIME", 0, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("TIME(2) DEFAULT '10:00:00.25'", "TIME", 2, null)).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("CHAR(5) DEFAULT 'a  '", "CHAR", 5, "'a'")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(5) DEFAULT 'a'", "VARBINARY", 5, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("BIT(1) DEFAULT 1", "BIT", 1, "b'1'")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("BIT(1) DEFAULT TRUE", "BIT", 1, "b'0'")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(10) DEFAULT CURRENT_TIMESTAMP", "VARCHAR", 10, null))
                .isEqualTo("pending");
        // MySQL reports the string default 'NULL' as the text NULL; "no default" is SQL NULL.
        assertThat(SchemaSynchronizer.reportsNoDefaultAsNullText(DatabaseDialect.MYSQL)).isFalse();
        assertThat(SchemaSynchronizer.reportsNoDefaultAsNullText(DatabaseDialect.POSTGRESQL)).isFalse();
        assertThat(SchemaSynchronizer.reportsNoDefaultAsNullText(DatabaseDialect.MARIADB)).isTrue();
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("NULL", "VARCHAR", false)).isEqualTo("'NULL'");
    }

    @Test
    void bitIsAFixedLengthBitFieldNotBoolean() {
        assertThat(ColumnDefinitionParser.parse("BIT")).isEqualTo(new ColumnSpec("BIT", 1, null, false, null));
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("BIT", 8, null, "BIT", 8, null))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.SAME);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("BIT", 1, null, "BIT", 8, null))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.INCOMPATIBLE);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("BIT", 1, null, "BOOLEAN", null, null))
                .isNotIn(NonDestructiveAlterPlanner.TypeChange.SAME, NonDestructiveAlterPlanner.TypeChange.WIDEN);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("BOOLEAN", null, null, "BIT", 1, null))
                .isNotIn(NonDestructiveAlterPlanner.TypeChange.SAME, NonDestructiveAlterPlanner.TypeChange.WIDEN);
        assertThat(SchemaSnapshotWriter.columnType("BIT", 8, null, DatabaseDialect.MYSQL)).isEqualTo("BIT(8)");
        assertThat(SchemaSnapshotWriter.columnType("BIT", 1, null, DatabaseDialect.SQLSERVER)).isEqualTo("BIT");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BIT", "tinyint(1)")).isEqualTo("TINYINT");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BOOLEAN", "tinyint(1)")).isEqualTo("TINYINT");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BOOLEAN", "tinyint(1) unsigned")).isEqualTo("TINYINT UNSIGNED");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BOOLEAN", "tinyint(1) unsigned zerofill"))
                .isEqualTo("TINYINT UNSIGNED ZEROFILL");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BIT", "bit(1)")).isEqualTo("BIT");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("BIT", null)).isEqualTo("BIT");
        assertThat(SchemaSnapshotWriter.mysqlTypeName("VARCHAR", "tinyint(1)")).isEqualTo("VARCHAR");
        // Signed BOOLEAN over an unsigned live TINYINT is a type change, never an auto-applied MODIFY.
        assertThat(mysqlDefaultPlan("BOOLEAN DEFAULT 1", "TINYINT UNSIGNED", null, "0")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("BOOLEAN DEFAULT 1", "TINYINT", null, "0")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("TIMESTAMP DEFAULT '2020-01-01 00:00:00'", "TIMESTAMP", 0, null))
                .isEqualTo("pending");
        assertThat(ColumnDefinitionParser.normalizeDefault("'10100001'::\"bit\"")).isEqualTo(
                ColumnDefinitionParser.normalizeDefault("X'A1'"));
        assertThat(ColumnDefinitionParser.normalizeDefault("x'0f'")).isEqualTo("'00001111'");
        assertThat(SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse("BOOLEAN DEFAULT FALSE"),
                DatabaseDialect.MYSQL)).isEqualTo(new ColumnSpec("TINYINT", null, null, false, "FALSE"));
        assertThat(SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse("BOOLEAN"),
                DatabaseDialect.POSTGRESQL).baseType()).isEqualTo("BOOLEAN");
        assertThat(ColumnDefinitionParser.parse("BIT VARYING(5)")).isEqualTo(new ColumnSpec("VARBIT", 5, null, false, null));
        assertThat(ColumnDefinitionParser.normalizeDefault("'101'::\"bit\"")).isEqualTo(
                ColumnDefinitionParser.normalizeDefault("B'101'"));
        assertThat(SchemaSnapshotWriter.columnType("VARBIT", 5, null, DatabaseDialect.POSTGRESQL)).isEqualTo("VARBIT(5)");
        // A default change on a live BIT(8) declared as BOOLEAN must not MODIFY the type.
        assertThat(mysqlDefaultPlan("BOOLEAN NOT NULL", "BIT", 8, null)).isEqualTo("pending");
    }

    @Test
    void implicitTimestampDefaultsBlockOnlyTimestampTypedColumns() {
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE IF NOT EXISTS t (id INT NOT NULL, ts timestamp)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (ts TIMESTAMP(3) NULL, id INT)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT, `timestamp` TIMESTAMP NULL)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT, `timestamp` DATETIME NULL, timestamp DATETIME)")).isFalse();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT, at DATETIME DEFAULT CURRENT_TIMESTAMP, note VARCHAR(20) DEFAULT 'x TIMESTAMP')"))
                .isFalse();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, -- created\n ts TIMESTAMP)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, /* c */ ts TIMESTAMP)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, # don't\n ts TIMESTAMP, v VARCHAR(5) DEFAULT 'x')")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, ts$1 TIMESTAMP)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, \"ts\" TIMESTAMP)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY /* , ts TIMESTAMP */)")).isFalse();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, \"t\"\"s\" TIMESTAMP)")).isTrue();
        assertThat(SchemaSynchronizer.declaresTimestampColumn(
                "CREATE TABLE t (id INT PRIMARY KEY, \"t,s\" DATETIME, v VARCHAR(5) DEFAULT 'TIMESTAMP')")).isFalse();
        assertThatThrownBy(() -> SchemaSynchronizer.requireExplicitTimestampDefaults(false, "adding column t.ts"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("adding column t.ts");
        SchemaSynchronizer.requireExplicitTimestampDefaults(true, "adding column t.ts");
    }

    @Test
    void keywordsInsideDefaultLiteralsAndExplicitNullSurviveParsing() {
        assertThat(ColumnDefinitionParser.parse("VARCHAR(20) DEFAULT 'my IDENTITY'").defaultExpr())
                .isEqualTo("'my IDENTITY'");
        assertThat(ColumnDefinitionParser.parse("VARCHAR(20) DEFAULT 'x AUTO_INCREMENT' NOT NULL"))
                .isEqualTo(new ColumnSpec("VARCHAR", 20, null, true, "'x AUTO_INCREMENT'"));
        assertThat(ColumnDefinitionParser.parse("BIGINT NOT NULL AUTO_INCREMENT"))
                .isEqualTo(new ColumnSpec("BIGINT", null, null, true, null));
        assertThat(ColumnDefinitionParser.parse("INT IDENTITY(1,1) NOT NULL"))
                .isEqualTo(new ColumnSpec("INTEGER", null, null, true, null));
        assertThat(ColumnDefinitionParser.parse("TIMESTAMP NULL"))
                .isEqualTo(new ColumnSpec("TIMESTAMP", null, null, false, null));
        assertThat(ColumnDefinitionParser.parse("INT NULL DEFAULT 0"))
                .isEqualTo(new ColumnSpec("INTEGER", null, null, false, "0"));
        assertThat(ColumnDefinitionParser.parse("VARCHAR(10) DEFAULT 'NULL'").defaultExpr()).isEqualTo("'NULL'");
        // Oracle requires DEFAULT before inline constraints.
        assertThat(ColumnDefinitionParser.parse("VARCHAR2(10) DEFAULT 'x' NULL"))
                .isEqualTo(new ColumnSpec("VARCHAR", 10, null, false, "'x'"));
        assertThat(ColumnDefinitionParser.parse("INTEGER DEFAULT 0 NULL").defaultExpr()).isEqualTo("0");
        assertThat(ColumnDefinitionParser.parse("VARCHAR(10) DEFAULT 'a NULL'").defaultExpr()).isEqualTo("'a NULL'");
        assertThat(ColumnDefinitionParser.parse("VARCHAR(10) DEFAULT NULL").defaultExpr()).isEqualTo("NULL");
        assertThat(ColumnDefinitionParser.parse("BOOLEAN DEFAULT x IS NULL").defaultExpr()).isEqualTo("x IS NULL");
        assertThat(SchemaSynchronizer.mySqlUnpredictableDefaultReason("(uuid())", null))
                .contains("server reports no default").doesNotContain("null");
        // NOW without parentheses is an identifier, not the function.
        assertThat(SchemaSynchronizer.mySqlComparableDefault("NOW", "VARCHAR")).isEqualTo("NOW");
        // Only bare TRUE/FALSE are boolean literals; 'TRUE' on an INT column is invalid DDL, not 1.
        assertThat(mysqlDefaultPlan("INT DEFAULT 'TRUE'", "INTEGER", null, "1")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("INT DEFAULT TRUE", "INTEGER", null, "1")).isEqualTo("same");
    }

    @Test
    void keywordsInsideDefaultLiteralsDoNotSatisfyMySqlAttributeChecks() {
        var binCollation = mysqlFacts("utf8mb4_bin", "utf8mb4_0900_ai_ci", "utf8mb4", "", "", "",
                "utf8mb4_0900_ai_ci");
        var latin1 = mysqlFacts("latin1_swedish_ci", "utf8mb4_0900_ai_ci", "latin1", "", "", "",
                "latin1_swedish_ci");
        var commented = mysqlFacts("utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci", "utf8mb4", "", "keep me", "",
                "utf8mb4_0900_ai_ci");
        var onUpdate = mysqlFacts(null, null, null, "DEFAULT_GENERATED on update CURRENT_TIMESTAMP", "", "", null);
        var autoInc = mysqlFacts(null, null, null, "auto_increment", "", "", null);
        assertThat(SchemaSynchronizer.mySqlBlockReason(binCollation, "VARCHAR(20) DEFAULT 'COLLATE'"))
                .contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(latin1, "VARCHAR(20) DEFAULT 'character set'"))
                .contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(commented, "VARCHAR(20) DEFAULT 'COMMENT'"))
                .contains("COMMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(onUpdate, "VARCHAR(20) DEFAULT 'on update'"))
                .contains("ON UPDATE");
        assertThat(SchemaSynchronizer.mySqlBlockReason(autoInc, "VARCHAR(20) DEFAULT 'AUTO_INCREMENT'"))
                .contains("AUTO_INCREMENT");
        // Identifiers in an expression default are not clauses either.
        assertThat(SchemaSynchronizer.mySqlBlockReason(commented, "BIGINT DEFAULT (comment_count + 1)"))
                .contains("COMMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(binCollation, "VARCHAR(40) DEFAULT (collate_key)"))
                .contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(onUpdate, "DATETIME DEFAULT (on_update_at)"))
                .contains("ON UPDATE");
        assertThat(SchemaSynchronizer.mySqlBlockReason(autoInc, "BIGINT DEFAULT (auto_increment_seed)"))
                .contains("AUTO_INCREMENT");
        // The clauses the parser accepts still count outside a literal.
        assertThat(SchemaSynchronizer.mySqlBlockReason(onUpdate,
                "TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(autoInc, "BIGINT NOT NULL AUTO_INCREMENT")).isNull();
    }

    @Test
    void onUpdateIsParsedOutOfTheDefaultAndComparedOnEverySync() {
        assertThat(ColumnDefinitionParser.parse("DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL"))
                .isEqualTo(new ColumnSpec("DATETIME", null, null, true, "CURRENT_TIMESTAMP"));
        assertThat(ColumnDefinitionParser.parse("TIMESTAMP(3) NULL ON UPDATE CURRENT_TIMESTAMP(3)"))
                .isEqualTo(new ColumnSpec("TIMESTAMP", 3, null, false, null));
        assertThat(ColumnDefinitionParser.parse("VARCHAR(20) DEFAULT 'x ON UPDATE NOW()'").defaultExpr())
                .isEqualTo("'x ON UPDATE NOW()'");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("DATETIME DEFAULT NULL ON UPDATE (now())"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ColumnDefinitionParser.onUpdateExpr("DATETIME(3) DEFAULT NOW(3) ON UPDATE current_timestamp( 3 )"))
                .isEqualTo("current_timestamp(3)");
        assertThat(ColumnDefinitionParser.onUpdateExpr("VARCHAR(20) DEFAULT 'a ON UPDATE NOW()'")).isNull();
        assertThat(ColumnDefinitionParser.onUpdateExpr("DATETIME")).isNull();
        // Only CURRENT_TIMESTAMP and synonyms, as a whole clause; anything else is rejected, not stripped.
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("DATETIME ON UPDATE UTC_TIMESTAMP()"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColumnDefinitionParser.parse(
                "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP + 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("DATETIME ON UPDATE NOW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ColumnDefinitionParser.parse("TIMESTAMP ON UPDATE CURRENT_TIMESTAMP DEFAULT CURRENT_TIMESTAMP"))
                .isEqualTo(new ColumnSpec("TIMESTAMP", null, null, false, "CURRENT_TIMESTAMP"));
        assertThat(ColumnDefinitionParser.parse("DATETIME ON\nUPDATE NOW() NOT NULL"))
                .isEqualTo(new ColumnSpec("DATETIME", null, null, true, null));
        // 'a\' ON UPDATE …' is one literal with backslash escapes and a literal plus a clause without.
        String ambiguous = "VARCHAR(40) DEFAULT 'a\\' ON UPDATE CURRENT_TIMESTAMP'";
        assertThat(ColumnDefinitionParser.onUpdateExpr(ambiguous)).isNull();
        assertThatThrownBy(() -> ColumnDefinitionParser.parse(ambiguous))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous ON UPDATE");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("VARCHAR(40) DEFAULT 'a\\' ON UPDATE NOW()"))
                .isInstanceOf(IllegalArgumentException.class);
        String escapedBackslash = "DATETIME DEFAULT 'x\\\\' ON UPDATE CURRENT_TIMESTAMP";
        assertThat(ColumnDefinitionParser.onUpdateExpr(escapedBackslash)).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(ColumnDefinitionParser.parse(escapedBackslash).defaultExpr()).isEqualTo("'x\\\\'");

        // Validation: MySQL family, temporal type, matching precision.
        SchemaSynchronizer.requireSupportedOnUpdate(ColumnDefinitionParser.parse("DATETIME(3) ON UPDATE NOW(3)"),
                "DATETIME(3) ON UPDATE NOW(3)", DatabaseDialect.MARIADB, "t.c");
        assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedOnUpdate(
                ColumnDefinitionParser.parse("TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"),
                "TIMESTAMP ON UPDATE CURRENT_TIMESTAMP", DatabaseDialect.POSTGRESQL, "t.c"))
                .hasMessageContaining("only on MySQL/MariaDB");
        assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedOnUpdate(
                ColumnDefinitionParser.parse("INT ON UPDATE CURRENT_TIMESTAMP"),
                "INT ON UPDATE CURRENT_TIMESTAMP", DatabaseDialect.MYSQL, "t.c"))
                .hasMessageContaining("DATETIME or TIMESTAMP");
        assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedOnUpdate(
                ColumnDefinitionParser.parse("DATETIME(3) ON UPDATE CURRENT_TIMESTAMP"),
                "DATETIME(3) ON UPDATE CURRENT_TIMESTAMP", DatabaseDialect.MYSQL, "t.c"))
                .hasMessageContaining("precision");
        // MariaDB accepts a bare ON UPDATE on DATETIME(n) and stores it with the column's precision.
        SchemaSynchronizer.requireSupportedOnUpdate(ColumnDefinitionParser.parse("DATETIME(3) ON UPDATE CURRENT_TIMESTAMP"),
                "DATETIME(3) ON UPDATE CURRENT_TIMESTAMP", DatabaseDialect.MARIADB, "t.c");
        assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedOnUpdate(
                ColumnDefinitionParser.parse("DATETIME(3) ON UPDATE CURRENT_TIMESTAMP(6)"),
                "DATETIME(3) ON UPDATE CURRENT_TIMESTAMP(6)", DatabaseDialect.MARIADB, "t.c"))
                .hasMessageContaining("precision");
        String bare = "DATETIME(3) ON UPDATE CURRENT_TIMESTAMP";
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift(SchemaSynchronizer.effectiveOnUpdate(bare,
                ColumnDefinitionParser.parse(bare), DatabaseDialect.MARIADB), "current_timestamp(3)")).isNull();
        assertThat(SchemaSynchronizer.effectiveOnUpdate(bare, ColumnDefinitionParser.parse(bare), DatabaseDialect.MYSQL))
                .isEqualTo("CURRENT_TIMESTAMP");
        String zero = "DATETIME ON UPDATE CURRENT_TIMESTAMP";
        assertThat(SchemaSynchronizer.effectiveOnUpdate(zero, ColumnDefinitionParser.parse(zero), DatabaseDialect.MARIADB))
                .isEqualTo("CURRENT_TIMESTAMP");
        // MariaDB 10.3/11.4 reject an explicit (0) on DATETIME(3) (error 1294); NOW() and LOCALTIMESTAMP are bare.
        for (String explicitZero : List.of("DATETIME(3) ON UPDATE CURRENT_TIMESTAMP(0)", "DATETIME(3) ON UPDATE NOW( 0 )")) {
            assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedOnUpdate(ColumnDefinitionParser.parse(explicitZero),
                    explicitZero, DatabaseDialect.MARIADB, "t.c")).as(explicitZero).hasMessageContaining("precision");
            assertThat(SchemaSynchronizer.effectiveOnUpdate(explicitZero, ColumnDefinitionParser.parse(explicitZero),
                    DatabaseDialect.MARIADB)).as(explicitZero).doesNotContain("(3)");
        }
        for (String bareSpelling : List.of("DATETIME(3) ON UPDATE NOW()", "DATETIME(3) ON UPDATE CURRENT_TIMESTAMP()",
                "DATETIME(3) ON UPDATE LOCALTIMESTAMP")) {
            SchemaSynchronizer.requireSupportedOnUpdate(ColumnDefinitionParser.parse(bareSpelling), bareSpelling,
                    DatabaseDialect.MARIADB, "t.c");
            assertThat(SchemaSynchronizer.effectiveOnUpdate(bareSpelling, ColumnDefinitionParser.parse(bareSpelling),
                    DatabaseDialect.MARIADB)).as(bareSpelling).isEqualTo("CURRENT_TIMESTAMP(3)");
        }

        // Declared and live agree (spelling-insensitive): no drift.
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift(null, null)).isNull();
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift("NOW()", "CURRENT_TIMESTAMP")).isNull();
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift("current_timestamp(3)", "CURRENT_TIMESTAMP(3)")).isNull();
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift("CURRENT_TIMESTAMP(0)", "current_timestamp()")).isNull();
        // Either side missing, or a precision difference, is drift.
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift("CURRENT_TIMESTAMP", null)).contains("server reports none");
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift(null, "CURRENT_TIMESTAMP")).contains("declared none");
        assertThat(SchemaSynchronizer.mySqlOnUpdateDrift("CURRENT_TIMESTAMP(3)", "CURRENT_TIMESTAMP")).isNotNull();

        // A string default with a control character is never auto-applied (MariaDB reports it escaped).
        assertThat(mysqlDefaultPlan("VARCHAR(20) DEFAULT 'nul\u0000x'", "VARCHAR", 20, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(20) DEFAULT 'tab\tx'", "VARCHAR", 20, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARCHAR(20) DEFAULT 'plain'", "VARCHAR", 20, null)).isEqualTo("apply");
    }

    @Test
    void binaryDefaultsAreReadFromRawShowCreateTableBytes() {
        // Renderings observed with character_set_results=binary on MySQL 8.4, MariaDB 10.3/11.4, MariaDB 11.8.
        byte[] mysql = ("CREATE TABLE `zz_b` (\n"
                + "  `nn` varbinary(8) NOT NULL DEFAULT 0x00FF,\n"
                + "  `nl` varbinary(8) DEFAULT '\\0''\\\\\\n',\n"
                + "  `s` binary(4) NOT NULL DEFAULT 'ab\\0\\0',\n"
                + "  `e` varbinary(4) NOT NULL DEFAULT '',\n"
                + "  `one` varbinary(4) DEFAULT 'ab' /*!80023 INVISIBLE */,\n"
                + "  `x``y` varbinary(4) DEFAULT 0x01\n"
                + ") ENGINE=InnoDB").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "nn")).isEqualTo("0x00FF");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "nl")).isEqualTo("0x00275C0A");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "s")).isEqualTo("0x61620000");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "e")).isEqualTo("''");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "one")).isEqualTo("0x6162");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "x`y")).isEqualTo("0x01");
        // Column names are exact: no prefix or case-folded match.
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "n")).isNull();
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mysql, "missing")).isNull();

        var raw = new java.io.ByteArrayOutputStream();
        raw.writeBytes("CREATE TABLE `zz_b` (\n  `nn` varbinary(8) NOT NULL DEFAULT '\\0".getBytes(StandardCharsets.ISO_8859_1));
        raw.write(0xFF);
        raw.writeBytes("',\n  `u` varbinary(4) DEFAULT '".getBytes(StandardCharsets.ISO_8859_1));
        raw.write(0xFF);
        raw.write(0x80);
        raw.writeBytes("',\n  `q` varbinary(4) DEFAULT 'it''s'\n)".getBytes(StandardCharsets.ISO_8859_1));
        byte[] mariaDb = raw.toByteArray();
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mariaDb, "nn")).isEqualTo("0x00FF");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mariaDb, "u")).isEqualTo("0xFF80");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mariaDb, "q")).isEqualTo("0x69742773");

        byte[] mariaDb118 = ("CREATE TABLE `zz_b` (\n  `s` binary(4) NOT NULL DEFAULT x'61620000',\n"
                + "  `e` varbinary(4) NOT NULL DEFAULT ''\n)").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mariaDb118, "s")).isEqualTo("0x61620000");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(mariaDb118, "e")).isEqualTo("''");

        // sql_quote_show_create=0 writes bare names; an unexpected shape is unreadable, never guessed.
        byte[] bare = "CREATE TABLE t (\n  raw varbinary(4) DEFAULT 0x0A\n)".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(bare, "raw")).isEqualTo("0x0A");
        byte[] odd = "CREATE TABLE t (\n  `raw` varbinary(4) DEFAULT (0x0A)\n)".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(odd, "raw")).isNull();

        // MariaDB renders COMPRESSED as a versioned comment between the type and NOT NULL/DEFAULT.
        byte[] compressed = ("CREATE TABLE `zz_c` (\n  `a` varbinary(10) /*M!100301 COMPRESSED*/ NOT NULL DEFAULT 'ab',\n"
                + "  `b` varbinary(10) /*M!100301 COMPRESSED*/ DEFAULT '\\0\u00ff'\n)")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(compressed, "a")).isEqualTo("0x6162");
        assertThat(SchemaSnapshotWriter.binaryDefaultInCreateTable(compressed, "b")).isEqualTo("0x00FF");

        byte[] temporary = "CREATE TEMPORARY TABLE `t` (\n  `raw` varbinary(4) DEFAULT 0x0A\n)"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> SchemaSnapshotWriter.requireBaseTable(temporary, "t"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEMPORARY table named t");
        assertThat(SchemaSnapshotWriter.requireBaseTable(bare, "t")).isSameAs(bare);
    }

    @Test
    void binaryDefaultsWithoutTableSelectStayPending() throws Exception {
        var denied = new java.sql.SQLException("SELECT command denied", "42000", 1142);
        assertThat(SchemaSnapshotWriter.unreadableBinaryDefaults(List.of("Raw", "b"), denied))
                .containsEntry("raw", SchemaSnapshotWriter.UNREADABLE_BINARY_DEFAULT)
                .containsEntry("b", SchemaSnapshotWriter.UNREADABLE_BINARY_DEFAULT);
        assertThat(SchemaSnapshotWriter.unreadableBinaryDefaults(List.of("c"),
                new java.sql.SQLException("column denied", "42000", 1143))).containsKey("c");
        var other = new java.sql.SQLException("syntax", "42000", 1064);
        assertThatThrownBy(() -> SchemaSnapshotWriter.unreadableBinaryDefaults(List.of("c"), other)).isSameAs(other);
        // Unreadable never matches a declaration, but a declared removal still applies.
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 0x00FF", "VARBINARY", 4,
                SchemaSnapshotWriter.UNREADABLE_BINARY_DEFAULT)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(4)", "VARBINARY", 4,
                SchemaSnapshotWriter.UNREADABLE_BINARY_DEFAULT)).isEqualTo("apply");
    }

    @Test
    void mysqlBinaryDefaultsCompareAsBytes() {
        // MySQL reports 0x6162, MariaDB 'ab'; the snapshot must write a literal MySQL accepts.
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("0x6162", "VARBINARY", false)).isEqualTo("0x6162");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("0x6162", "BINARY", false)).isEqualTo("0x6162");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("0x", "VARBINARY", false)).isEqualTo("''");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("0x6162", "VARCHAR", false)).isEqualTo("'0x6162'");
        assertThat(ColumnDefinitionParser.parse("VARBINARY(10) DEFAULT 0x6162").defaultExpr()).isEqualTo("0x6162");
        assertThat(mysqlDefaultPlan("VARBINARY(10) DEFAULT 'ab'", "VARBINARY", 10, "0x6162")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(10) DEFAULT X'6162'", "VARBINARY", 10, "'ab'")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(10) DEFAULT 0x6162", "VARBINARY", 10, "0x6162")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("BINARY(4) DEFAULT 'ab'", "BINARY", 4, "0x61620000")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(10) DEFAULT 'ab'", "VARBINARY", 10, "0x616263")).isEqualTo("pending");
        // Binary defaults are never auto-applied; dropping one is.
        assertThat(mysqlDefaultPlan("VARBINARY(10) DEFAULT 'ab'", "VARBINARY", 10, null)).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(10)", "VARBINARY", 10, "0x6162")).isEqualTo("apply");
        // A non-ASCII string literal's bytes depend on the session charset: never equal, and a widen stays pending.
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 'é'", "VARBINARY", 4, "0xC3A9")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 'é'", "VARBINARY", 4, "0xE9")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(8) DEFAULT 'é'", "VARBINARY", 4, "0xC3A9")).isEqualTo("pending");
        assertThat(mysqlDefaultPlan("VARBINARY(8) DEFAULT X'C3A9'", "VARBINARY", 4, "0xC3A9")).isEqualTo("apply");
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT X'C3A9'", "VARBINARY", 4, "0xC3A9")).isEqualTo("same");
        assertThat(SchemaSynchronizer.mySqlUnpredictableDefaultReason("'é'", "0xE9")).contains("declare the bytes as X'");
        assertThat(SchemaSynchronizer.mySqlUnpredictableDefaultReason("'a\\nb'", "0x610A62")).contains("sql_mode");
        assertThat(SchemaSynchronizer.mySqlUnpredictableDefaultReason("'ab'", "0x616263"))
                .contains("declare it as a snapshot writes it");
        // A bare integer on a binary column is stored as its decimal text.
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 5", "VARBINARY", 4, "0x35")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 007", "VARBINARY", 4, "0x37")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT -12", "VARBINARY", 4, "'-12'")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("BINARY(2) DEFAULT 5", "BINARY", 2, "0x3500")).isEqualTo("same");
        assertThat(mysqlDefaultPlan("VARBINARY(4) DEFAULT 5", "VARBINARY", 4, "0x05")).isEqualTo("pending");
    }

    /** How the MySQL family handles a declared column against a live default: same, apply, or pending. */
    private static String mysqlDefaultPlan(String declared, String liveType, Integer liveLength, String liveDefault) {
        ColumnSpec parsed = SchemaSynchronizer.withDefaultFractionalPrecision(SchemaSynchronizer.foldNationalType(
                ColumnDefinitionParser.parse(declared), DatabaseDialect.MYSQL), DatabaseDialect.MYSQL);
        ColumnSpec target = new ColumnSpec(parsed.baseType(), parsed.length(), parsed.scale(), parsed.notNull(),
                SchemaSynchronizer.mySqlComparableDefault(parsed.defaultExpr(), parsed.baseType(), parsed.length()));
        LiveColumn live = new LiveColumn(liveType, liveLength, parsed.scale(), false,
                SchemaSynchronizer.mySqlComparableDefault(liveDefault, parsed.baseType(), liveLength));
        NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        String reason = SchemaSynchronizer.mySqlUnpredictableDefaultChange(plan, target)
                ? SchemaSynchronizer.mySqlUnpredictableDefaultReason(parsed.defaultExpr(), liveDefault) : null;
        NonDestructiveAlterPlanner.Plan result = SchemaSynchronizer.mySqlFamilyColumnPlan("t", "c", declared, plan,
                reason);
        return !result.pendingSql().isEmpty() ? "pending" : !result.applySql().isEmpty() ? "apply" : "same";
    }

    @Test
    void mysqlNationalDeclarationReportsCharsetDriftEvenWhenTypesMatch() {
        String declared = "NVARCHAR(40)";
        ColumnSpec folded = SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse(declared),
                DatabaseDialect.MYSQL);
        NonDestructiveAlterPlanner.Plan same = NonDestructiveAlterPlanner.plan("t", "c", folded,
                new LiveColumn("VARCHAR", 40, null, false, null));
        assertThat(same.applySql()).isEmpty();
        assertThat(same.pendingSql()).isEmpty();

        var utf8mb4 = mysqlFacts("utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci", "utf8mb4", "", "", "",
                "utf8mb4_0900_ai_ci");
        NonDestructiveAlterPlanner.Plan charsetDrift = SchemaSynchronizer.mySqlNationalDriftPlan("t", "c", declared,
                SchemaSynchronizer.mySqlNationalDrift(utf8mb4), same);
        assertThat(charsetDrift.applySql()).isEmpty();
        assertThat(charsetDrift.pendingSql()).singleElement().asString().contains("utf8mb4");

        var nonDefaultCollation = mysqlFacts("utf8mb3_bin", "utf8mb4_0900_ai_ci", "utf8mb3", "", "", "",
                "utf8mb3_general_ci");
        assertThat(SchemaSynchronizer.mySqlNationalDriftPlan("t", "c", declared,
                SchemaSynchronizer.mySqlNationalDrift(nonDefaultCollation), same).pendingSql())
                .singleElement().asString().contains("utf8mb3_bin");

        var converged = mysqlFacts("utf8mb3_general_ci", "utf8mb4_0900_ai_ci", "utf8mb3", "", "", "",
                "utf8mb3_general_ci");
        assertThat(SchemaSynchronizer.mySqlNationalDrift(converged)).isNull();
        assertThat(SchemaSynchronizer.mySqlNationalDriftPlan("t", "c", declared, null, same)).isSameAs(same);

        assertThat(SchemaSynchronizer.mySqlDeclaresNational("nvarchar(40)")).isTrue();
        assertThat(SchemaSynchronizer.mySqlDeclaresNational("NATIONAL CHARACTER VARYING(40)")).isTrue();
        assertThat(SchemaSynchronizer.mySqlDeclaresNational("NCHAR(2) NOT NULL")).isTrue();
        assertThat(SchemaSynchronizer.mySqlDeclaresNational("VARCHAR(40)")).isFalse();
        assertThat(SchemaSynchronizer.mySqlDeclaresNational("NCHARX(40)")).isFalse();

        // Every spelling treated as national must also fold, or a column SS created never converges.
        for (String spelling : List.of("NVARCHAR(20)", "NATIONAL VARCHAR(20)", "NATIONAL CHARACTER VARYING(20)",
                "NATIONAL CHAR VARYING(20)", "NCHAR VARYING(20)", "NCHAR VARCHAR(20)", "nchar varying(20)")) {
            assertThat(SchemaSynchronizer.mySqlDeclaresNational(spelling)).as(spelling).isTrue();
            ColumnSpec spec = SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse(spelling),
                    DatabaseDialect.MYSQL);
            assertThat(spec.baseType()).as(spelling).isEqualTo("VARCHAR");
            assertThat(NonDestructiveAlterPlanner.plan("t", "c", spec,
                    new LiveColumn("VARCHAR", 20, null, false, null)).pendingSql()).as(spelling).isEmpty();
        }
        for (String spelling : List.of("NCHAR(2)", "NATIONAL CHAR(2)", "NATIONAL CHARACTER(2)")) {
            assertThat(SchemaSynchronizer.mySqlDeclaresNational(spelling)).as(spelling).isTrue();
            assertThat(SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse(spelling),
                    DatabaseDialect.MARIADB).baseType()).as(spelling).isEqualTo("CHAR");
        }
    }

    @Test
    void zonedTemporalFormsParseWithPrecision() {
        record Form(String declared, String type, Integer precision) {}
        for (Form form : List.of(
                new Form("TIMESTAMP(3) WITHOUT TIME ZONE", "TIMESTAMP", 3),
                new Form("timestamp without time zone", "TIMESTAMP", null),
                new Form("TIMESTAMP (3) WITH TIME ZONE", "TIMESTAMPTZ", 3),
                new Form("timestamp(6) with time zone", "TIMESTAMPTZ", 6),
                new Form("TIMESTAMP(9) WITH LOCAL TIME ZONE", "TIMESTAMPLTZ", 9),
                new Form("TIME(3) WITH TIME ZONE", "TIMETZ", 3),
                new Form("TIME WITHOUT TIME ZONE", "TIME", null))) {
            ColumnSpec spec = ColumnDefinitionParser.parse(form.declared() + " NOT NULL");
            assertThat(spec.baseType()).as(form.declared()).isEqualTo(form.type());
            assertThat(spec.length()).as(form.declared()).isEqualTo(form.precision());
            assertThat(spec.notNull()).as(form.declared()).isTrue();
        }
        for (String invalid : List.of("TIMESTAMP WITHOUT LOCAL TIME ZONE", "TIME WITH LOCAL TIME ZONE",
                "TIMESTAMP WITH TIME ZONE EXTRA")) {
            assertThatThrownBy(() -> ColumnDefinitionParser.parse(invalid)).as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        record Limit(DatabaseDialect dialect, String type, int max) {}
        for (Limit limit : List.of(
                new Limit(DatabaseDialect.POSTGRESQL, "TIMESTAMP", 6),
                new Limit(DatabaseDialect.POSTGRESQL, "TIMESTAMPTZ", 6),
                new Limit(DatabaseDialect.POSTGRESQL, "TIME", 6),
                new Limit(DatabaseDialect.MYSQL, "DATETIME", 6),
                new Limit(DatabaseDialect.MARIADB, "TIMESTAMP", 6),
                new Limit(DatabaseDialect.SQLSERVER, "DATETIME2", 7),
                new Limit(DatabaseDialect.SQLSERVER, "DATETIMEOFFSET", 7),
                new Limit(DatabaseDialect.SQLSERVER, "TIME", 7),
                new Limit(DatabaseDialect.ORACLE, "TIMESTAMP", 9))) {
            ColumnSpec atMax = ColumnDefinitionParser.parse(limit.type() + "(" + limit.max() + ")");
            assertThatCode(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(atMax, limit.dialect(), "t.c"))
                    .as(limit.toString()).doesNotThrowAnyException();
            ColumnSpec over = ColumnDefinitionParser.parse(limit.type() + "(" + (limit.max() + 1) + ")");
            assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(over, limit.dialect(),
                    "t.c")).as(limit.toString()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0.." + limit.max());
        }
        assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(
                ColumnDefinitionParser.parse("TIMESTAMP(10) WITH TIME ZONE"), DatabaseDialect.ORACLE, "t.c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(
                ColumnDefinitionParser.parse("TIMESTAMP"), DatabaseDialect.POSTGRESQL, "t.c"))
                .doesNotThrowAnyException();
        for (var unsupported : List.of(Map.entry(DatabaseDialect.SQLSERVER, "DATETIME(3)"),
                Map.entry(DatabaseDialect.ORACLE, "TIME(3)"), Map.entry(DatabaseDialect.MYSQL, "TIMESTAMPTZ(3)"),
                Map.entry(DatabaseDialect.POSTGRESQL, "DATETIME2(3)"))) {
            assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(
                    ColumnDefinitionParser.parse(unsupported.getValue()), unsupported.getKey(), "t.c"))
                    .as(unsupported.toString()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not accept");
        }
        assertThatCode(() -> SchemaSynchronizer.requireSupportedFractionalPrecision(
                ColumnDefinitionParser.parse("DATETIME"), DatabaseDialect.SQLSERVER, "t.c"))
                .doesNotThrowAnyException();
    }

    @Test
    void temporalPrecisionChangesAreNeverSilent() {
        ColumnSpec tz3 = ColumnDefinitionParser.parse("TIMESTAMP(3) WITH TIME ZONE NOT NULL");
        assertThat(tz3.baseType()).isEqualTo("TIMESTAMPTZ");
        assertThat(tz3.length()).isEqualTo(3);
        ColumnSpec ltz = ColumnDefinitionParser.parse("TIMESTAMP(6) WITH LOCAL TIME ZONE NOT NULL");
        assertThat(ltz.baseType()).isEqualTo("TIMESTAMPLTZ");
        assertThat(ColumnDefinitionParser.normalizeType("TIMESTAMP(6) WITH LOCAL TIME ZONE"))
                .isEqualTo("TIMESTAMPLTZ");

        // Oracle live TYPE_NAME carries the precision.
        assertThat(SchemaSynchronizer.liveFractionalPrecision("TIMESTAMP(6) WITH TIME ZONE", "TIMESTAMPTZ", 6,
                DatabaseDialect.ORACLE)).isEqualTo(6);
        assertThat(SchemaSynchronizer.liveFractionalPrecision("TIMESTAMP(3)", "TIMESTAMP", null,
                DatabaseDialect.ORACLE)).isEqualTo(3);
        assertThat(SchemaSynchronizer.liveFractionalPrecision("datetime", "DATETIME", 3,
                DatabaseDialect.SQLSERVER)).isNull();
        assertThat(SchemaSynchronizer.liveFractionalPrecision("datetime2", "DATETIME2", 3,
                DatabaseDialect.SQLSERVER)).isEqualTo(3);

        LiveColumn oracleTz6 = new LiveColumn("TIMESTAMPTZ", 6, null, true, null);
        NonDestructiveAlterPlanner.Plan narrow = NonDestructiveAlterPlanner.plan("t", "c",
                SchemaSynchronizer.withDefaultFractionalPrecision(tz3, DatabaseDialect.ORACLE), oracleTz6);
        assertThat(narrow.applySql()).isEmpty();
        assertThat(narrow.pendingSql()).isNotEmpty();
        NonDestructiveAlterPlanner.Plan widen = NonDestructiveAlterPlanner.plan("t", "c",
                SchemaSynchronizer.withDefaultFractionalPrecision(ColumnDefinitionParser.parse(
                        "TIMESTAMP(9) WITH TIME ZONE NOT NULL"), DatabaseDialect.ORACLE), oracleTz6);
        assertThat(widen.applySql()).isEmpty();
        assertThat(widen.pendingSql()).isNotEmpty();
        NonDestructiveAlterPlanner.Plan bareMatchesDefault = NonDestructiveAlterPlanner.plan("t", "c",
                SchemaSynchronizer.withDefaultFractionalPrecision(ColumnDefinitionParser.parse(
                        "TIMESTAMP WITH TIME ZONE NOT NULL"), DatabaseDialect.ORACLE), oracleTz6);
        assertThat(bareMatchesDefault.applySql()).isEmpty();
        assertThat(bareMatchesDefault.pendingSql()).isEmpty();
        NonDestructiveAlterPlanner.Plan localIsDifferent = NonDestructiveAlterPlanner.plan("t", "c",
                SchemaSynchronizer.withDefaultFractionalPrecision(ltz, DatabaseDialect.ORACLE), oracleTz6);
        assertThat(localIsDifferent.applySql()).isEmpty();
        assertThat(localIsDifferent.pendingSql()).isNotEmpty();

        // Omitted precision means each engine's own default.
        record Cell(DatabaseDialect dialect, String declared, String liveType, int livePrecision, boolean drift) {}
        List<Cell> cells = List.of(
                new Cell(DatabaseDialect.POSTGRESQL, "TIMESTAMP", "TIMESTAMP", 6, false),
                new Cell(DatabaseDialect.POSTGRESQL, "TIMESTAMP", "TIMESTAMP", 3, true),
                new Cell(DatabaseDialect.POSTGRESQL, "TIMESTAMPTZ(3)", "TIMESTAMPTZ", 3, false),
                new Cell(DatabaseDialect.POSTGRESQL, "TIME", "TIME", 0, true),
                new Cell(DatabaseDialect.MYSQL, "DATETIME", "DATETIME", 0, false),
                new Cell(DatabaseDialect.MYSQL, "DATETIME", "DATETIME", 3, true),
                new Cell(DatabaseDialect.MARIADB, "TIMESTAMP(6)", "TIMESTAMP", 6, false),
                new Cell(DatabaseDialect.MARIADB, "TIME(3)", "TIME", 0, true),
                new Cell(DatabaseDialect.SQLSERVER, "DATETIME2", "DATETIME2", 7, false),
                new Cell(DatabaseDialect.SQLSERVER, "DATETIME2(3)", "DATETIME2", 7, true),
                new Cell(DatabaseDialect.SQLSERVER, "DATETIMEOFFSET", "DATETIMEOFFSET", 3, true),
                new Cell(DatabaseDialect.ORACLE, "TIMESTAMP", "TIMESTAMP", 6, false),
                new Cell(DatabaseDialect.ORACLE, "TIMESTAMP(0)", "TIMESTAMP", 6, true));
        for (Cell cell : cells) {
            ColumnSpec target = SchemaSynchronizer.withDefaultFractionalPrecision(
                    ColumnDefinitionParser.parse(cell.declared()), cell.dialect());
            NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("t", "c", target,
                    new LiveColumn(cell.liveType(), cell.livePrecision(), null, false, null));
            assertThat(plan.applySql()).as(cell.toString()).isEmpty();
            assertThat(plan.pendingSql().isEmpty()).as(cell.toString()).isEqualTo(!cell.drift());
        }

        // SQL Server legacy DATETIME has no precision argument; never compared.
        ColumnSpec legacy = SchemaSynchronizer.withDefaultFractionalPrecision(
                ColumnDefinitionParser.parse("DATETIME"), DatabaseDialect.SQLSERVER);
        assertThat(legacy.length()).isNull();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", legacy,
                new LiveColumn("DATETIME", null, null, false, null)).pendingSql()).isEmpty();

        // PostgreSQL snapshots keep a non-default precision so replay converges.
        assertThat(SchemaSnapshotWriter.columnType("TIMESTAMP", 29, 3, DatabaseDialect.POSTGRESQL))
                .isEqualTo("TIMESTAMP(3)");
        assertThat(SchemaSnapshotWriter.columnType("TIMESTAMPTZ", 35, 0, DatabaseDialect.POSTGRESQL))
                .isEqualTo("TIMESTAMPTZ(0)");
        assertThat(SchemaSnapshotWriter.columnType("TIME", 15, 6, DatabaseDialect.POSTGRESQL)).isEqualTo("TIME");
        assertThat(ColumnDefinitionParser.parse("TIMESTAMPTZ(0)").length()).isEqualTo(0);
    }

    @Test
    void declarationsThatEnginesStoreDifferentlyConvergeOnSecondSync() {
        String onUpdate = "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
        assertThat(ColumnDefinitionParser.parse(onUpdate).defaultExpr()).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse(onUpdate),
                new LiveColumn("TIMESTAMP", null, null, true, "CURRENT_TIMESTAMP")).applySql()).isEmpty();
        assertThat(ColumnDefinitionParser.parse("VARCHAR(20) DEFAULT 'on update x'").defaultExpr())
                .isEqualTo("'on update x'");

        ColumnSpec pgNational = SchemaSynchronizer.foldNationalType(
                ColumnDefinitionParser.parse("NATIONAL CHARACTER VARYING(40)"), DatabaseDialect.POSTGRESQL);
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", pgNational,
                new LiveColumn("VARCHAR", 40, null, false, null)).pendingSql()).isEmpty();
        ColumnSpec pgNchar = SchemaSynchronizer.foldNationalType(
                ColumnDefinitionParser.parse("NCHAR(3)"), DatabaseDialect.POSTGRESQL);
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", pgNchar,
                new LiveColumn("CHAR", 3, null, false, null)).pendingSql()).isEmpty();

        for (String bare : List.of("CHAR", "NCHAR", "BINARY")) {
            ColumnSpec spec = ColumnDefinitionParser.parse(bare);
            assertThat(spec.length()).as(bare).isEqualTo(1);
            NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("t", "c", spec,
                    new LiveColumn(spec.baseType(), 1, null, false, null));
            assertThat(plan.applySql()).as(bare).isEmpty();
            assertThat(plan.pendingSql()).as(bare).isEmpty();
        }
    }

    @Test
    void primaryKeyColumnsAcceptSqlServerClusteredForms() {
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (id BIGINT NOT NULL, CONSTRAINT pk_t PRIMARY KEY CLUSTERED (id))",
                DatabaseDialect.SQLSERVER)).containsExactly("id");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (a INT NOT NULL, b INT NOT NULL, CONSTRAINT pk_t PRIMARY KEY NONCLUSTERED (a ASC, b))",
                DatabaseDialect.SQLSERVER)).containsExactly("a", "b");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (id BIGINT NOT NULL PRIMARY KEY, label VARCHAR(10))", DatabaseDialect.SQLSERVER))
                .containsExactly("id");
    }

    @Test
    void primaryKeyTextInsideLiteralsIsNotAKeyClause() {
        // Snapshot createSql carries column COMMENTs; their text must not be read as a key.
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id BIGINT NOT NULL COMMENT "
                + "'surrogate primary key (auto)', PRIMARY KEY (id))", DatabaseDialect.MYSQL)).containsExactly("id");
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (note VARCHAR(20) DEFAULT NULL COMMENT "
                + "'not a primary key')", DatabaseDialect.MYSQL)).isEmpty();
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL, note VARCHAR(20) COMMENT "
                + "'primary key (see docs)')", DatabaseDialect.MARIADB)).isEmpty();
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (v VARCHAR(20) DEFAULT 'a PRIMARY KEY', "
                + "id INT PRIMARY KEY)", DatabaseDialect.POSTGRESQL)).containsExactly("id");
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL, -- primary key (x)\n"
                + " PRIMARY KEY (id))", DatabaseDialect.ORACLE)).containsExactly("id");
        // Without ANSI_QUOTES "…" is a string on MySQL; a key that reads differently either way is rejected.
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL COMMENT \"a note\", "
                + "PRIMARY KEY (id))", DatabaseDialect.MYSQL)).containsExactly("id");
        assertThatThrownBy(() -> SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL, note "
                + "VARCHAR(20) COMMENT \"primary key (note)\")", DatabaseDialect.MYSQL))
                .hasMessageContaining("ANSI_QUOTES");
        assertThatThrownBy(() -> SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL, "
                + "PRIMARY KEY (\"id\"))", DatabaseDialect.MARIADB)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL COMMENT 'primary key (x)', "
                + "PRIMARY KEY (id))", DatabaseDialect.MARIADB)).containsExactly("id");
        // Other engines read "…" only as an identifier, so double-quoted comment text is still a key clause there.
        assertThat(SchemaSynchronizer.primaryKeyColumns("CREATE TABLE t (id INT NOT NULL, note VARCHAR(20) "
                + "DEFAULT 'primary key (note)', PRIMARY KEY (id))", DatabaseDialect.POSTGRESQL)).containsExactly("id");
    }

    @Test
    void unsignedDecimalComparesPrecisionAndScale() {
        String unsigned = "NUMERIC UNSIGNED";
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange(unsigned, 10, 4, unsigned, 10, 2))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.NARROW);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange(unsigned, 10, 2, unsigned, 5, 2))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.NARROW);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange(unsigned, 5, 2, unsigned, 10, 2))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.WIDEN);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange(unsigned, 5, 2, unsigned, 5, 2))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.SAME);
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange(unsigned, 5, 2, "NUMERIC", 5, 2))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.INCOMPATIBLE);
        assertThat(NonDestructiveAlterPlanner.formatType(unsigned, 5, 2)).isEqualTo("NUMERIC(5,2) UNSIGNED");
        assertThat(NonDestructiveAlterPlanner.formatType("NUMERIC", 5, 2)).isEqualTo("NUMERIC(5,2)");
        // A narrowing type change is pending, so dropping NOT NULL cannot MODIFY the column to a smaller type.
        NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("t", "amount",
                new ColumnSpec(unsigned, 10, 2, false, null), new LiveColumn(unsigned, 10, 4, true, null));
        assertThat(plan.pendingSql()).singleElement().asString().contains("NUMERIC(10,2) UNSIGNED");

        // A bare exact numeric compares as the precision the engine creates.
        for (DatabaseDialect dialect : List.of(DatabaseDialect.MYSQL, DatabaseDialect.MARIADB)) {
            assertThat(bareNumeric("DECIMAL", dialect)).isEqualTo(new ColumnSpec("NUMERIC", 10, 0, false, null));
            assertThat(bareNumeric("DECIMAL UNSIGNED DEFAULT 1", dialect))
                    .isEqualTo(new ColumnSpec(unsigned, 10, 0, false, "1"));
            assertThat(bareNumeric("fixed", dialect)).isEqualTo(new ColumnSpec("NUMERIC", 10, 0, false, null));
        }
        for (String bare : List.of("DECIMAL", "NUMERIC", "dec NOT NULL")) {
            assertThat(bareNumeric(bare, DatabaseDialect.SQLSERVER).length()).as(bare).isEqualTo(18);
            assertThat(bareNumeric(bare, DatabaseDialect.ORACLE).length()).as(bare).isEqualTo(38);
            assertThat(bareNumeric(bare, DatabaseDialect.POSTGRESQL).length()).as(bare).isNull();
        }
        // Oracle NUMBER without precision is a floating decimal, not NUMBER(38,0); other engines
        // have no NUMBER (validation rejects it) and FIXED only exists on MySQL/MariaDB.
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            assertThat(bareNumeric("NUMBER", dialect)).as(dialect.id())
                    .isEqualTo(new ColumnSpec("NUMERIC", null, null, false, null));
        }
        assertThat(bareNumeric("FIXED", DatabaseDialect.SQLSERVER).length()).isNull();
        assertThat(bareNumeric("DECIMALX", DatabaseDialect.ORACLE)).isEqualTo(ColumnDefinitionParser.parse("DECIMALX"));
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            assertThat(bareNumeric("DECIMAL(12,4)", dialect)).isEqualTo(new ColumnSpec("NUMERIC", 12, 4, false, null));
            assertThat(bareNumeric("INT", dialect)).isEqualTo(ColumnDefinitionParser.parse("INT"));
        }
        // Against a wider live column the bare form is a narrowing, never an automatic widening.
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", bareNumeric("DEC", DatabaseDialect.SQLSERVER),
                new LiveColumn("NUMERIC", 20, 4, false, null)).applySql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", bareNumeric("DEC", DatabaseDialect.ORACLE),
                new LiveColumn("NUMERIC", 20, 4, false, null)).applySql()).isEmpty();
        // Oracle bare DECIMAL is NUMBER(38,0): against a live unbounded NUMBER it is pending, not applied.
        NonDestructiveAlterPlanner.Plan unbounded = NonDestructiveAlterPlanner.plan("t", "c",
                bareNumeric("DECIMAL", DatabaseDialect.ORACLE), new LiveColumn("NUMERIC", null, null, false, null));
        assertThat(unbounded.applySql()).isEmpty();
        assertThat(unbounded.pendingSql()).isNotEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", bareNumeric("DEC", DatabaseDialect.SQLSERVER),
                new LiveColumn("NUMERIC", 18, 0, false, null)).applySql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", bareNumeric("DEC", DatabaseDialect.SQLSERVER),
                new LiveColumn("NUMERIC", 18, 0, false, null)).pendingSql()).isEmpty();
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("NUMERIC", 12, 4, "NUMERIC", 10, 0))
                .isEqualTo(NonDestructiveAlterPlanner.TypeChange.NARROW);

        // UNSIGNED defaults are stored exactly like the signed type's.
        assertThat(SchemaSynchronizer.mySqlStoredAsDeclared(new ColumnSpec("INTEGER UNSIGNED", null, null, false, "5")))
                .isTrue();
        assertThat(SchemaSynchronizer.mySqlStoredAsDeclared(new ColumnSpec(unsigned, 5, 2, false, "1.5"))).isTrue();
        assertThat(SchemaSynchronizer.mySqlStoredAsDeclared(new ColumnSpec(unsigned, 5, 2, false, "1.555"))).isFalse();
        assertThat(SchemaSynchronizer.mySqlStoredAsDeclared(new ColumnSpec("INTEGER UNSIGNED", null, null, false, "1.5")))
                .isFalse();
        assertThat(SchemaSynchronizer.mySqlStoredAsDeclared(
                new ColumnSpec("INTEGER UNSIGNED ZEROFILL", null, null, false, "5"))).isFalse();
    }

    @Test
    void metadataPatternsMatchOnlyTheExactName() throws Exception {
        assertThat(DatabaseDialect.matchesLiterally("app_db", "app_db")).isTrue();
        assertThat(DatabaseDialect.matchesLiterally("app_db", "app1db")).isFalse();
        assertThat(DatabaseDialect.matchesLiterally("app_db", "app_dbx")).isFalse();
        assertThat(DatabaseDialect.matchesLiterally("a%b", "a%b")).isTrue();
        assertThat(DatabaseDialect.matchesLiterally("a%b", "axxb")).isFalse();
        assertThat(DatabaseDialect.matchesLiterally("a%b", "ab")).isFalse();
        assertThat(DatabaseDialect.matchesLiterally("app_db", null)).isFalse();
        // Characters other than wildcards are compared by the server (case-insensitive on some).
        assertThat(DatabaseDialect.matchesLiterally("APP_DB", "app_db")).isTrue();

        // Schema from TABLE_SCHEM; MySQL-family drivers in catalog mode report it in TABLE_CAT.
        assertThat(DatabaseDialect.isRequestedObject(row("app_db", "def", "t_1"), "app_db", "t_1")).isTrue();
        assertThat(DatabaseDialect.isRequestedObject(row("app1db", null, "t_1"), "app_db", "t_1")).isFalse();
        assertThat(DatabaseDialect.isRequestedObject(row("app_db", null, "tx1"), "app_db", "t_1")).isFalse();
        assertThat(DatabaseDialect.isRequestedObject(row(null, "app_db", "t_1"), "app_db", "t_1")).isTrue();
        assertThat(DatabaseDialect.isRequestedObject(row(null, "app1db", "t_1"), "app_db", "t_1")).isFalse();
        assertThat(DatabaseDialect.isRequestedObject(row("app1db", null, "t_1"), "app_db", null)).isFalse();
        assertThat(DatabaseDialect.isRequestedObject(row("app1db", null, "tx1"), null, null)).isTrue();
    }

    private static ColumnSpec bareNumeric(String definition, DatabaseDialect dialect) {
        return SchemaSynchronizer.withDefaultNumericPrecision(ColumnDefinitionParser.parse(definition), definition, dialect);
    }

    private static java.sql.ResultSet row(String schema, String catalog, String table) throws Exception {
        java.sql.ResultSet row = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(row.getString("TABLE_SCHEM")).thenReturn(schema);
        org.mockito.Mockito.when(row.getString("TABLE_CAT")).thenReturn(catalog);
        org.mockito.Mockito.when(row.getString("TABLE_NAME")).thenReturn(table);
        return row;
    }

    @Test
    void unsignedZerofillDecimalKeepsPrecision() {
        assertThat(ColumnDefinitionParser.parse("DECIMAL(8, 2) UNSIGNED ZEROFILL DEFAULT 1.5"))
                .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED ZEROFILL", 8, 2, false, "1.5"));
        assertThat(ColumnDefinitionParser.parse("fixed(8) unsigned zerofill"))
                .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED ZEROFILL", 8, null, false, null));
        // An integer display width with ZEROFILL is not compared, so it cannot be declared.
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("INT(5) UNSIGNED ZEROFILL"))
                .hasMessageContaining("unparseable");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("DOUBLE(8,2) UNSIGNED ZEROFILL"))
                .hasMessageContaining("unparseable");
        for (DatabaseDialect dialect : List.of(DatabaseDialect.MYSQL, DatabaseDialect.MARIADB)) {
            String written = SchemaSnapshotWriter.columnType("DECIMAL UNSIGNED ZEROFILL", 8, 2, dialect);
            assertThat(written).isEqualTo("NUMERIC(8,2) UNSIGNED ZEROFILL");
            assertThat(ColumnDefinitionParser.parse(written))
                    .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED ZEROFILL", 8, 2, false, null));
            assertThat(SchemaSnapshotWriter.columnType("INT UNSIGNED ZEROFILL", 10, 0, dialect))
                    .isEqualTo("INT UNSIGNED ZEROFILL");
        }
        assertThat(NonDestructiveAlterPlanner.classifyTypeChange("NUMERIC UNSIGNED ZEROFILL", 8, 2,
                "NUMERIC UNSIGNED ZEROFILL", 6, 2)).isEqualTo(NonDestructiveAlterPlanner.TypeChange.NARROW);
        assertThat(NonDestructiveAlterPlanner.formatType("NUMERIC UNSIGNED ZEROFILL", 8, 2))
                .isEqualTo("NUMERIC(8,2) UNSIGNED ZEROFILL");

        // MySQL Connector/J drops ZEROFILL from TYPE_NAME; COLUMN_TYPE restores it.
        assertThat(SchemaSnapshotWriter.mysqlZerofill("DECIMAL UNSIGNED", "decimal(8,2) unsigned zerofill"))
                .isEqualTo("DECIMAL UNSIGNED ZEROFILL");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("INT UNSIGNED", "int(5) unsigned zerofill"))
                .isEqualTo("INT UNSIGNED ZEROFILL");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("int unsigned", "int unsigned zerofill"))
                .isEqualTo("int unsigned ZEROFILL");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("DECIMAL UNSIGNED ZEROFILL", "decimal(8,2) unsigned zerofill"))
                .isEqualTo("DECIMAL UNSIGNED ZEROFILL");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("DECIMAL UNSIGNED", "decimal(8,2) unsigned"))
                .isEqualTo("DECIMAL UNSIGNED");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("ENUM", "enum('unsigned zerofill')")).isEqualTo("ENUM");
        assertThat(SchemaSnapshotWriter.mysqlZerofill("DECIMAL UNSIGNED", null)).isEqualTo("DECIMAL UNSIGNED");
    }

    @Test
    void decAndFixedAreDecimalSynonyms() {
        assertThat(ColumnDefinitionParser.parse("DEC(8,2)")).isEqualTo(new ColumnSpec("NUMERIC", 8, 2, false, null));
        assertThat(ColumnDefinitionParser.parse("fixed(8,2)")).isEqualTo(new ColumnSpec("NUMERIC", 8, 2, false, null));
        assertThat(ColumnDefinitionParser.parse("DEC(8,2) UNSIGNED"))
                .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED", 8, 2, false, null));
    }

    @Test
    void mySqlAttributeSpellingsNormalizeOrFailParsing() {
        // ZEROFILL implies UNSIGNED, in either order (MySQL 8.4 and MariaDB 10.3 store both as UNSIGNED ZEROFILL).
        for (String declaration : List.of("INT ZEROFILL", "int zerofill unsigned", "INT UNSIGNED ZEROFILL")) {
            assertThat(ColumnDefinitionParser.parse(declaration).baseType()).as(declaration)
                    .isEqualTo("INTEGER UNSIGNED ZEROFILL");
        }
        assertThat(ColumnDefinitionParser.normalizeType("INT ZEROFILL")).isEqualTo("INTEGER UNSIGNED ZEROFILL");
        for (String declaration : List.of("DECIMAL(8,2) ZEROFILL", "decimal(8,2) zerofill unsigned")) {
            assertThat(ColumnDefinitionParser.parse(declaration)).as(declaration)
                    .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED ZEROFILL", 8, 2, false, null));
        }
        // MySQL rejects an attribute before the length.
        for (String declaration : List.of("INT UNSIGNED(10)", "DECIMAL UNSIGNED(5,2)", "INT ZEROFILL(5)",
                "DECIMAL UNSIGNED ZEROFILL(5,2)")) {
            assertThatThrownBy(() -> ColumnDefinitionParser.parse(declaration)).as(declaration)
                    .hasMessageContaining("unparseable");
        }
    }

    @Test
    void mySqlZerofillDisplayWidthIsNotDeclarable() {
        for (String columnType : List.of("tinyint(3) unsigned zerofill", "smallint(5) unsigned zerofill",
                "mediumint(8) unsigned zerofill", "int(10) unsigned zerofill", "bigint(20) unsigned zerofill",
                "decimal(8,2) unsigned zerofill", "int(5) unsigned", "int unsigned zerofill")) {
            assertThat(SchemaSnapshotWriter.mysqlZerofillCustomWidth(columnType)).as(columnType).isNull();
        }
        assertThat(SchemaSnapshotWriter.mysqlZerofillCustomWidth("int(5) unsigned zerofill")).isEqualTo(5);
        assertThat(SchemaSnapshotWriter.mysqlZerofillCustomWidth("tinyint(1) unsigned zerofill")).isEqualTo(1);
        assertThat(SchemaSnapshotWriter.mysqlZerofillCustomWidth(null)).isNull();
    }

    @Test
    void unsignedAndZerofillAreRejectedOutsideMySqlFamily() {
        List<String> declarations = List.of("INT UNSIGNED", "DECIMAL(8,2) UNSIGNED", "DECIMAL(8,2) UNSIGNED ZEROFILL",
                "INT UNSIGNED ZEROFILL", "INT ZEROFILL", "FIXED(8,2)", "fixed");
        for (String declaration : declarations) {
            ColumnSpec spec = ColumnDefinitionParser.parse(declaration);
            for (DatabaseDialect dialect : DatabaseDialect.values()) {
                if (dialect.isMySqlFamily()) {
                    assertThatCode(() -> SchemaSynchronizer.requireMySqlOnlyAttributes(spec, declaration, dialect, "t.c"))
                            .as(dialect + " " + declaration).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> SchemaSynchronizer.requireMySqlOnlyAttributes(spec, declaration, dialect, "t.c"))
                            .as(dialect + " " + declaration).hasMessageContaining("MySQL/MariaDB");
                }
            }
        }
        // NUMBER compares as NUMERIC, so outside Oracle it would reach ALTER COLUMN c NUMBER.
        for (String declaration : List.of("NUMBER", "number(10,2) NOT NULL")) {
            ColumnSpec spec = ColumnDefinitionParser.parse(declaration);
            for (DatabaseDialect dialect : DatabaseDialect.values()) {
                if (dialect == DatabaseDialect.ORACLE) {
                    assertThatCode(() -> SchemaSynchronizer.requireMySqlOnlyAttributes(spec, declaration, dialect, "t.c"))
                            .as(dialect + " " + declaration).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> SchemaSynchronizer.requireMySqlOnlyAttributes(spec, declaration, dialect, "t.c"))
                            .as(dialect + " " + declaration).hasMessageContaining("Oracle type");
                }
            }
        }
        // Validation runs it for every declared column.
        for (DatabaseDialect dialect : List.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.SQLSERVER)) {
            String schema = dialect == DatabaseDialect.SQLSERVER ? "dbo" : "public";
            for (String declaration : List.of("NUMBER", "INT UNSIGNED", "FIXED(8,2)")) {
                assertThatThrownBy(() -> validate(new SchemaDefinition(Map.of("items", new SchemaDefinition.TableDef(
                        "CREATE TABLE items (id INT NOT NULL, PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "INT NOT NULL"),
                                new SchemaDefinition.ColumnDef("amount", declaration)), List.of()))), dialect, schema))
                        .as(dialect + " " + declaration).hasMessageContaining("items.amount");
            }
        }
        for (String declaration : List.of("INT", "NUMERIC(8,2)", "DEC(8,2)", "VARCHAR(10) DEFAULT 'unsigned'",
                "FIXEDX(3)", "NUMBERS(3)")) {
            ColumnSpec spec = ColumnDefinitionParser.parse(declaration);
            for (DatabaseDialect dialect : DatabaseDialect.values()) {
                assertThatCode(() -> SchemaSynchronizer.requireMySqlOnlyAttributes(spec, declaration, dialect, "t.c"))
                        .as(dialect + " " + declaration).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void tinyint1UnsignedKeepsItsDisplayWidth() {
        assertThat(ColumnDefinitionParser.parse("TINYINT(1) UNSIGNED DEFAULT 0"))
                .extracting(ColumnSpec::baseType, ColumnSpec::length, ColumnSpec::defaultExpr)
                .containsExactly("TINYINT UNSIGNED", 1, "0");
        assertThat(ColumnDefinitionParser.parse("tinyint(1) unsigned").baseType()).isEqualTo("TINYINT UNSIGNED");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("TINYINT(1) UNSIGNED ZEROFILL"))
                .hasMessageContaining("unparseable");
        assertThat(ColumnDefinitionParser.parse("INT(10) UNSIGNED NOT NULL"))
                .extracting(ColumnSpec::baseType, ColumnSpec::length, ColumnSpec::notNull)
                .containsExactly("INTEGER UNSIGNED", 10, true);
        // The base of an UNSIGNED type normalizes like the signed type, on both the declared and live side.
        assertThat(ColumnDefinitionParser.normalizeType("int unsigned")).isEqualTo("INTEGER UNSIGNED");
        assertThat(ColumnDefinitionParser.normalizeType("DECIMAL UNSIGNED")).isEqualTo("NUMERIC UNSIGNED");
        assertThat(ColumnDefinitionParser.normalizeType("INT UNSIGNED ZEROFILL")).isEqualTo("INTEGER UNSIGNED ZEROFILL");
        assertThat(ColumnDefinitionParser.parse("DECIMAL(5, 2) UNSIGNED DEFAULT 1.5"))
                .isEqualTo(new ColumnSpec("NUMERIC UNSIGNED", 5, 2, false, "1.5"));
        assertThat(ColumnDefinitionParser.parse(SchemaSnapshotWriter.columnType("DECIMAL UNSIGNED", 5, 2,
                DatabaseDialect.MYSQL))).isEqualTo(new ColumnSpec("NUMERIC UNSIGNED", 5, 2, false, null));
        assertThat(SchemaSnapshotWriter.columnType("INT UNSIGNED", 10, 0, DatabaseDialect.MARIADB)).isEqualTo("INT UNSIGNED");
        assertThat(SchemaSnapshotWriter.columnType("TINYINT(1) UNSIGNED", 3, 0, DatabaseDialect.MARIADB))
                .isEqualTo("TINYINT(1) UNSIGNED");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("DOUBLE(5,2) UNSIGNED"))
                .hasMessageContaining("scale is supported only for NUMERIC");
        assertThatThrownBy(() -> ColumnDefinitionParser.parse("VARCHAR(10) UNSIGNED"))
                .hasMessageContaining("unparseable");

        assertThat(SchemaSnapshotWriter.mysqlUnsignedTinyint1("TINYINT UNSIGNED", "tinyint(1) unsigned"))
                .isEqualTo("TINYINT(1) UNSIGNED");
        assertThat(SchemaSnapshotWriter.mysqlUnsignedTinyint1("TINYINT UNSIGNED", "tinyint(1) unsigned zerofill"))
                .isEqualTo("TINYINT UNSIGNED");
        assertThat(SchemaSnapshotWriter.mysqlUnsignedTinyint1("TINYINT UNSIGNED", "tinyint(3) unsigned"))
                .isEqualTo("TINYINT UNSIGNED");
        assertThat(SchemaSnapshotWriter.mysqlUnsignedTinyint1("BOOLEAN", "tinyint(1)")).isEqualTo("BOOLEAN");
        assertThat(SchemaSnapshotWriter.mysqlUnsignedTinyint1("TINYINT UNSIGNED", "tinyint unsigned"))
                .isEqualTo("TINYINT UNSIGNED");
        assertThat(ColumnDefinitionParser.parse(SchemaSnapshotWriter.mysqlUnsignedTinyint1(
                "TINYINT UNSIGNED", "tinyint(1) unsigned") + " DEFAULT 1").baseType()).isEqualTo("TINYINT UNSIGNED");
    }

    @Test
    void outOfRangeDoubleExponentsCompareByValue() {
        String dbl = ColumnDefinitionParser.normalizeType("DOUBLE");
        // MySQL 8.4 and MariaDB 10.3/11.4 report DEFAULT 10E299 as 1e300 (verified live).
        assertThat(SchemaSynchronizer.mySqlComparableDefault("10E299", dbl))
                .isEqualTo(SchemaSynchronizer.mySqlComparableDefault("1e300", dbl));
        assertThat(SchemaSynchronizer.mySqlComparableDefault("1.50E-300", dbl))
                .isEqualTo(SchemaSynchronizer.mySqlComparableDefault("1.5e-300", dbl));
        assertThat(SchemaSynchronizer.mySqlComparableDefault("1E300", dbl))
                .isNotEqualTo(SchemaSynchronizer.mySqlComparableDefault("2e300", dbl));
        assertThat(SchemaSynchronizer.mySqlComparableDefault("1.50", dbl)).isEqualTo("1.5");
        for (String unsigned : List.of("TINYINT UNSIGNED", "INTEGER UNSIGNED", "BIGINT UNSIGNED ZEROFILL",
                ColumnDefinitionParser.parse("INT UNSIGNED").baseType(), ColumnDefinitionParser.parse("DOUBLE UNSIGNED").baseType())) {
            assertThat(SchemaSynchronizer.mySqlComparableDefault("1.0", unsigned)).as(unsigned).isEqualTo("1");
            assertThat(SchemaSynchronizer.mySqlComparableDefault("'2'", unsigned)).as(unsigned).isEqualTo("2");
        }
    }

    @Test
    void equivalentDefaultsNormalizeTogether() {
        assertThat(ColumnDefinitionParser.normalizeDefault("(getdate())"))
                .isEqualTo(ColumnDefinitionParser.normalizeDefault("CURRENT_TIMESTAMP"));
        assertThat(ColumnDefinitionParser.normalizeDefault("now()")).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(ColumnDefinitionParser.normalizeDefault("NULL")).isNull();
        assertThat(ColumnDefinitionParser.normalizeDefault("'NULL'")).isEqualTo("'NULL'");
        assertThat(ColumnDefinitionParser.normalizeDefault("sysdatetime()")).isNotEqualTo("CURRENT_TIMESTAMP");
    }

    @Test
    void oracleTrailingSemicolonIsStrippedAroundComments() {
        DatabaseDialect oracle = DatabaseDialect.ORACLE;
        assertThat(oracle.executableSql("ALTER TABLE t ADD (c INT); -- add c"))
                .isEqualTo("ALTER TABLE t ADD (c INT)");
        assertThat(oracle.executableSql("ALTER TABLE t ADD (c INT) /* x; */"))
                .isEqualTo("ALTER TABLE t ADD (c INT) /* x; */");
        assertThat(oracle.executableSql("UPDATE t SET note = 'a;'"))
                .isEqualTo("UPDATE t SET note = 'a;'");
        String trigger = "-- audit\nCREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW BEGIN NULL; END;";
        assertThat(oracle.executableSql(trigger)).isEqualTo(trigger);
    }

    @Test
    void mysqlNationalTypesCompareAsTheirStoredType() {
        ColumnSpec folded = SchemaSynchronizer.foldNationalType(
                ColumnDefinitionParser.parse("NVARCHAR(40)"), DatabaseDialect.MYSQL);
        assertThat(folded.baseType()).isEqualTo("VARCHAR");
        assertThat(NonDestructiveAlterPlanner.plan("items", "label", folded,
                new LiveColumn("VARCHAR", 40, null, false, null)).pendingSql()).isEmpty();
        assertThat(SchemaSynchronizer.foldNationalType(ColumnDefinitionParser.parse("NVARCHAR(40)"),
                DatabaseDialect.SQLSERVER).baseType()).isEqualTo("NVARCHAR");
    }

    @Test
    void mysqlColumnRewriteKeepsPlannerOperations() {
        NonDestructiveAlterPlanner.Plan relax = NonDestructiveAlterPlanner.plan("items", "label",
                ColumnDefinitionParser.parse("VARCHAR(40)"), new LiveColumn("VARCHAR", 40, null, true, null));
        NonDestructiveAlterPlanner.Plan rewritten =
                SchemaSynchronizer.mySqlFamilyColumnPlan("items", "label", "VARCHAR(40)", relax);
        assertThat(rewritten.applyOps()).contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
    }
}
