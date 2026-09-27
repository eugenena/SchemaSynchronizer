// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lock release and connection cleanup run after the outcome of the sync is already decided. */
class SchemaSynchronizerLockReleaseTest {

    @Test
    void sqlServerReleaseFailureAfterCommitReturnsTheResult() throws Exception {
        Connection connection = sqlServerConnection();
        when(connection.prepareStatement(contains("sp_releaseapplock")))
                .thenThrow(new SQLException("release failed"));

        SchemaSynchronizationResult result = synchronizer("dbo").synchronizeWithResult(connection, empty("sqlserver"));

        assertThat(result.changed()).isFalse();
        var order = inOrder(connection);
        order.verify(connection).commit();
        order.verify(connection).prepareStatement(contains("sp_releaseapplock"));
        order.verify(connection).setAutoCommit(true);
    }

    @Test
    void sqlServerAutoCommitRestoreFailureAfterCommitReturnsTheResult() throws Exception {
        Connection connection = sqlServerConnection();
        doThrow(new SQLException("restore failed")).when(connection).setAutoCommit(true);

        SchemaSynchronizationResult result = synchronizer("dbo").synchronizeWithResult(connection, empty("sqlserver"));

        assertThat(result.changed()).isFalse();
        verify(connection).commit();
    }

    @Test
    void sqlServerReleaseFailureIsSuppressedOnThePrimaryFailure() throws Exception {
        Connection connection = sqlServerConnection();
        doThrow(new SQLException("commit failed")).when(connection).commit();
        when(connection.prepareStatement(contains("sp_releaseapplock")))
                .thenThrow(new SQLException("release failed"));

        assertThatThrownBy(() -> synchronizer("dbo").synchronizeWithResult(connection, empty("sqlserver")))
                .hasMessage("commit failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage).containsExactly("release failed"));
        verify(connection).rollback();
    }

    @Test
    void mySqlReleaseFailureAfterSuccessfulSyncReturnsTheResult() throws Exception {
        Connection connection = mySqlConnection();
        when(connection.prepareStatement(contains("RELEASE_LOCK")))
                .thenThrow(new SQLException("release failed"));

        SchemaSynchronizationResult result = synchronizer("app").synchronizeWithResult(connection, empty("mysql"));

        assertThat(result.changed()).isFalse();
        verify(connection).prepareStatement(contains("RELEASE_LOCK"));
    }

    @Test
    void mySqlReleaseFailureIsSuppressedOnThePrimaryFailure() throws Exception {
        Connection connection = mySqlConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        when(metadata.getTables(any(), any(), any(), any(String[].class)))
                .thenThrow(new SQLException("metadata failed"));
        when(connection.prepareStatement(contains("RELEASE_LOCK")))
                .thenThrow(new SQLException("release failed"));

        assertThatThrownBy(() -> synchronizer("app").synchronizeWithResult(connection, empty("mysql")))
                .hasMessage("metadata failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage).containsExactly("release failed"));
    }

    private static Connection sqlServerConnection() throws Exception {
        Connection connection = connection("Microsoft SQL Server", "15.00.2000");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getSchema()).thenReturn("dbo");
        when(connection.getCatalog()).thenReturn("app");
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet granted = mock(ResultSet.class);
        when(connection.prepareStatement(contains("sp_getapplock"))).thenReturn(acquire);
        when(connection.prepareStatement(contains("sp_releaseapplock"))).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(granted);
        when(granted.next()).thenReturn(true);
        when(granted.getInt(1)).thenReturn(0);
        return connection;
    }

    private static Connection mySqlConnection() throws Exception {
        Connection connection = connection("MySQL", "8.0.36");
        Statement statement = mock(Statement.class);
        ResultSet database = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(database);
        when(database.next()).thenReturn(true);
        when(database.getString(1)).thenReturn("app");
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet granted = mock(ResultSet.class);
        when(connection.prepareStatement(contains("GET_LOCK"))).thenReturn(acquire);
        when(connection.prepareStatement(contains("RELEASE_LOCK"))).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(granted);
        when(granted.next()).thenReturn(true);
        when(granted.getInt(1)).thenReturn(1);
        return connection;
    }

    private static Connection connection(String product, String version) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet noTables = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getConnection()).thenReturn(connection);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getDatabaseProductVersion()).thenReturn(version);
        when(metadata.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(noTables);
        return connection;
    }

    private static SchemaDefinition empty(String dialect) {
        return new SchemaDefinition(2, dialect, Map.of(), List.of());
    }

    private static SchemaSynchronizer synchronizer(String schema) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L,
                        false, true, true));
    }
}
