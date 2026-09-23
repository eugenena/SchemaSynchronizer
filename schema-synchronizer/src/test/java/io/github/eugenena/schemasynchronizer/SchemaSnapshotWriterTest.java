// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package io.github.eugenena.schemasynchronizer;

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
    void preservesMariaDbIndexPrefixAndDirection() throws Exception {
        ResultSet row = indexRow("idx_items_name", "name");
        when(row.getInt("sub_part")).thenReturn(12);
        when(row.wasNull()).thenReturn(false);
        when(row.getString("collation")).thenReturn("D");

        assertThat(SchemaSnapshotWriter.readMariaDbIndexes(connection(row), "items"))
                .containsExactly("CREATE INDEX IF NOT EXISTS idx_items_name ON items (name(12) DESC)");
    }

    @Test
    void rejectsMariaDbExpressionIndexInsteadOfDiscardingIt() throws Exception {
        ResultSet row = indexRow("idx_items_expression", null);

        assertThatThrownBy(() -> SchemaSnapshotWriter.readMariaDbIndexes(connection(row), "items"))
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
