// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaSynchronizerTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private Statement statement;
    @Mock private ResultSet tablesRs;
    @Mock private ResultSet historyTablesRs;
    @Mock private ResultSet columnsRs;
    @Mock private ResultSet primaryKeysRs;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet preparedRows;
    @Mock private ResultSet lockRs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchemaSynchronizer synchronizer;

    /** Every SQL string the synchronizer sent, in order, tagged by JDBC call. */
    private final List<String> sentSql = new ArrayList<>();

    @BeforeEach
    void setUp() {
        synchronizer = new SchemaSynchronizer(objectMapper, dataSource);
    }

    /** Strict stubs for a PostgreSQL session with no history table; every statement is recorded. */
    private void stubPostgresSession() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getAutoCommit()).thenReturn(true);
        when(metaData.getTables(null, "public", "schema_synchronizer_history", new String[]{"TABLE"}))
                .thenReturn(historyTablesRs);
        when(historyTablesRs.next()).thenReturn(false);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(invocation -> {
            sentSql.add("execute: " + invocation.getArgument(0));
            return false;
        });
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            sentSql.add("query: " + invocation.getArgument(0));
            return lockRs;
        });
        when(lockRs.next()).thenReturn(true);
        when(lockRs.getBoolean(1)).thenReturn(true);
    }

    /** Prepared metadata queries (all returning no rows), recorded in {@link #sentSql}. */
    private void stubPreparedQueries() throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            sentSql.add("prepare: " + invocation.getArgument(0));
            return preparedStatement;
        });
        when(preparedStatement.executeQuery()).thenReturn(preparedRows);
        when(preparedRows.next()).thenReturn(false);
    }

    @Test
    void emptyDefinitionStillChecksTheImmutableLedgerUnderLock() throws Exception {
        stubPostgresSession();
        synchronizer.synchronize(connection, new SchemaDefinition(Map.of()));
        int key1 = DialectSupport.namespaceLockKey("public");
        int key2 = Math.floorMod(Long.hashCode(7_249_031_147L), Integer.MAX_VALUE);
        verify(statement, times(1)).executeQuery(
                "SELECT pg_try_advisory_xact_lock(" + key1 + ", " + key2 + ")");
        verify(statement, times(1)).executeQuery("SELECT pg_try_advisory_xact_lock(7249031147)");
        assertThat(sentSql).containsExactly(EMPTY_DEFINITION_SQL.toArray(String[]::new));
    }

    @Test
    void rejectsDuplicateChangeIdsAcrossPhases() throws Exception {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("duplicate", "before", List.of("SELECT 1"), null,
                        SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA),
                new SchemaDefinition.ChangeSet("duplicate", "after", List.of("SELECT 2"), null,
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)));

        assertThatThrownBy(() -> synchronizer.synchronize(connection, definition))
                .isInstanceOf(SchemaDefinitionException.class)
                .hasMessageContaining("duplicate schema change id");
    }

    @Test
    void missingRequiredDefinitionFailsClosed() {
        SchemaSynchronizer missing = new SchemaSynchronizer(objectMapper, dataSource, "/does-not-exist.json");
        assertThatThrownBy(missing::synchronizeFromClasspath)
                .isInstanceOf(SchemaDefinitionException.class)
                .hasMessageContaining("Required schema definition is missing");
    }

    @Test
    void addsMissingColumn() throws Exception {
        stubPostgresSession();
        stubPreparedQueries();
        when(metaData.getPrimaryKeys(null, "public", "existing_table")).thenReturn(primaryKeysRs);
        when(primaryKeysRs.next()).thenReturn(false);
        when(metaData.getTables(null, "public", "%", new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(tablesRs.getString("TABLE_NAME")).thenReturn("existing_table");
        when(metaData.getColumns(null, "public", "existing_table", "%")).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(columnsRs.getString("TABLE_NAME")).thenReturn("existing_table");
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("name");
        when(columnsRs.getString("TYPE_NAME")).thenReturn("VARCHAR");
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(255);
        when(columnsRs.getString("IS_NULLABLE")).thenReturn("YES");
        when(columnsRs.getString("COLUMN_DEF")).thenReturn(null);

        var tableDef = new SchemaDefinition.TableDef(
                null,
                List.of(
                        new SchemaDefinition.ColumnDef("id", "BIGINT"),
                        new SchemaDefinition.ColumnDef("name", "VARCHAR(255)")
                ),
                null
        );
        synchronizer.synchronize(connection, new SchemaDefinition(Map.of("existing_table", tableDef)));

        verify(statement).execute("ALTER TABLE \"existing_table\" ADD COLUMN IF NOT EXISTS \"id\" BIGINT");
        assertThat(sentSql).containsExactly(withLedgerFrame(
                "execute: ALTER TABLE \"existing_table\" ADD COLUMN IF NOT EXISTS \"id\" BIGINT"));
    }

    @Test
    void appliesDefaultChangeOnExistingColumn() throws Exception {
        stubPostgresSession();
        stubPreparedQueries();
        when(metaData.getPrimaryKeys(null, "public", "t")).thenReturn(primaryKeysRs);
        when(primaryKeysRs.next()).thenReturn(false);
        when(metaData.getTables(null, "public", "%", new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(tablesRs.getString("TABLE_NAME")).thenReturn("t");
        when(metaData.getColumns(null, "public", "t", "%")).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(columnsRs.getString("TABLE_NAME")).thenReturn("t");
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("status");
        when(columnsRs.getString("TYPE_NAME")).thenReturn("VARCHAR");
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(50);
        when(columnsRs.getString("IS_NULLABLE")).thenReturn("YES");
        when(columnsRs.getString("COLUMN_DEF")).thenReturn("'OLD'");

        var tableDef = new SchemaDefinition.TableDef(
                null,
                List.of(new SchemaDefinition.ColumnDef("status", "VARCHAR(50) DEFAULT 'NEW'")),
                null
        );
        synchronizer.synchronize(connection, new SchemaDefinition(Map.of("t", tableDef)));

        verify(statement).execute("ALTER TABLE \"t\" ALTER COLUMN \"status\" SET DEFAULT 'NEW'");
        assertThat(sentSql).containsExactly(withLedgerFrame(
                "execute: ALTER TABLE \"t\" ALTER COLUMN \"status\" SET DEFAULT 'NEW'"));
    }

    /** What the synchronizer sends around the declarative work for an empty ledger in {@code public}. */
    private static final List<String> EMPTY_DEFINITION_SQL = List.of(
            "execute: SET LOCAL search_path TO \"public\"",
            "query: SELECT pg_try_advisory_xact_lock(-904734018, 1625532851)",
            "query: SELECT pg_try_advisory_xact_lock(1084631176, 806580201)",
            "query: SELECT pg_try_advisory_xact_lock(7249031147)");

    /** Post-change read of the altered table's standalone indexes. */
    private static final String STANDALONE_INDEX_QUERY = "prepare: SELECT indexes.indexname, indexes.indexdef"
            + " FROM pg_indexes indexes JOIN pg_namespace namespace ON namespace.nspname = indexes.schemaname"
            + " JOIN pg_class index_class ON index_class.relnamespace = namespace.oid"
            + " AND index_class.relname = indexes.indexname"
            + " WHERE indexes.schemaname = ? AND indexes.tablename = ?"
            + " AND NOT EXISTS (SELECT 1 FROM pg_constraint constraint_row"
            + " WHERE constraint_row.conindid = index_class.oid)";

    /** Exact session for one altered table: frame, the work, then the standalone-index read. */
    private static String[] withLedgerFrame(String... work) {
        List<String> expected = new ArrayList<>(EMPTY_DEFINITION_SQL);
        expected.addAll(List.of(work));
        expected.add(STANDALONE_INDEX_QUERY);
        return expected.toArray(String[]::new);
    }

    @Test
    void skipsNextvalIdentityDefaults() {
        assertThat(SchemaSynchronizer.shouldSkipAlter(
                "BIGINT NOT NULL",
                new LiveColumn("BIGINT", null, null, true, "nextval('t_id_seq'::regclass)"))).isTrue();
        assertThat(SchemaSynchronizer.shouldSkipAlter(
                "BIGSERIAL NOT NULL",
                new LiveColumn("BIGINT", null, null, true, null))).isTrue();
        assertThat(SchemaSynchronizer.shouldSkipAlter(
                "VARCHAR(50) DEFAULT 'x'",
                new LiveColumn("VARCHAR", 50, null, false, "'y'"))).isFalse();

        LiveColumn plain = new LiveColumn("VARCHAR", 100, null, false, "'x'");
        for (String literal : List.of("VARCHAR(20) DEFAULT 'IDENTITY_SVC'", "VARCHAR(20) DEFAULT 'serial'",
                "VARCHAR(20) DEFAULT 'auto_increment'", "VARCHAR(20) DEFAULT 'it''s IDENTITY'",
                "VARCHAR(20) DEFAULT 'BIGSERIAL'")) {
            assertThat(SchemaSynchronizer.shouldSkipAlter(literal, plain)).as(literal).isFalse();
        }
        for (String identifierLike : List.of("VARCHAR(20) DEFAULT 'a'", "INT", "SERIALIZED_BLOB")) {
            assertThat(SchemaSynchronizer.shouldSkipAlter(identifierLike, plain)).as(identifierLike).isFalse();
        }
        for (String identity : List.of("BIGINT NOT NULL AUTO_INCREMENT", "BIGINT IDENTITY(1,1) NOT NULL",
                "NUMBER GENERATED BY DEFAULT AS IDENTITY NOT NULL", "serial", "SMALLSERIAL",
                "BIGINT NOT NULL AUTO_INCREMENT COMMENT 'x'")) {
            assertThat(SchemaSynchronizer.shouldSkipAlter(identity, plain)).as(identity).isTrue();
        }
    }

    @Test
    void mariaDbNeverExecutesSafeFragmentsWhenTheSameColumnHasPendingNarrowing() {
        NonDestructiveAlterPlanner.Plan mixed = new NonDestructiveAlterPlanner.Plan(
                List.of("ALTER TABLE items ALTER COLUMN label SET DEFAULT 'x'"),
                List.of("ALTER TABLE items ALTER COLUMN label TYPE VARCHAR(10)"));

        NonDestructiveAlterPlanner.Plan result = SchemaSynchronizer.mySqlFamilyColumnPlan(
                "items", "label", "VARCHAR(10) DEFAULT 'x'", mixed);

        assertThat(result.applySql()).isEmpty();
        assertThat(result.pendingSql()).containsExactly(
                "ALTER TABLE items MODIFY COLUMN label VARCHAR(10) DEFAULT 'x'; "
                        + "-- pending: unsafe type/nullability change");
    }

    @Test
    void removesUnsupportedIfNotExistsFromEnginesThatRejectIt() {
        assertThat(SchemaSynchronizer.mysqlCompatibleIndexSql(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_items_label ON items (label)",
                DatabaseDialect.MYSQL))
                .isEqualTo("CREATE UNIQUE INDEX idx_items_label ON items (label)");
        assertThat(SchemaSynchronizer.dialectCompatibleIndexSql(
                "CREATE INDEX IF NOT EXISTS idx_items_label ON items (label)",
                DatabaseDialect.SQLSERVER))
                .isEqualTo("CREATE INDEX idx_items_label ON items (label)");
        assertThat(SchemaSynchronizer.dialectCompatibleIndexSql(
                "CREATE INDEX IF NOT EXISTS idx_items_label ON items (label)",
                DatabaseDialect.ORACLE))
                .isEqualTo("CREATE INDEX idx_items_label ON items (label)");
        assertThat(SchemaSynchronizer.mysqlCompatibleIndexSql(
                "CREATE INDEX IF NOT EXISTS idx_items_label ON items (label)",
                DatabaseDialect.MARIADB))
                .contains("IF NOT EXISTS");
        assertThat(SchemaSynchronizer.mysqlCompatibleAddColumnSql(
                "ALTER TABLE items ADD COLUMN IF NOT EXISTS notes VARCHAR(255)",
                DatabaseDialect.MYSQL))
                .isEqualTo("ALTER TABLE items ADD COLUMN notes VARCHAR(255)");
    }

    @Test
    void buildsDialectSpecificAddColumnAndAlterSql() {
        assertThat(SchemaSynchronizer.addColumnSql("items", "notes", "VARCHAR(255)", DatabaseDialect.SQLSERVER))
                .isEqualTo("ALTER TABLE items ADD notes VARCHAR(255)");
        assertThat(SchemaSynchronizer.addColumnSql("items", "notes", "VARCHAR2(255)", DatabaseDialect.ORACLE))
                .isEqualTo("ALTER TABLE items ADD (notes VARCHAR2(255))");

        var none = SchemaSynchronizer.SqlServerColumnFacts.NONE;
        NonDestructiveAlterPlanner.Plan widen = planFor("VARCHAR(100) NOT NULL",
                new LiveColumn("VARCHAR", 40, null, true, null));
        assertThat(SchemaSynchronizer.sqlServerColumnPlan(
                "items", "label", "VARCHAR(100) NOT NULL", widen, none).applySql())
                .containsExactly("ALTER TABLE items ALTER COLUMN label VARCHAR(100) NOT NULL");
        assertThat(SchemaSynchronizer.oracleColumnPlan(
                "items", "label", "VARCHAR2(100) NOT NULL", widen).applySql())
                .containsExactly("ALTER TABLE items MODIFY (label VARCHAR2(100))");

        NonDestructiveAlterPlanner.Plan dropNotNull = planFor("VARCHAR(40)",
                new LiveColumn("VARCHAR", 40, null, true, null));
        assertThat(SchemaSynchronizer.sqlServerColumnPlan(
                "items", "label", "VARCHAR(40)", dropNotNull, none).applySql())
                .containsExactly("ALTER TABLE items ALTER COLUMN label VARCHAR(40) NULL");
        assertThat(SchemaSynchronizer.oracleColumnPlan(
                "items", "label", "VARCHAR2(40)", dropNotNull).applySql())
                .containsExactly("ALTER TABLE items MODIFY (label NULL)");

        NonDestructiveAlterPlanner.Plan dropDefault = planFor("VARCHAR(50)",
                new LiveColumn("VARCHAR", 50, null, false, "'OLD'"));
        assertThat(SchemaSynchronizer.oracleColumnPlan(
                "items", "status", "VARCHAR2(50)", dropDefault).applySql())
                .containsExactly("ALTER TABLE items MODIFY (status DEFAULT NULL)");
    }

    private static NonDestructiveAlterPlanner.Plan planFor(String definition, LiveColumn live) {
        return NonDestructiveAlterPlanner.plan("items", "c", ColumnDefinitionParser.parse(definition), live);
    }

    @Test
    void sqlServerDefaultConstraintBlocksOnlyBaseTypeChanges() {
        String declared = "VARCHAR(100) NOT NULL DEFAULT 'x'";
        NonDestructiveAlterPlanner.Plan widen = planFor(declared,
                new LiveColumn("VARCHAR", 40, null, true, "('x')"));

        NonDestructiveAlterPlanner.Plan lengthWiden = SchemaSynchronizer.sqlServerColumnPlan(
                "items", "label", declared, widen, new SchemaSynchronizer.SqlServerColumnFacts(true, false, null));
        assertThat(lengthWiden.applySql())
                .containsExactly("ALTER TABLE items ALTER COLUMN label VARCHAR(100) NOT NULL");
        assertThat(lengthWiden.pendingSql()).isEmpty();

        NonDestructiveAlterPlanner.Plan baseType = SchemaSynchronizer.sqlServerColumnPlan(
                "items", "qty", "BIGINT DEFAULT 0",
                planFor("BIGINT DEFAULT 0", new LiveColumn("INTEGER", null, null, false, "((0))")),
                new SchemaSynchronizer.SqlServerColumnFacts(true, true, null));
        assertThat(baseType.applySql()).isEmpty();
        assertThat(baseType.pendingSql()).singleElement().asString().contains("DEFAULT constraint");

        NonDestructiveAlterPlanner.Plan dependents = SchemaSynchronizer.sqlServerColumnPlan(
                "items", "label", declared, widen, new SchemaSynchronizer.SqlServerColumnFacts(false, false, "column is used by an index, key"));
        assertThat(dependents.applySql()).isEmpty();
        assertThat(dependents.pendingSql()).singleElement().asString().contains("index, key");
    }

    @Test
    void sqlServerBlockReasonCoversEveryDependencyKind() {
        var widen = Set.of(NonDestructiveAlterPlanner.Op.WIDEN_TYPE);
        var relax = Set.of(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
        ColumnSpec varchar100 = ColumnDefinitionParser.parse("VARCHAR(100)");
        ColumnSpec varcharMax = ColumnDefinitionParser.parse("VARCHAR(MAX)");
        var none = SchemaSynchronizer.SqlServerDependents.NONE;
        var index = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, true, false, false, false, false, true);
        var check = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, false, true, false, false, false, true);
        var stats = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, false, false, true, false, false, true);
        var computed = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, true, false, false, false, false, false, true);
        var deprecated = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, false, false, false, true, false, false);
        var collation = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, false, false, false, false, true, true);
        var parameterized = new SchemaSynchronizer.SqlServerDependents(
                true, false, false, false, false, false, false, false, false, false, true);
        var missing = new SchemaSynchronizer.SqlServerDependents(
                false, false, false, false, false, false, false, false, false, false, false);

        assertThat(SchemaSynchronizer.sqlServerBlockReason(none, varchar100, widen, false, "VARCHAR(100)")).isNull();
        for (var lengthWidenAllowed : List.of(index, check, stats)) {
            assertThat(SchemaSynchronizer.sqlServerBlockReason(lengthWidenAllowed, varchar100, widen, false,
                    "VARCHAR(100)")).isNull();
            assertThat(SchemaSynchronizer.sqlServerBlockReason(lengthWidenAllowed, varcharMax, widen, false,
                    "VARCHAR(MAX)")).contains("index");
            assertThat(SchemaSynchronizer.sqlServerBlockReason(lengthWidenAllowed, varchar100, relax, false,
                    "VARCHAR(100)")).contains("index");
            assertThat(SchemaSynchronizer.sqlServerBlockReason(lengthWidenAllowed,
                    ColumnDefinitionParser.parse("BIGINT"), widen, true, "BIGINT")).isNotNull();
        }
        assertThat(SchemaSynchronizer.sqlServerBlockReason(computed, varchar100, widen, false, "VARCHAR(100)"))
                .contains("computed column");
        assertThat(SchemaSynchronizer.sqlServerBlockReason(deprecated, ColumnDefinitionParser.parse("TEXT"),
                relax, false, "TEXT")).contains("text/ntext");
        assertThat(SchemaSynchronizer.sqlServerBlockReason(collation, varchar100, widen, false, "VARCHAR(100)"))
                .contains("collation");
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, ColumnDefinitionParser.parse("DATETIME2"),
                relax, false, "DATETIME2")).contains("precision");
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, ColumnDefinitionParser.parse("DATETIME2(0)"),
                relax, false, "DATETIME2(0)")).isNull();
        ColumnSpec bareWithDefault = SchemaSynchronizer.withDefaultFractionalPrecision(
                ColumnDefinitionParser.parse("DATETIME2"), DatabaseDialect.SQLSERVER);
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, bareWithDefault, relax, false, "DATETIME2"))
                .isNull();
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, ColumnDefinitionParser.parse("DECIMAL"),
                relax, false, "DECIMAL")).contains("precision");
        // Bare DEC is DECIMAL(18,0), so ALTER COLUMN c DEC keeps the precision the planner compared.
        ColumnSpec bareDecimal = SchemaSynchronizer.withDefaultNumericPrecision(
                ColumnDefinitionParser.parse("DEC"), "DEC", DatabaseDialect.SQLSERVER);
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, bareDecimal, relax, false, "DEC")).isNull();
        assertThat(SchemaSynchronizer.sqlServerBlockReason(parameterized, ColumnDefinitionParser.parse("VARCHAR"),
                relax, false, "VARCHAR")).contains("precision");
        assertThat(SchemaSynchronizer.sqlServerBlockReason(missing, varchar100, widen, false, "VARCHAR(100)"))
                .contains("not found");
    }

    @Test
    void sqlServerDefaultDriftIsACommentNeverExecutableSql() {
        NonDestructiveAlterPlanner.Plan defaultOnly = planFor("VARCHAR(50) DEFAULT 'NEW'",
                new LiveColumn("VARCHAR", 50, null, false, "('OLD')"));
        NonDestructiveAlterPlanner.Plan plan = SchemaSynchronizer.sqlServerColumnPlan(
                "items", "status", "VARCHAR(50) DEFAULT 'NEW'", defaultOnly,
                SchemaSynchronizer.SqlServerColumnFacts.NONE);
        assertThat(plan.applySql()).isEmpty();
        assertThat(plan.pendingSql()).singleElement().asString().startsWith("-- pending:");
    }

    @Test
    void sqlServerEquivalentParenthesizedDefaultIsNoDrift() {
        NonDestructiveAlterPlanner.Plan plan = planFor("INT NOT NULL DEFAULT 0",
                new LiveColumn("INTEGER", null, null, true, "((0))"));
        assertThat(plan.applySql()).isEmpty();
        assertThat(plan.pendingSql()).isEmpty();
    }

    @Test
    void dialectRewriteRejectsPlansWithoutOperations() {
        NonDestructiveAlterPlanner.Plan untyped = new NonDestructiveAlterPlanner.Plan(
                List.of("ALTER TABLE items ALTER COLUMN label DROP NOT NULL"), List.of());
        assertThatThrownBy(() -> SchemaSynchronizer.oracleColumnPlan("items", "label", "VARCHAR2(40)", untyped))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void columnTypeTextStripsDefaultNullabilityAndIdentity() {
        assertThat(SchemaSynchronizer.columnTypeText("VARCHAR(40) NOT NULL DEFAULT 'a NOT NULL'"))
                .isEqualTo("VARCHAR(40)");
        assertThat(SchemaSynchronizer.columnTypeText("VARCHAR(40) DEFAULT 'x' NOT NULL")).isEqualTo("VARCHAR(40)");
        assertThat(SchemaSynchronizer.columnTypeText("BIGINT IDENTITY(1,1) NOT NULL")).isEqualTo("BIGINT");
        assertThat(SchemaSynchronizer.columnTypeText("NUMBER(19) GENERATED BY DEFAULT AS IDENTITY"))
                .isEqualTo("NUMBER(19)");
        assertThat(SchemaSynchronizer.columnTypeText("NVARCHAR(MAX) NULL")).isEqualTo("NVARCHAR(MAX)");
    }

    @Test
    void oracleIntegerStorageComparesAsDeclaredIntegerAndIdentityDefaultsAreSkipped() {
        LiveColumn live = new LiveColumn("NUMERIC", 38, 0, true, null);
        assertThat(comparable(live, "INTEGER NOT NULL").baseType()).isEqualTo("INTEGER");
        assertThat(comparable(live, "NUMBER(19)")).isSameAs(live);
        for (String ansi : List.of("NUMERIC NOT NULL", "DECIMAL NOT NULL", "numeric NOT NULL", "DEC NOT NULL")) {
            ColumnSpec target = SchemaSynchronizer.withDefaultNumericPrecision(
                    ColumnDefinitionParser.parse(ansi), ansi, DatabaseDialect.ORACLE);
            NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("items", "c", target,
                    SchemaSynchronizer.oracleComparableLive(live, target));
            assertThat(plan.applySql()).as(ansi).isEmpty();
            assertThat(plan.pendingSql()).as(ansi).isEmpty();
        }
        assertThat(comparable(live, "NUMBER")).as("Oracle NUMBER is unbounded, not NUMBER(38,0)").isSameAs(live);
        assertThat(planFor("NUMBER NOT NULL", comparable(live, "NUMBER NOT NULL")).applySql())
                .as("unbounded NUMBER widens NUMBER(38,0)").isNotEmpty();
        assertThat(SchemaSynchronizer.shouldSkipAlter("NUMBER(19) NOT NULL",
                new LiveColumn("NUMERIC", 19, 0, true, "\"APP\".\"ISEQ$$_7\".nextval"))).isTrue();
    }

    @Test
    void oracleFloatFamilyComparesByBinaryPrecision() {
        record Cell(String declared, Integer liveBinaryPrecision, String expected) {}
        List<Cell> cells = List.of(
                new Cell("FLOAT", 126, "same"),
                new Cell("FLOAT", null, "same"),
                new Cell("DOUBLE PRECISION", 126, "same"),
                new Cell("REAL", 63, "same"),
                new Cell("FLOAT(126)", 126, "same"),
                new Cell("FLOAT(63)", 63, "same"),
                new Cell("FLOAT(10)", 10, "same"),
                new Cell("REAL", 126, "pending"),
                new Cell("REAL", null, "pending"),
                new Cell("FLOAT(10)", 126, "pending"),
                new Cell("FLOAT(10)", 63, "pending"),
                new Cell("FLOAT(63)", 126, "pending"),
                new Cell("FLOAT", 63, "MODIFY (c FLOAT)"),
                new Cell("DOUBLE PRECISION", 63, "MODIFY (c DOUBLE PRECISION)"),
                new Cell("FLOAT(126)", 10, "MODIFY (c FLOAT(126))"),
                new Cell("REAL", 10, "MODIFY (c REAL)"),
                new Cell("NUMBER(10)", 126, "pending"),
                new Cell("BINARY_DOUBLE", 126, "pending"));
        for (Cell cell : cells) {
            NonDestructiveAlterPlanner.Plan plan = oracleFloatPlan(cell.declared(),
                    new LiveColumn("FLOAT", cell.liveBinaryPrecision(), null, false, null));
            switch (cell.expected()) {
                case "same" -> {
                    assertThat(plan.applySql()).as(cell.toString()).isEmpty();
                    assertThat(plan.pendingSql()).as(cell.toString()).isEmpty();
                }
                case "pending" -> {
                    assertThat(plan.applySql()).as(cell.toString()).isEmpty();
                    assertThat(plan.pendingSql()).as(cell.toString()).singleElement().asString()
                            .contains("MODIFY (c " + cell.declared() + ")");
                }
                default -> {
                    assertThat(plan.pendingSql()).as(cell.toString()).isEmpty();
                    assertThat(plan.applySql()).as(cell.toString())
                            .containsExactly("ALTER TABLE t " + cell.expected());
                }
            }
        }
        // Another engine's live FLOAT carries no precision, so a declared FLOAT(n) is not compared there.
        assertThat(NonDestructiveAlterPlanner.plan("t", "c", ColumnDefinitionParser.parse("FLOAT(10)"),
                new LiveColumn("FLOAT", null, null, false, null)).pendingSql()).isEmpty();
        // BINARY_FLOAT is a different type, not a FLOAT precision.
        assertThat(oracleFloatPlan("REAL", new LiveColumn("BINARY_FLOAT", null, null, false, null))
                .pendingSql()).isNotEmpty();
    }

    @Test
    void oracleFloatPrecisionOutsideTheEngineRangeFailsValidation() {
        for (String invalid : List.of("FLOAT(0)", "FLOAT(127)")) {
            assertThatThrownBy(() -> SchemaSynchronizer.requireSupportedFloatPrecision(
                    ColumnDefinitionParser.parse(invalid), DatabaseDialect.ORACLE, "t.c"))
                    .as(invalid).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..126");
        }
        for (String valid : List.of("FLOAT", "FLOAT(1)", "FLOAT(126)", "REAL", "DOUBLE PRECISION")) {
            SchemaSynchronizer.requireSupportedFloatPrecision(
                    ColumnDefinitionParser.parse(valid), DatabaseDialect.ORACLE, "t.c");
        }
        SchemaSynchronizer.requireSupportedFloatPrecision(
                ColumnDefinitionParser.parse("FLOAT(200)"), DatabaseDialect.POSTGRESQL, "t.c");
    }

    @Test
    void oracleSnapshotsKeepANonDefaultFloatPrecision() {
        assertThat(SchemaSnapshotWriter.columnType("FLOAT", 126, null, DatabaseDialect.ORACLE)).isEqualTo("FLOAT");
        assertThat(SchemaSnapshotWriter.columnType("FLOAT", 63, null, DatabaseDialect.ORACLE)).isEqualTo("FLOAT(63)");
        assertThat(SchemaSnapshotWriter.columnType("FLOAT", 10, null, DatabaseDialect.ORACLE)).isEqualTo("FLOAT(10)");
        assertThat(SchemaSnapshotWriter.columnType("FLOAT", 12, null, DatabaseDialect.MYSQL)).isEqualTo("FLOAT");
        for (int precision : List.of(1, 10, 63, 126)) {
            String written = SchemaSnapshotWriter.columnType("FLOAT", precision, null, DatabaseDialect.ORACLE);
            NonDestructiveAlterPlanner.Plan replay = oracleFloatPlan(written,
                    new LiveColumn("FLOAT", precision, null, false, null));
            assertThat(replay.applySql()).as(written).isEmpty();
            assertThat(replay.pendingSql()).as(written).isEmpty();
        }
    }

    @Test
    void oracleLocalTimeZoneLiveMetadataFoldsLikeTheDeclaration() {
        record Cell(String declared, String liveTypeName, boolean drift) {}
        List<Cell> cells = List.of(
                new Cell("TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP(6) WITH LOCAL TIME ZONE", false),
                new Cell("TIMESTAMP(6) WITH LOCAL TIME ZONE", "TIMESTAMP(6) WITH LOCAL TIME ZONE", false),
                new Cell("TIMESTAMP(3) WITH LOCAL TIME ZONE", "TIMESTAMP(3) WITH LOCAL TIME ZONE", false),
                new Cell("timestamp(3) with local time zone", "TIMESTAMP(3) WITH LOCAL TIME ZONE", false),
                new Cell("TIMESTAMP(3) WITH LOCAL TIME ZONE", "TIMESTAMP(6) WITH LOCAL TIME ZONE", true),
                new Cell("TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP(3) WITH LOCAL TIME ZONE", true),
                new Cell("TIMESTAMP WITH TIME ZONE", "TIMESTAMP(6) WITH LOCAL TIME ZONE", true),
                new Cell("TIMESTAMP", "TIMESTAMP(6) WITH LOCAL TIME ZONE", true),
                new Cell("TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP(6) WITH TIME ZONE", true));
        for (Cell cell : cells) {
            String liveType = ColumnDefinitionParser.normalizeType(cell.liveTypeName());
            LiveColumn live = new LiveColumn(liveType, SchemaSynchronizer.liveFractionalPrecision(
                    cell.liveTypeName(), liveType, null, DatabaseDialect.ORACLE), null, false, null);
            ColumnSpec target = SchemaSynchronizer.oracleComparableTarget(SchemaSynchronizer
                    .withDefaultFractionalPrecision(ColumnDefinitionParser.parse(cell.declared()), DatabaseDialect.ORACLE));
            NonDestructiveAlterPlanner.Plan plan = SchemaSynchronizer.oracleColumnPlan("t", "c", cell.declared(),
                    NonDestructiveAlterPlanner.plan("t", "c", target, SchemaSynchronizer.oracleComparableLive(live, target)));
            assertThat(plan.applySql()).as(cell.toString()).isEmpty();
            assertThat(plan.pendingSql().isEmpty()).as(cell.toString()).isEqualTo(!cell.drift());
        }
        // Snapshots write ojdbc's TYPE_NAME, which parses back to the same type and precision.
        for (int precision : List.of(0, 3, 6, 9)) {
            String typeName = "TIMESTAMP(" + precision + ") WITH LOCAL TIME ZONE";
            String written = SchemaSnapshotWriter.columnType(typeName, 11, precision, DatabaseDialect.ORACLE);
            ColumnSpec replayed = ColumnDefinitionParser.parse(written);
            assertThat(replayed.baseType()).as(written).isEqualTo("TIMESTAMPLTZ");
            assertThat(replayed.length()).as(written).isEqualTo(precision);
        }
    }

    private static NonDestructiveAlterPlanner.Plan oracleFloatPlan(String declared, LiveColumn live) {
        ColumnSpec target = SchemaSynchronizer.oracleComparableTarget(ColumnDefinitionParser.parse(declared));
        NonDestructiveAlterPlanner.Plan plan = NonDestructiveAlterPlanner.plan("t", "c", target,
                SchemaSynchronizer.oracleComparableLive(live, target));
        return SchemaSynchronizer.oracleColumnPlan("t", "c", declared, plan);
    }

    private static LiveColumn comparable(LiveColumn live, String definition) {
        return SchemaSynchronizer.oracleComparableLive(live, ColumnDefinitionParser.parse(definition));
    }

    @Test
    void dropIndexSqlIsDialectAware() {
        assertThat(SchemaSynchronizer.dropIndexSql(DatabaseDialect.SQLSERVER, "idx", "items"))
                .isEqualTo("DROP INDEX idx ON items;");
        assertThat(SchemaSynchronizer.dropIndexSql(DatabaseDialect.POSTGRESQL, "idx", "items"))
                .isEqualTo("DROP INDEX IF EXISTS idx;");
        assertThat(SchemaSynchronizer.dropIndexSql(DatabaseDialect.MYSQL, "idx", "items"))
                .isEqualTo("DROP INDEX idx ON items;");
        assertThat(SchemaSynchronizer.dropIndexSql(DatabaseDialect.MARIADB, "idx", "items"))
                .isEqualTo("DROP INDEX idx ON items;");
        assertThat(SchemaSynchronizer.dropIndexSql(DatabaseDialect.ORACLE, "idx", "items"))
                .isEqualTo("DROP INDEX idx;");
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            if (dialect != DatabaseDialect.POSTGRESQL) {
                assertThat(SchemaSynchronizer.dropIndexSql(dialect, "idx", "items"))
                        .as("%s supported versions reject DROP INDEX IF EXISTS", dialect)
                        .doesNotContainIgnoringCase("IF EXISTS");
            }
        }
    }

    @Test
    void pendingIndexRecreateSqlIsExecutableOnEveryDialect() {
        String declared = "CREATE INDEX IF NOT EXISTS idx_items_note ON items (note)";
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            String sql = SchemaSynchronizer.dialectCompatibleIndexSql(declared, dialect);
            if (dialect.supportsCreateIndexIfNotExists()) {
                assertThat(sql).isEqualTo(declared);
            } else {
                assertThat(sql).as("%s", dialect)
                        .isEqualTo("CREATE INDEX idx_items_note ON items (note)");
            }
        }
        assertThat(DatabaseDialect.ORACLE.supportsCreateIndexIfNotExists()).isFalse();
        assertThat(DatabaseDialect.SQLSERVER.supportsCreateIndexIfNotExists()).isFalse();
    }

    @Test
    void ignoresOnlyMigrationAndOwnHistorySchemaNoise() {
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("flyway_schema_history")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("schema_synchronizer_history")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("spt_monitor")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("msreplication_options")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("thinkai_schema_business_data")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("scheduler_lock")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("work_items")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("system_events")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("flyway_schema_history_pk")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("flyway_business_idx")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("scheduler_lock_pkey")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("idx_work_items_title")).isFalse();
    }
}
