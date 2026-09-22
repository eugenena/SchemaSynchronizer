package com.thinkai.schema;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaApplierTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private Statement statement;
    @Mock private ResultSet tablesRs;
    @Mock private ResultSet columnsRs;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet preparedRows;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchemaApplier applier;

    @BeforeEach
    void setUp() throws Exception {
        applier = new SchemaApplier(objectMapper, dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getMetaData()).thenReturn(metaData);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(statement.execute(anyString())).thenReturn(true);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(preparedRows);
        lenient().when(preparedRows.next()).thenReturn(false);
    }

    @Test
    void emptyTables_doesNothing() throws Exception {
        applier.applySchema(connection, new SchemaDefinition(Map.of()));
        verify(statement, never()).execute(anyString());
    }

    @Test
    void missingRequiredDefinitionFailsClosed() {
        SchemaApplier missing = new SchemaApplier(objectMapper, dataSource, "/does-not-exist.json");
        assertThatThrownBy(missing::applyFromClasspath)
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
        applier.applySchema(connection, new SchemaDefinition(Map.of("existing_table", tableDef)));

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
        applier.applySchema(connection, new SchemaDefinition(Map.of("t", tableDef)));

        verify(statement).execute("ALTER TABLE t ALTER COLUMN status SET DEFAULT 'NEW'");
    }

    @Test
    void skipsNextvalIdentityDefaults() {
        assertThat(SchemaApplier.shouldSkipAlter(
                "BIGINT NOT NULL",
                new LiveColumn("BIGINT", null, true, "nextval('t_id_seq'::regclass)"))).isTrue();
        assertThat(SchemaApplier.shouldSkipAlter(
                "BIGSERIAL NOT NULL",
                new LiveColumn("BIGINT", null, true, null))).isTrue();
        assertThat(SchemaApplier.shouldSkipAlter(
                "VARCHAR(50) DEFAULT 'x'",
                new LiveColumn("VARCHAR", 50, false, "'y'"))).isFalse();
    }

    @Test
    void ignoresFlywayAndShedlockSchemaNoise() {
        assertThat(SchemaApplier.isIgnorableSchemaTable("flyway_schema_history")).isTrue();
        assertThat(SchemaApplier.isIgnorableSchemaTable("shedlock")).isTrue();
        assertThat(SchemaApplier.isIgnorableSchemaTable("jobs")).isFalse();
        assertThat(SchemaApplier.isIgnorableSchemaIndex("flyway_schema_history_pk")).isTrue();
        assertThat(SchemaApplier.isIgnorableSchemaIndex("idx_jobs_title")).isFalse();
    }
}
