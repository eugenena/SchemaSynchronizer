// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

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
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("uuid()", "VARCHAR", true)).isEqualTo("uuid()");
        assertThat(SchemaSnapshotWriter.mysqlLiteralDefault("CURRENT_TIMESTAMP", "TIMESTAMP", false))
                .isEqualTo("CURRENT_TIMESTAMP");
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

    @Test
    void mysqlModifyColumnIsPendingWhenItWouldResetUndeclaredAttributes() {
        var plain = new SchemaSynchronizer.MySqlColumnFacts(true, "utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci",
                "utf8mb4", "", "", "");
        assertThat(SchemaSynchronizer.mySqlBlockReason(plain, "VARCHAR(100)")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true,
                "utf8mb4_bin", "latin1_swedish_ci", "utf8mb4", "", "", ""), "VARCHAR(100)")).contains("collation");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true,
                "utf8mb4_bin", "latin1_swedish_ci", "utf8mb4", "", "", ""),
                "VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null,
                null, "DEFAULT_GENERATED on update CURRENT_TIMESTAMP", "", ""), "TIMESTAMP NULL"))
                .contains("ON UPDATE");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null,
                null, "DEFAULT_GENERATED", "", ""), "TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP")).isNull();
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null,
                null, "auto_increment", "", ""), "BIGINT")).contains("AUTO_INCREMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true,
                "utf8mb4_0900_ai_ci", "utf8mb4_0900_ai_ci", "utf8mb4", "", "note", ""), "VARCHAR(100)"))
                .contains("COMMENT");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true, null, null,
                null, "VIRTUAL GENERATED", "", "`a` + 1"), "INT")).contains("generated");
        assertThat(SchemaSynchronizer.mySqlBlockReason(plain, "NVARCHAR(100)")).contains("utf8mb3");
        assertThat(SchemaSynchronizer.mySqlBlockReason(new SchemaSynchronizer.MySqlColumnFacts(true,
                "utf8mb3_general_ci", "utf8mb4_0900_ai_ci", "utf8mb3", "", "", ""), "NVARCHAR(100)")).isNull();

        NonDestructiveAlterPlanner.Plan widen = NonDestructiveAlterPlanner.plan("items", "label",
                ColumnDefinitionParser.parse("VARCHAR(100)"), new LiveColumn("VARCHAR", 40, null, false, null));
        NonDestructiveAlterPlanner.Plan blocked = SchemaSynchronizer.mySqlFamilyColumnPlan("items", "label",
                "VARCHAR(100)", widen, "column collation x differs");
        assertThat(blocked.applySql()).isEmpty();
        assertThat(blocked.pendingSql()).singleElement().asString().contains("-- pending: column collation x");
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
