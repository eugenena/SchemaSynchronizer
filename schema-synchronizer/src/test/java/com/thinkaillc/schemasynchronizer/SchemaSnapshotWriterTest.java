// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaSnapshotWriterTest {

    @Test
    void mysqlIndexesOmitUnsupportedIfNotExists() throws Exception {
        ResultSet row = indexRow("idx_items_label", "label");
        when(row.wasNull()).thenReturn(true);
        when(row.getString("collation")).thenReturn("A");

        assertThat(SchemaSnapshotWriter.readMySqlFamilyIndexes(
                connection(row), "items", DatabaseDialect.MYSQL))
                .containsExactly("CREATE INDEX idx_items_label ON items (label)");
    }

    @Test
    void preservesMariaDbIndexPrefixAndDirection() throws Exception {
        ResultSet row = indexRow("idx_items_name", "name");
        when(row.getInt("sub_part")).thenReturn(12);
        when(row.wasNull()).thenReturn(false);
        when(row.getString("collation")).thenReturn("D");

        assertThat(SchemaSnapshotWriter.readMySqlFamilyIndexes(
                connection(row), "items", DatabaseDialect.MARIADB))
                .containsExactly("CREATE INDEX IF NOT EXISTS idx_items_name ON items (name(12) DESC)");
    }

    @Test
    void rejectsMariaDbExpressionIndexInsteadOfDiscardingIt() throws Exception {
        ResultSet row = indexRow("idx_items_expression", null);

        assertThatThrownBy(() -> SchemaSnapshotWriter.readMySqlFamilyIndexes(
                connection(row), "items", DatabaseDialect.MARIADB))
                .hasMessageContaining("expression index cannot be serialized safely");
    }

    private Connection connection(ResultSet rows) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        return connection;
    }

    private ResultSet indexRow(String name, String column) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("index_name")).thenReturn(name);
        when(rows.getString("column_name")).thenReturn(column);
        when(rows.getBoolean("non_unique")).thenReturn(true);
        when(rows.getShort("seq_in_index")).thenReturn((short) 1);
        return rows;
    }
}
