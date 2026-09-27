// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The identifier model of {@link SqlIdentifiers}, cell by cell per dialect:
 * <pre>
 * backend     quoted  declared name stored as   names compare
 * PostgreSQL  "x"     lower                     case-sensitively
 * Oracle      "X"     UPPER                     case-sensitively
 * MySQL/Maria `x`     lower                     tables by lower_case_table_names; columns/indexes insensitively
 * SQL Server  [x]     lower                     by the database collation
 * </pre>
 */
class IdentifierQuotingContractTest {

    /** Reserved (or keyword-like) on at least one backend. */
    private static final List<String> RESERVED = List.of("order", "user", "group", "end", "desc", "default",
            "select", "key", "rank", "interval", "table", "column", "each");

    private static String schemaFor(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "public";
            case SQLSERVER -> "dbo";
            case MYSQL, MARIADB, ORACLE -> "app";
        };
    }

    private static String q(DatabaseDialect dialect, String declared) {
        return switch (dialect) {
            case POSTGRESQL -> "\"" + declared + "\"";
            case ORACLE -> "\"" + declared.toUpperCase(java.util.Locale.ROOT) + "\"";
            case MYSQL, MARIADB -> "`" + declared + "`";
            case SQLSERVER -> "[" + declared + "]";
        };
    }

    private static String bigint(DatabaseDialect dialect) {
        return dialect == DatabaseDialect.ORACLE ? "NUMBER(19)" : "BIGINT";
    }

    private static String varchar(DatabaseDialect dialect) {
        return dialect == DatabaseDialect.ORACLE ? "VARCHAR2(50)" : "VARCHAR(50)";
    }

    private static SchemaSynchronizerOptions options(DatabaseDialect dialect) {
        return new SchemaSynchronizerOptions(schemaFor(dialect), "schema_synchronizer_history", 1L,
                false, true, true);
    }

    // ---------------------------------------------------------------- quoting

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void everyDeclaredNameIsQuotedInTheDialectStyleAsItsStoredForm(DatabaseDialect dialect) {
        for (String word : RESERVED) {
            assertThat(SqlIdentifiers.quote(dialect, word)).as(word).isEqualTo(q(dialect, word));
            assertThat(SqlIdentifiers.quote(dialect, word.toUpperCase(java.util.Locale.ROOT)))
                    .as("declared names fold, so %s and %s are one name", word, word.toUpperCase(java.util.Locale.ROOT))
                    .isEqualTo(q(dialect, word));
        }
    }

    @Test
    void quoteExactEscapesTheClosingQuoteAndRejectsUnrepresentableNames() {
        assertThat(SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, "a\"b")).isEqualTo("\"a\"\"b\"");
        // Oracle has no escape for a double quote inside a quoted identifier.
        assertThatThrownBy(() -> SqlIdentifiers.quoteExact(DatabaseDialect.ORACLE, "a\"b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Oracle");
        assertThat(SqlIdentifiers.quoteExact(DatabaseDialect.MYSQL, "a`b")).isEqualTo("`a``b`");
        assertThat(SqlIdentifiers.quoteExact(DatabaseDialect.SQLSERVER, "a]b")).isEqualTo("[a]]b]");
        assertThat(SqlIdentifiers.quoteExact(DatabaseDialect.SQLSERVER, "a[b")).isEqualTo("[a[b]");
        for (String invalid : new String[]{null, "", "a\0b"}) {
            assertThatThrownBy(() -> SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SqlIdentifiers.quote(DatabaseDialect.POSTGRESQL, "a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlySqlServerTableReferencesAreSchemaQualified() {
        assertThat(SqlIdentifiers.declaredTableReference(DatabaseDialect.SQLSERVER, "dbo", "order"))
                .isEqualTo("[dbo].[order]");
        assertThat(SqlIdentifiers.declaredTableReference(DatabaseDialect.SQLSERVER, "Sales", "order"))
                .as("SQL Server namespaces keep their configured spelling").isEqualTo("[Sales].[order]");
        assertThat(SqlIdentifiers.declaredTableReference(DatabaseDialect.POSTGRESQL, "public", "order"))
                .isEqualTo("\"order\"");
        assertThat(SqlIdentifiers.declaredTableReference(DatabaseDialect.ORACLE, "app", "order"))
                .isEqualTo("\"ORDER\"");
        assertThat(SqlIdentifiers.declaredTableReference(DatabaseDialect.MYSQL, "app", "order"))
                .isEqualTo("`order`");
        assertThat(SqlIdentifiers.tableReference(DatabaseDialect.MYSQL, "app", "Order"))
                .as("live objects keep their exact spelling").isEqualTo("`Order`");
    }

    @Test
    void historyTableAndItsColumnsAreQuotedEverywhere() {
        assertThat(DatabaseDialect.POSTGRESQL.qualifyHistoryTable("public", "schema_synchronizer_history"))
                .isEqualTo("\"public\".\"schema_synchronizer_history\"");
        assertThat(DatabaseDialect.SQLSERVER.qualifyHistoryTable("dbo", "order"))
                .isEqualTo("[dbo].[order]");
        assertThat(DatabaseDialect.ORACLE.qualifyHistoryTable("app", "order"))
                .isEqualTo("\"APP\".\"ORDER\"");
        assertThat(DatabaseDialect.MYSQL.qualifyHistoryTable("app", "order")).isEqualTo("`order`");

        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            String history = dialect.qualifyHistoryTable(schemaFor(dialect), "schema_synchronizer_history");
            String insert = ChangeSetExecutor.insertHistorySql(dialect, history);
            for (String column : List.of("change_id", "checksum", "description", "applied_by", "execution_ms")) {
                assertThat(insert).as("%s %s", dialect, column).contains(q(dialect, column));
            }
            String ddl = DialectSupport.createHistoryDdl(dialect, history);
            for (String column : List.of("installed_rank", "change_id", "checksum", "applied_at")) {
                assertThat(ddl).as("%s %s", dialect, column).contains(q(dialect, column));
            }
        }
    }

    // ---------------------------------------------------------------- reserved-word matrix

    /** Each word as a table, a column, the primary-key column, and an indexed column. */
    private static SchemaDefinition.TableDef reservedTable(DatabaseDialect dialect, String word) {
        String createSql = "CREATE TABLE " + q(dialect, word) + " (" + q(dialect, word) + " " + bigint(dialect)
                + " NOT NULL, " + q(dialect, "rank") + " " + varchar(dialect) + ", PRIMARY KEY (" + q(dialect, word)
                + "))";
        String ifNotExists = dialect.supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "";
        List<String> indexes = List.of(
                "CREATE INDEX " + ifNotExists + q(dialect, "idx_" + word) + " ON " + q(dialect, word) + " ("
                        + q(dialect, word) + ")",
                "CREATE UNIQUE INDEX " + ifNotExists + q(dialect, "uq_" + word) + " ON " + q(dialect, word) + " ("
                        + q(dialect, "rank") + ", " + q(dialect, word) + ")");
        List<SchemaDefinition.ColumnDef> columns = new ArrayList<>();
        columns.add(new SchemaDefinition.ColumnDef(word, bigint(dialect) + " NOT NULL"));
        if (!word.equals("rank")) {
            columns.add(new SchemaDefinition.ColumnDef("rank", varchar(dialect)));
        }
        return new SchemaDefinition.TableDef(createSql, columns, indexes);
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void reservedWordsAreAcceptedAsTableColumnKeyAndIndexedColumn(DatabaseDialect dialect) {
        for (String word : RESERVED) {
            SchemaDefinition.TableDef table = reservedTable(dialect, word);
            assertThatCode(() -> SchemaSynchronizer.validateDeclarative(new SchemaDefinition(Map.of(word, table)),
                    dialect, options(dialect))).as("%s %s", dialect, word).doesNotThrowAnyException();

            assertThat(SchemaSynchronizer.primaryKeyColumns(table.createSql(), dialect)).as(word)
                    .containsExactly(word);

            String tableSql = SqlIdentifiers.declaredTableReference(dialect, schemaFor(dialect), word);
            IndexDefinition index = IndexDefinition.parse(table.indexes().get(0), dialect);
            assertThat(index.name()).isEqualTo("idx_" + word);
            assertThat(index.table()).isEqualTo(word);
            assertThat(index.toSql(dialect, tableSql)).isEqualTo("CREATE INDEX "
                    + (dialect.supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "")
                    + q(dialect, "idx_" + word) + " ON " + tableSql + " (" + q(dialect, word) + ")");

            IndexDefinition unique = IndexDefinition.parse(table.indexes().get(1), dialect);
            assertThat(unique.toSql(dialect, tableSql)).contains("(" + q(dialect, "rank") + ", " + q(dialect, word) + ")");

            assertThat(SchemaSynchronizer.addColumnSql(tableSql, SqlIdentifiers.quote(dialect, word), "INT", dialect))
                    .contains(tableSql).contains(q(dialect, word));
            assertThat(SchemaSynchronizer.dropIndexSql(dialect, SqlIdentifiers.quote(dialect, "idx_" + word), tableSql))
                    .contains(q(dialect, "idx_" + word));
        }
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void quotedAndBareSpellingsOfTheFoldedNameAreOneIndex(DatabaseDialect dialect) {
        String ifNotExists = dialect.supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "";
        IndexDefinition bare = IndexDefinition.parse("CREATE INDEX " + ifNotExists + "idx_items ON items (label, id)",
                dialect);
        IndexDefinition quoted = IndexDefinition.parse("CREATE INDEX " + ifNotExists + q(dialect, "idx_items") + " ON "
                + q(dialect, "items") + " (" + q(dialect, "label") + ", " + q(dialect, "id") + ")", dialect);
        IndexDefinition upperBare = IndexDefinition.parse("CREATE INDEX " + ifNotExists + "IDX_ITEMS ON ITEMS (LABEL, ID)",
                dialect);

        for (IndexDefinition other : List.of(quoted, upperBare)) {
            assertThat(other.name()).isEqualTo(bare.name());
            assertThat(other.table()).isEqualTo(bare.table());
            assertThat(other.hasSameStructure(bare)).isTrue();
        }
        IndexDefinition reordered = IndexDefinition.parse("CREATE INDEX " + ifNotExists + "idx_items ON items (id, label)",
                dialect);
        assertThat(reordered.hasSameStructure(bare)).isFalse();
    }

    // ---------------------------------------------------------------- createSql target

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void createSqlTargetIsTheDeclaredTableBareOrQuotedAsItsFoldedName(DatabaseDialect dialect) {
        String schema = schemaFor(dialect);
        String storedSchema = SqlIdentifiers.quoteNamespace(dialect, schema);
        for (String accepted : List.of(
                "CREATE TABLE order_lines (id INT)",
                "CREATE TABLE ORDER_LINES (id INT)",
                "CREATE TABLE " + q(dialect, "order_lines") + " (id INT)",
                "CREATE TABLE " + q(dialect, "order_lines") + "(id INT)",
                "create table  " + storedSchema + " . " + q(dialect, "order_lines") + " (id INT)",
                "CREATE TABLE " + schema + ".order_lines (id INT)")) {
            assertThatCode(() -> SchemaSynchronizer.requireCreateTableTarget(accepted, "order_lines", dialect, schema, 63))
                    .as(accepted).doesNotThrowAnyException();
        }
        String wrongCase = dialect == DatabaseDialect.ORACLE ? "\"order_lines\"" : q(dialect, "ORDER_LINES")
                .replace("order_lines".toUpperCase(java.util.Locale.ROOT), "Order_Lines");
        for (String rejected : List.of(
                "CREATE TABLE other (id INT)",
                "CREATE TABLE " + q(dialect, "other") + " (id INT)",
                "CREATE TABLE " + wrongCase + " (id INT)",
                "CREATE TABLE elsewhere.order_lines (id INT)",
                "CREATE TABLE order_lines_x (id INT)",
                "CREATE TABLE order_lines AS SELECT 1",
                "CREATE TABLE " + q(dialect, "order_lines") + " x (id INT)",
                "CREATE TABLE")) {
            assertThatThrownBy(() -> SchemaSynchronizer.requireCreateTableTarget(rejected, "order_lines", dialect,
                    schema, 63)).as(rejected).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("createSql target does not match");
        }
    }

    @Test
    void quotedNameInAnotherSpellingIsRejectedWithBothSpellings() {
        assertThatThrownBy(() -> SchemaSynchronizer.requireCreateTableTarget("CREATE TABLE \"Orders\" (id INT)",
                "orders", DatabaseDialect.POSTGRESQL, "public", 63))
                .hasMessageContaining("\"Orders\"").hasMessageContaining("\"orders\"");
        assertThatThrownBy(() -> SchemaSynchronizer.requireCreateTableTarget("CREATE TABLE \"orders\" (id INT)",
                "orders", DatabaseDialect.ORACLE, "app", 63))
                .hasMessageContaining("\"orders\"").hasMessageContaining("\"ORDERS\"");
        assertThatThrownBy(() -> IndexDefinition.parse("CREATE INDEX IF NOT EXISTS \"Idx\" ON items (id)",
                DatabaseDialect.POSTGRESQL)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"Idx\"");
    }

    @Test
    void primaryKeyColumnsAcceptEachQuoteStyleOnlyInItsDialect() {
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (\"order\" INT NOT NULL, \"key\" INT NOT NULL, PRIMARY KEY (\"order\", \"key\"))",
                DatabaseDialect.POSTGRESQL)).containsExactly("order", "key");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (\"ORDER\" NUMBER(19) NOT NULL, PRIMARY KEY (\"ORDER\"))",
                DatabaseDialect.ORACLE)).containsExactly("order");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (`order` INT NOT NULL PRIMARY KEY)", DatabaseDialect.MYSQL)).containsExactly("order");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t ([order] INT NOT NULL, PRIMARY KEY ([order] ASC))", DatabaseDialect.SQLSERVER))
                .containsExactly("order");
        assertThatThrownBy(() -> SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (\"Order\" INT NOT NULL, PRIMARY KEY (\"Order\"))", DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (\"order\" NUMBER(19) NOT NULL, PRIMARY KEY (\"order\"))", DatabaseDialect.ORACLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- live resolution

    @Test
    void resolveLiveMatchesTheStoredFormFirst() {
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.POSTGRESQL, "table", "orders",
                List.of("Orders", "orders"), true)).isEqualTo("orders");
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.ORACLE, "table", "orders",
                List.of("orders", "ORDERS"), true)).isEqualTo("ORDERS");
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.MYSQL, "table", "orders",
                List.of("ORDERS", "orders"), false)).isEqualTo("orders");
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.POSTGRESQL, "table", "orders",
                List.of("other"), true)).isNull();
    }

    @Test
    void caseVariantResolvesOnlyWhereTheBackendComparesInsensitively() {
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.MYSQL, "table", "orders", List.of("Orders"), false))
                .isEqualTo("Orders");
        assertThat(SqlIdentifiers.resolveLive(DatabaseDialect.SQLSERVER, "column", "label", List.of("Label"), false))
                .isEqualTo("Label");
        for (DatabaseDialect dialect : List.of(DatabaseDialect.POSTGRESQL, DatabaseDialect.MYSQL,
                DatabaseDialect.SQLSERVER)) {
            assertThatThrownBy(() -> SqlIdentifiers.resolveLive(dialect, "table", "orders", List.of("Orders"), true))
                    .as(dialect.id()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SqlIdentifiers.quoteExact(dialect, "Orders"))
                    .hasMessageContaining(SqlIdentifiers.quote(dialect, "orders"))
                    .hasMessageContaining("case-sensitively");
        }
        assertThatThrownBy(() -> SqlIdentifiers.resolveLive(DatabaseDialect.ORACLE, "table", "orders",
                List.of("Orders"), true)).hasMessageContaining("\"Orders\"").hasMessageContaining("\"ORDERS\"");
    }

    @Test
    void twoCaseVariantsOnAnInsensitiveBackendAreAConflictNotAGuess() {
        assertThatThrownBy(() -> SqlIdentifiers.resolveLive(DatabaseDialect.SQLSERVER, "column", "label",
                List.of("Label", "LABEL"), false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[Label]").hasMessageContaining("[LABEL]");
    }

    @Test
    void matchLiveNeverThrowsAndTreatsSensitiveVariantsAsOtherObjects() {
        assertThat(SqlIdentifiers.matchLive(DatabaseDialect.POSTGRESQL, "idx", List.of("Idx"), true)).isNull();
        assertThat(SqlIdentifiers.matchLive(DatabaseDialect.POSTGRESQL, "idx", List.of("Idx", "idx"), true))
                .isEqualTo("idx");
        assertThat(SqlIdentifiers.matchLive(DatabaseDialect.MYSQL, "idx", List.of("IDX"), false)).isEqualTo("IDX");
        assertThat(SqlIdentifiers.matchLive(DatabaseDialect.MYSQL, "idx", List.of("IDX", "Idx"), false)).isNull();
    }

    @Test
    void onlyAsciiLettersFold() {
        assertThat(SqlIdentifiers.asciiCaseVariant("ORDERS", "orders")).isTrue();
        assertThat(SqlIdentifiers.asciiCaseVariant("orders", "orders")).isTrue();
        assertThat(SqlIdentifiers.asciiCaseVariant("\u0130d", "id")).as("dotted capital I").isFalse();
        assertThat(SqlIdentifiers.asciiCaseVariant("\u212Aey", "key")).as("Kelvin sign").isFalse();
        assertThat(SqlIdentifiers.asciiCaseVariant("order", "orders")).isFalse();
        assertThat(SqlIdentifiers.asciiCaseVariant("a_b", "a-b")).isFalse();
    }

    @Test
    void keyColumnsCompareInOrderUnderTheColumnRule() {
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.POSTGRESQL, List.of("order", "key"),
                List.of("order", "key"), true)).isTrue();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.POSTGRESQL, List.of("order", "key"),
                List.of("key", "order"), true)).isFalse();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.ORACLE, List.of("order"),
                List.of("ORDER"), true)).isTrue();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.POSTGRESQL, List.of("order"),
                List.of("Order"), true)).isFalse();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.MYSQL, List.of("order"),
                List.of("Order"), false)).isTrue();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.MYSQL, List.of("order"),
                List.of("order", "key"), false)).isFalse();
        assertThat(SchemaSynchronizer.sameKeyColumns(DatabaseDialect.MYSQL, List.of(), List.of(), false)).isTrue();
    }

    // ---------------------------------------------------------------- snapshot names

    @Test
    void snapshotDeclaresOnlyNamesThatRoundTrip() {
        // Folded spellings declare on every dialect, whatever the server's case rule.
        for (boolean sensitive : new boolean[]{true, false}) {
            assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.POSTGRESQL, "order", "table", sensitive))
                    .isEqualTo("order");
            assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.ORACLE, "ORDER", "table", sensitive))
                    .isEqualTo("order");
            assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.MYSQL, "order", "table", sensitive))
                    .isEqualTo("order");
            assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.SQLSERVER, "order", "column", sensitive))
                    .isEqualTo("order");
        }
        // A case-variant spelling declares only where the server compares that kind insensitively
        // (MySQL lower_case_table_names=1/2 tables, MySQL columns, SQL Server _CI_ collations).
        assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.MYSQL, "Order", "table", false)).isEqualTo("order");
        assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.MARIADB, "Order", "column", false))
                .isEqualTo("order");
        assertThat(SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.SQLSERVER, "Order", "column", false))
                .isEqualTo("order");

        // ...and is refused where it compares sensitively: a replay would declare a different object.
        assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.POSTGRESQL, "Order", "table", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"Order\"");
        assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.ORACLE, "order", "table", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("\"order\"");
        assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.MYSQL, "Users", "table", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("`Users`")
                .hasMessageContaining("case-sensitively");
        assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.SQLSERVER, "IX_Users_Email",
                "index", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[IX_Users_Email]");
        assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.SQLSERVER, "UserId", "column", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[UserId]");

        for (boolean sensitive : new boolean[]{true, false}) {
            assertThatThrownBy(() -> SchemaSnapshotWriter.declaredNameOf(DatabaseDialect.MYSQL, "my table", "table",
                    sensitive))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("`my table`");
        }
    }

    @Test
    void snapshotIndexNamingAppliesEachKindsOwnCaseRule() {
        // MySQL with lower_case_table_names=0: tables sensitive, indexes and columns insensitive.
        SchemaSnapshotWriter.IndexNaming mysqlLctn0 = SchemaSnapshotWriter.IndexNaming.snapshot(
                new SchemaSynchronizer.NameRules(true, false, false));
        assertThat(mysqlLctn0.index(DatabaseDialect.MYSQL, "IdxMixed")).isEqualTo("`idxmixed`");
        assertThat(mysqlLctn0.column(DatabaseDialect.MYSQL, "UserId")).isEqualTo("`userid`");
        assertThat(mysqlLctn0.table(DatabaseDialect.MYSQL, "items")).isEqualTo("`items`");
        assertThatThrownBy(() -> mysqlLctn0.table(DatabaseDialect.MYSQL, "Items"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("`Items`");

        // SQL Server case-sensitive collation: every kind is sensitive.
        SchemaSnapshotWriter.IndexNaming sqlServerCs = SchemaSnapshotWriter.IndexNaming.snapshot(
                new SchemaSynchronizer.NameRules(true, true, true));
        assertThatThrownBy(() -> sqlServerCs.index(DatabaseDialect.SQLSERVER, "IX_Users_Email"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sqlServerCs.column(DatabaseDialect.SQLSERVER, "Email"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sqlServerCs.index(DatabaseDialect.SQLSERVER, "ix_users_email")).isEqualTo("[ix_users_email]");

        SchemaSnapshotWriter.IndexNaming sqlServerCi = SchemaSnapshotWriter.IndexNaming.snapshot(
                new SchemaSynchronizer.NameRules(false, false, false));
        assertThat(sqlServerCi.index(DatabaseDialect.SQLSERVER, "IX_Users_Email")).isEqualTo("[ix_users_email]");
    }

    @Test
    void liveIndexColumnsFoldOnlyWhereTheyCompareEqualToADeclaredKey() {
        SchemaSnapshotWriter.IndexNaming sensitive = SchemaSnapshotWriter.IndexNaming.live(
                new SchemaSynchronizer.NameRules(true, true, true));
        SchemaSnapshotWriter.IndexNaming insensitive = SchemaSnapshotWriter.IndexNaming.live(
                new SchemaSynchronizer.NameRules(true, false, false));

        assertThat(sensitive.column(DatabaseDialect.POSTGRESQL, "order")).isEqualTo("\"order\"");
        assertThat(sensitive.column(DatabaseDialect.POSTGRESQL, "Order")).isEqualTo("\"Order\"");
        assertThat(sensitive.column(DatabaseDialect.ORACLE, "ORDER")).isEqualTo("\"ORDER\"");
        assertThat(insensitive.column(DatabaseDialect.MYSQL, "Order")).isEqualTo("`order`");
        assertThat(insensitive.column(DatabaseDialect.MYSQL, "my col")).isEqualTo("`my col`");
        assertThat(sensitive.index(DatabaseDialect.MYSQL, "IdxMixed")).isEqualTo("`IdxMixed`");
        assertThat(insensitive.table(DatabaseDialect.MYSQL, "Items")).isEqualTo("`Items`");
    }

    @Test
    void snapshotCreateSqlQuotesTableColumnsAndKey() {
        String sql = SchemaSnapshotWriter.buildCreateSql("order",
                List.of(Map.of("name", "user", "type", "BIGINT NOT NULL"),
                        Map.of("name", "desc", "type", "VARCHAR(50)")),
                List.of("user"), DatabaseDialect.POSTGRESQL);
        assertThat(sql).isEqualTo("CREATE TABLE IF NOT EXISTS \"order\" (\"user\" BIGINT NOT NULL, "
                + "\"desc\" VARCHAR(50), PRIMARY KEY (\"user\"))");
        assertThatCode(() -> SchemaSynchronizer.requireCreateTableTarget(sql, "order", DatabaseDialect.POSTGRESQL,
                "public", 63)).doesNotThrowAnyException();
        assertThat(SchemaSynchronizer.primaryKeyColumns(sql, DatabaseDialect.POSTGRESQL)).containsExactly("user");
    }

    // ---------------------------------------------------------------- lock identity

    @Test
    void lockIdentityIsDerivedFromSchemaAndHistoryTableOnEveryDialect() {
        Set<String> identities = new HashSet<>();
        for (String[] pair : List.of(new String[]{"app", "history"}, new String[]{"app", "history_b"},
                new String[]{"other", "history"})) {
            identities.add(DialectSupport.lockIdentity(pair[0], pair[1]));
            assertThat(DialectSupport.mysqlIdentityResource(pair[0], pair[1])).hasSize(DialectSupport.MYSQL_LOCK_NAME_MAX);
            assertThat(DialectSupport.oracleIdentityLockId(pair[0], pair[1])).isBetween(0, 1_073_741_822);
            assertThat(DialectSupport.sqlServerIdentityResource(pair[0], pair[1]).length()).isLessThanOrEqualTo(255);
        }
        assertThat(identities).hasSize(3);

        assertThat(DialectSupport.mysqlIdentityResource("app", "history"))
                .isNotEqualTo(DialectSupport.mysqlIdentityResource("app", "history_b"));
        assertThat(DialectSupport.postgresIdentityKeys("app", "history"))
                .isNotEqualTo(DialectSupport.postgresIdentityKeys("app", "history_b"));
        assertThat(DialectSupport.sqlServerIdentityResource("app", "history"))
                .isNotEqualTo(DialectSupport.sqlServerIdentityResource("other", "history"));

        assertThat(DialectSupport.lockIdentity("App", "HISTORY"))
                .as("folding may serialize two ledgers but never splits one")
                .isEqualTo(DialectSupport.lockIdentity("app", "history"));
    }

    @Test
    void legacyPostgresLockKeysAreUnchanged() {
        assertThat(DialectSupport.namespaceLockKey("public")).isEqualTo(1084631176);
        assertThat(Math.floorMod(Long.hashCode(7_249_031_147L), Integer.MAX_VALUE)).isEqualTo(806580201);
    }
}
