package io.github.eugenena.schemasynchronizer;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchemaSynchronizer synchronizer;

    @BeforeEach
    void setUp() throws Exception {
        synchronizer = new SchemaSynchronizer(objectMapper, dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        lenient().when(connection.getAutoCommit()).thenReturn(true);
        lenient().when(metaData.getTables(null, "public", "schema_synchronizer_history", new String[]{"TABLE"}))
                .thenReturn(historyTablesRs);
        lenient().when(historyTablesRs.next()).thenReturn(false);
        lenient().when(metaData.getPrimaryKeys(null, "public", "existing_table"))
                .thenReturn(primaryKeysRs);
        lenient().when(metaData.getPrimaryKeys(null, "public", "t"))
                .thenReturn(primaryKeysRs);
        lenient().when(primaryKeysRs.next()).thenReturn(false);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.execute(anyString())).thenReturn(true);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(preparedRows);
        lenient().when(preparedRows.next()).thenReturn(false);
    }

    @Test
    void emptyDefinitionStillChecksTheImmutableLedgerUnderLock() throws Exception {
        synchronizer.synchronize(connection, new SchemaDefinition(Map.of()));
        verify(statement).execute("SELECT pg_advisory_xact_lock(7249031147)");
    }

    @Test
    void rejectsDuplicateChangeIdsAcrossPhases() {
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("duplicate", "before", List.of("SELECT 1"), null,
                        SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA),
                new SchemaDefinition.ChangeSet("duplicate", "after", List.of("SELECT 2"), null,
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)));

        assertThatThrownBy(() -> synchronizer.synchronize(connection, definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate schema change id");
    }

    @Test
    void missingRequiredDefinitionFailsClosed() {
        SchemaSynchronizer missing = new SchemaSynchronizer(objectMapper, dataSource, "/does-not-exist.json");
        assertThatThrownBy(missing::synchronizeFromClasspath)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required schema definition is missing");
    }

    @Test
    void addsMissingColumn() throws Exception {
        when(metaData.getTables(null, "public", "%", new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_NAME")).thenReturn("existing_table");
        when(metaData.getColumns(null, "public", "existing_table", "%")).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
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

        verify(statement).execute("ALTER TABLE existing_table ADD COLUMN IF NOT EXISTS id BIGINT");
    }

    @Test
    void appliesDefaultChangeOnExistingColumn() throws Exception {
        when(metaData.getTables(null, "public", "%", new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_NAME")).thenReturn("t");
        when(metaData.getColumns(null, "public", "t", "%")).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
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

        verify(statement).execute("ALTER TABLE t ALTER COLUMN status SET DEFAULT 'NEW'");
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
    }

    @Test
    void mariaDbNeverExecutesSafeFragmentsWhenTheSameColumnHasPendingNarrowing() {
        NonDestructiveAlterPlanner.Plan mixed = new NonDestructiveAlterPlanner.Plan(
                List.of("ALTER TABLE items ALTER COLUMN label SET DEFAULT 'x'"),
                List.of("ALTER TABLE items ALTER COLUMN label TYPE VARCHAR(10)"));

        NonDestructiveAlterPlanner.Plan result = SchemaSynchronizer.mariaDbColumnPlan(
                "items", "label", "VARCHAR(10) DEFAULT 'x'", mixed);

        assertThat(result.applySql()).isEmpty();
        assertThat(result.pendingSql()).containsExactly(
                "ALTER TABLE items MODIFY COLUMN label VARCHAR(10) DEFAULT 'x'; "
                        + "-- pending: unsafe type/nullability change");
    }

    @Test
    void ignoresOnlyMigrationAndOwnHistorySchemaNoise() {
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("flyway_schema_history")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("schema_synchronizer_history")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("thinkai_schema_business_data")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("scheduler_lock")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaTable("work_items")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("flyway_schema_history_pk")).isTrue();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("flyway_business_idx")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("scheduler_lock_pkey")).isFalse();
        assertThat(SchemaSynchronizer.isIgnorableSchemaIndex("idx_work_items_title")).isFalse();
    }
}
