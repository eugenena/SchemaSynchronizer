// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@LiveDatabase(engine = "SQL Server", properties = {
        "schema.test.sqlserver.jdbc.url", "schema.test.sqlserver.jdbc.user", "schema.test.sqlserver.jdbc.password"})
class IdentifierQuotingSqlServerIntegrationTest extends IdentifierQuotingLiveCells {

    private static final String DATABASE = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    private static final String REPLAY = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    /** A case-sensitive database: object names compare by the database collation. */
    private static final String SENSITIVE = "ss_ident_test_" + LiveTestSupport.randomHex(8);

    private static Connection open(String database) throws Exception {
        String url = System.getProperty("schema.test.sqlserver.jdbc.url");
        String separator = url.endsWith(";") ? "" : ";";
        return DriverManager.getConnection(url + (database == null ? "" : separator + "databaseName=" + database + ";"),
                System.getProperty("schema.test.sqlserver.jdbc.user"),
                System.getProperty("schema.test.sqlserver.jdbc.password"));
    }

    @BeforeAll
    static void createDatabases() throws Exception {
        dropDatabases();
        try (Connection admin = open(null); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + LiveTestSupport.bracket(DATABASE));
            statement.execute("CREATE DATABASE " + LiveTestSupport.bracket(REPLAY));
            statement.execute("CREATE DATABASE " + LiveTestSupport.bracket(SENSITIVE) + " COLLATE Latin1_General_CS_AS");
        }
    }

    @AfterAll
    static void dropDatabases() throws Exception {
        try (Connection admin = open(null); var statement = admin.createStatement()) {
            for (String database : List.of(DATABASE, REPLAY, SENSITIVE)) {
                LiveTestSupport.requireTestNamespace(database, "database");
                statement.execute("IF DB_ID(N'" + database + "') IS NOT NULL BEGIN "
                        + "ALTER DATABASE " + LiveTestSupport.bracket(database)
                        + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; "
                        + "DROP DATABASE " + LiveTestSupport.bracket(database) + "; END");
            }
        }
    }

    @BeforeEach
    @AfterEach
    void cleanDatabases() throws Exception {
        for (String database : List.of(DATABASE, REPLAY, SENSITIVE)) {
            try (Connection connection = open(database)) {
                LiveTestSupport.cleanSqlServerDatabase(connection);
            }
        }
    }

    @Test
    void caseSensitiveCollationMakesACaseVariantTableAndColumnAConflict() throws Exception {
        try (Connection connection = open(SENSITIVE); var statement = connection.createStatement()) {
            assertThat(tablesCaseSensitive(connection)).isTrue();
            statement.execute("CREATE TABLE [dbo].[Orders] (id BIGINT NOT NULL PRIMARY KEY)");
            statement.execute("CREATE TABLE [dbo].[items] (id BIGINT NOT NULL PRIMARY KEY, [Label] VARCHAR(50))");
        }
        SchemaDefinition orders = new SchemaDefinition(2, "sqlserver", Map.of("orders",
                new SchemaDefinition.TableDef("CREATE TABLE orders (id BIGINT NOT NULL, PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")), List.of())), List.of());
        SchemaDefinition items = new SchemaDefinition(2, "sqlserver", Map.of("items",
                new SchemaDefinition.TableDef("CREATE TABLE items (id BIGINT NOT NULL, label VARCHAR(50), PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("label", "VARCHAR(50)")), List.of())), List.of());
        try (Connection connection = open(SENSITIVE)) {
            assertThatThrownBy(() -> synchronizer("dbo").synchronizeWithResult(connection, orders))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("[Orders]").hasMessageContaining("[orders]");
        }
        try (Connection connection = open(SENSITIVE)) {
            assertThatThrownBy(() -> synchronizer("dbo").synchronizeWithResult(connection, items))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("[Label]").hasMessageContaining("[label]");
        }
    }

    @Test
    void caseSensitiveCollationSnapshotsOnlyNamesThatReplayOntoTheSameObjects(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        try (Connection connection = open(SENSITIVE); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE [dbo].[items] (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(80))");
            statement.execute("CREATE INDEX [ix_items_email] ON [dbo].[items] (email)");
        }
        java.nio.file.Path snapshot = tempDir.resolve("snapshot.json");
        try (Connection connection = open(SENSITIVE)) {
            SchemaSnapshotWriter.writeSnapshot(connection, "dbo", snapshot);
        }
        SchemaDefinition definition = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(snapshot.toFile(), SchemaDefinition.class);
        try (Connection connection = open(SENSITIVE)) {
            SchemaSynchronizationResult replay = synchronizer("dbo").synchronizeWithResult(connection, definition);
            assertThat(replay.changed()).isFalse();
            assertThat(replay.pendingSql()).isEmpty();
        }

        for (List<String> cell : List.of(
                List.of("IX_Items_Email", "CREATE INDEX [IX_Items_Email] ON [dbo].[items] (email)"),
                List.of("Nickname", "ALTER TABLE [dbo].[items] ADD [Nickname] VARCHAR(20)"),
                List.of("Orders", "CREATE TABLE [dbo].[Orders] (id BIGINT NOT NULL PRIMARY KEY)"))) {
            String name = cell.get(0);
            String fixture = cell.get(1);
            try (Connection connection = open(SENSITIVE); var statement = connection.createStatement()) {
                statement.execute(fixture);
            }
            try (Connection connection = open(SENSITIVE)) {
                assertThatThrownBy(() -> SchemaSnapshotWriter.writeSnapshot(connection, "dbo",
                        tempDir.resolve("refused.json")))
                        .as(fixture)
                        .isInstanceOf(SchemaSynchronizationException.class)
                        .hasMessageContaining("[" + name + "]")
                        .hasMessageContaining("case-sensitively")
                        .hasMessageNotContaining("of table items names");
            }
            try (Connection connection = open(SENSITIVE); var statement = connection.createStatement()) {
                statement.execute(fixture.startsWith("CREATE INDEX") ? "DROP INDEX [" + name + "] ON [dbo].[items]"
                        : fixture.startsWith("ALTER") ? "ALTER TABLE [dbo].[items] DROP COLUMN [" + name + "]"
                        : "DROP TABLE [dbo].[" + name + "]");
            }
        }
    }

    @Override
    DatabaseDialect dialect() {
        return DatabaseDialect.SQLSERVER;
    }

    @Override
    Connection connection() throws Exception {
        return open(DATABASE);
    }

    @Override
    Connection replayConnection() throws Exception {
        return open(REPLAY);
    }

    @Override
    String schema() {
        return "dbo";
    }

    @Override
    String replaySchema() {
        return "dbo";
    }

    @Override
    boolean tablesCaseSensitive(Connection connection) throws Exception {
        String style = LiveTestSupport.scalar(connection, "SELECT COLLATIONPROPERTY(CONVERT(sysname, "
                + "DATABASEPROPERTYEX(DB_NAME(), 'Collation')), 'ComparisonStyle')");
        return style == null || (Integer.parseInt(style) & 1) == 0;
    }

    @Override
    boolean columnsCaseSensitive(Connection connection) throws Exception {
        return tablesCaseSensitive(connection);
    }
}
