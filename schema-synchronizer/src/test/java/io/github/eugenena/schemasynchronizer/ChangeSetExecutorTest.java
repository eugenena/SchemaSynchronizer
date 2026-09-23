package io.github.eugenena.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeSetExecutorTest {

    @Test
    void mariaDbRequiresVerificationForRecoveringAnImplicitCommit() throws Exception {
        Connection connection = connectionWithoutHistory();
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "one", "unsafe replay", List.of("ALTER TABLE items ADD COLUMN note TEXT"));

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(
                connection, List.of(change), mariaOptions(), DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires verificationSql");
    }

    @Test
    void mariaDbRejectsUnverifiedMultiStatementChangesBeforeExecution() throws Exception {
        Connection connection = connectionWithoutHistory();
        Statement statement = mock(Statement.class);
        ResultSet verification = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT FALSE")).thenReturn(verification);
        when(verification.next()).thenReturn(true, false);
        when(verification.getBoolean(1)).thenReturn(false);
        when(verification.wasNull()).thenReturn(false);
        SchemaDefinition.ChangeSet change = new SchemaDefinition.ChangeSet(
                "two", "partial commit risk",
                List.of("ALTER TABLE items ADD COLUMN note TEXT", "UPDATE items SET note = ''"),
                "SELECT FALSE");

        assertThatThrownBy(() -> new ChangeSetExecutor().validateHistory(
                connection, List.of(change), mariaOptions(), DatabaseDialect.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one statement");
    }

    private Connection connectionWithoutHistory() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(connection.getCatalog()).thenReturn("test");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables("test", null, "schema_synchronizer_history", new String[]{"TABLE"}))
                .thenReturn(tables);
        when(tables.next()).thenReturn(false);
        return connection;
    }

    private SchemaSynchronizerOptions mariaOptions() {
        return new SchemaSynchronizerOptions(
                "test", "schema_synchronizer_history", 7_249_031_147L, false, true, true);
    }
}
