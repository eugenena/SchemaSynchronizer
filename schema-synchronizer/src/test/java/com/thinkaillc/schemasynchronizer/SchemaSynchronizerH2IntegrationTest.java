// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaSynchronizerH2IntegrationTest {

    private static Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:ss_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static SchemaSynchronizer synchronizer() {
        return new SchemaSynchronizer(new ObjectMapper(), null, "/schema-definition.json",
                new SchemaSynchronizerOptions("APP", "schema_synchronizer_history", 73L,
                        false, true, true));
    }

    private static SchemaDefinition definition() {
        return new SchemaDefinition(2, "h2", Map.of("widgets", new SchemaDefinition.TableDef(
                "CREATE TABLE IF NOT EXISTS widgets (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(40) NOT NULL)",
                List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("name", "VARCHAR(40) NOT NULL")),
                List.of("CREATE INDEX IF NOT EXISTS idx_widgets_name ON widgets (name)"))), List.of());
    }

    @Test
    void detectsH2AndConvergesRepeatedlyUsingQuotedIdentifiersAndHistory() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA APP");
            connection.setSchema("APP");
            assertThat(DatabaseDialect.detect(connection.getMetaData())).isEqualTo(DatabaseDialect.H2);

            SchemaSynchronizationResult first = synchronizer().synchronizeWithResult(connection, definition());
            assertThat(first.tablesCreated()).isEqualTo(1);
            assertThat(first.pendingSql()).isEmpty();

            SchemaSynchronizationResult second = synchronizer().synchronizeWithResult(connection, definition());
            assertThat(second.changed()).isFalse();
            assertThat(second.pendingSql()).isEmpty();

            try (var rows = connection.getMetaData().getTables(null, "APP", "WIDGETS", new String[]{"TABLE"})) {
                assertThat(rows.next()).isTrue();
            }
            try (var rows = connection.getMetaData().getIndexInfo(null, "APP", "WIDGETS", false, false)) {
                boolean foundDeclaredIndex = false;
                while (rows.next()) {
                    if ("IDX_WIDGETS_NAME".equals(rows.getString("INDEX_NAME"))) {
                        foundDeclaredIndex = true;
                    }
                }
                assertThat(foundDeclaredIndex).isTrue();
            }
        }
    }

    @Test
    void rejectsCallerOwnedTransactionsBecauseH2DdlCommitsImplicitly() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA APP");
            connection.setSchema("APP");
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> synchronizer().synchronizeWithResult(connection, definition()))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("autoCommit=true");
            connection.rollback();
        }
    }
}
