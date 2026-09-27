// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector columns: dimension read from {@code pg_attribute}, snapshot, replay, add, and dimension
 * drift. Runs in a per-run database with the extension installed in {@code public}, the schema
 * the synchronizer targets there, so the unqualified {@code vector} type resolves under the
 * synchronizer's {@code search_path}.
 */
@LiveDatabase(engine = "PostgreSQL (pgvector)", properties = {
        "schema.test.jdbc.url",
        "schema.test.jdbc.user"})
class PgVectorPostgresIntegrationTest {
    private static final String DATABASE = "ss_vector_test_" + LiveTestSupport.randomHex(4);
    private static boolean created;

    @TempDir
    Path tempDirectory;

    @BeforeAll
    static void createDatabaseWithVector() throws Exception {
        String failure = null;
        try (Connection admin = connect(configuredUrl()); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
            created = true;
        } catch (SQLException e) {
            failure = "cannot create a per-run database: " + e.getMessage();
        }
        if (failure == null) {
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            } catch (SQLException e) {
                failure = "pgvector extension is unavailable to the test user: " + e.getMessage();
            }
        }
        LiveTestSupport.assumeOrRequire(failure == null, String.valueOf(failure));
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (!created) {
            return;
        }
        LiveTestSupport.requireTestNamespace(DATABASE, "database");
        try (Connection admin = connect(configuredUrl()); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            try (var rows = statement.executeQuery("SELECT count(*) FROM pg_database WHERE datname = '"
                    + DATABASE + "'")) {
                rows.next();
                assertThat(rows.getInt(1)).as("database %s after DROP", DATABASE).isZero();
            }
        }
    }

    /** Every test starts from an empty {@code public} (plus the extension) in the per-run database. */
    @AfterEach
    void emptyTheDatabase() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            assertThat(LiveTestSupport.scalar(connection, "SELECT current_database()")).isEqualTo(DATABASE);
            List<String> drops = new ArrayList<>();
            try (var rows = statement.executeQuery("SELECT format('DROP SCHEMA %I CASCADE', nspname) "
                    + "FROM pg_namespace WHERE nspname LIKE 'vec\\_%' UNION ALL "
                    + "SELECT format('DROP TABLE public.%I CASCADE', tablename) FROM pg_tables "
                    + "WHERE schemaname = 'public'")) {
                while (rows.next()) {
                    drops.add(rows.getString(1));
                }
            }
            for (String drop : drops) {
                statement.execute(drop);
            }
        }
    }

    @Test
    void snapshotRecordsTheDimensionAndReplayConverges() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA vec_source");
            statement.execute("CREATE TABLE vec_source.vec_items (id BIGINT PRIMARY KEY, "
                    + "embedding vector(3) NOT NULL, spare vector(1536))");
            statement.execute("INSERT INTO vec_source.vec_items VALUES (1, '[1,2,3]', NULL)");
        }
        Path snapshot = tempDirectory.resolve("vector.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, "vec_source", snapshot);
        }
        String json = Files.readString(snapshot);
        assertThat(json).containsIgnoringCase("vector(3)").containsIgnoringCase("vector(1536)");
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("vec_items").columns())
                .extracting(SchemaDefinition.ColumnDef::definition)
                .anyMatch(definition -> definition.matches("(?i)\"public\"\\.vector\\(3\\) NOT NULL"));

        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, serialized).tablesCreated())
                    .isEqualTo(1);
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult again = synchronizer(true).synchronizeWithResult(connection, serialized);
            assertThat(again.changed()).isFalse();
            assertThat(again.pendingSql()).isEmpty();
        }
        assertThat(formatType("public", "vec_items", "embedding")).isEqualTo("vector(3)");
        assertThat(formatType("public", "vec_items", "spare")).isEqualTo("vector(1536)");
    }

    @Test
    void addingAVectorColumnUsesTheDeclaredDimension() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.vec_items (id BIGINT PRIMARY KEY)");
        }
        SchemaDefinition declared = definition("vector(3)");
        try (Connection connection = connection()) {
            assertThat(synchronizer(true).synchronizeWithResult(connection, declared).columnsAdded()).isEqualTo(1);
        }
        assertThat(formatType("public", "vec_items", "embedding")).isEqualTo("vector(3)");
        try (Connection connection = connection()) {
            SchemaSynchronizationResult again = synchronizer(true).synchronizeWithResult(connection, declared);
            assertThat(again.changed()).isFalse();
            assertThat(again.pendingSql()).isEmpty();
        }
    }

    @Test
    void aDimensionChangeIsPendingAndNeverApplied() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.vec_items (id BIGINT PRIMARY KEY, embedding vector(3))");
            statement.execute("INSERT INTO public.vec_items VALUES (1, '[1,2,3]')");
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult same = synchronizer(true).synchronizeWithResult(connection,
                    definition("vector(3)"));
            assertThat(same.pendingSql()).isEmpty();
        }
        for (String declaredType : List.of("vector(4)", "vector(2)")) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer(false)
                        .synchronizeWithResult(connection, definition(declaredType));
                assertThat(result.columnsAltered()).as(declaredType).isZero();
                assertThat(result.pendingSql()).as(declaredType).singleElement().asString()
                        .contains("embedding").containsIgnoringCase(declaredType);
            }
            assertThat(formatType("public", "vec_items", "embedding")).isEqualTo("vector(3)");
        }
        try (Connection connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT embedding::text FROM public.vec_items")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("[1,2,3]");
        }
    }

    @Test
    void theDimensionIsReadFromTheConfiguredSchemaOnly() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA vec_sibling");
            statement.execute("CREATE TABLE vec_sibling.vec_items (id BIGINT PRIMARY KEY, embedding vector(5))");
            statement.execute("CREATE TABLE public.vec_items (id BIGINT PRIMARY KEY, embedding vector(3))");
        }
        for (String declaredType : List.of("vector(3)", "vector(5)")) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer(false)
                        .synchronizeWithResult(connection, definition(declaredType));
                if (declaredType.equals("vector(3)")) {
                    assertThat(result.pendingSql()).as(declaredType).isEmpty();
                } else {
                    assertThat(result.pendingSql()).as(declaredType).singleElement().asString()
                            .contains("embedding");
                }
            }
        }
    }

    @Test
    void aNonPublicTargetComparesAndCreatesTypesOfAnExtensionInstalledInPublic() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA vec_app");
            statement.execute("CREATE TABLE vec_app.vec_items (id BIGINT PRIMARY KEY, embedding vector(3))");
        }
        for (String declared : List.of("vector(3)", "public.vector(3)", "\"public\".vector(3)",
                "PUBLIC.VECTOR(3)")) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer("vec_app", false)
                        .synchronizeWithResult(connection, definition(declared));
                assertThat(result.pendingSql()).as(declared).isEmpty();
                assertThat(result.changed()).as(declared).isFalse();
            }
        }
        for (String declared : List.of("vec_app.vector(3)", "\"Public\".vector(3)", "pg_catalog.vector(3)")) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer("vec_app", false)
                        .synchronizeWithResult(connection, definition(declared));
                assertThat(result.pendingSql()).as(declared).singleElement().asString()
                        .contains("vec_items.embedding").contains("live type is in public");
            }
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult result = synchronizer("vec_app", false)
                    .synchronizeWithResult(connection, definition("public.vector(4)"));
            assertThat(result.pendingSql()).singleElement().asString().containsIgnoringCase("vector(4)");
        }

        SchemaDefinition widened = new SchemaDefinition(2, "postgresql", Map.of("vec_items",
                new SchemaDefinition.TableDef("CREATE TABLE IF NOT EXISTS vec_items (id BIGINT NOT NULL, "
                        + "embedding public.vector(3), PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("embedding", "public.vector(3)"),
                                new SchemaDefinition.ColumnDef("spare", "public.vector(2)")),
                        List.of())), List.of());
        try (Connection connection = connection()) {
            assertThat(synchronizer("vec_app", true).synchronizeWithResult(connection, widened).columnsAdded())
                    .isEqualTo(1);
        }
        assertThat(formatType("vec_app", "vec_items", "spare")).isEqualTo("vector(2)");

        Path snapshot = tempDirectory.resolve("vec-app.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, "vec_app", snapshot);
        }
        SchemaDefinition serialized = new ObjectMapper().readValue(snapshot.toFile(), SchemaDefinition.class);
        assertThat(serialized.tables().get("vec_items").columns())
                .extracting(SchemaDefinition.ColumnDef::definition)
                .contains("\"public\".vector(3)", "\"public\".vector(2)");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA vec_replay");
        }
        for (String target : List.of("vec_app", "vec_replay", "vec_replay")) {
            try (Connection connection = connection()) {
                SchemaSynchronizationResult result = synchronizer(target, true)
                        .synchronizeWithResult(connection, serialized);
                assertThat(result.pendingSql()).as(target).isEmpty();
            }
        }
        assertThat(formatType("vec_replay", "vec_items", "embedding")).isEqualTo("vector(3)");
        try (Connection connection = connection()) {
            assertThat(synchronizer("vec_replay", true).synchronizeWithResult(connection, serialized).changed())
                    .isFalse();
        }
    }

    private static SchemaSynchronizer synchronizer(String schema, boolean failOnPending) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
    }

    private static SchemaDefinition definition(String embeddingType) {
        return new SchemaDefinition(2, "postgresql", Map.of("vec_items", new SchemaDefinition.TableDef(
                "CREATE TABLE IF NOT EXISTS vec_items (id BIGINT NOT NULL, embedding " + embeddingType
                        + ", PRIMARY KEY (id))",
                List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                        new SchemaDefinition.ColumnDef("embedding", embeddingType)),
                List.of())), List.of());
    }

    private static SchemaSynchronizer synchronizer(boolean failOnPending) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions("public", "schema_synchronizer_history", 7_249_031_147L,
                        false, failOnPending, true));
    }

    private static String formatType(String schema, String table, String column) throws Exception {
        try (Connection connection = connection(); var statement = connection.prepareStatement(
                "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                        + "JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static String configuredUrl() {
        return System.getProperty("schema.test.jdbc.url");
    }

    private static Connection connection() throws Exception {
        return connect(configuredUrl().replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + DATABASE));
    }

    private static Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, System.getProperty("schema.test.jdbc.user", ""),
                System.getProperty("schema.test.jdbc.password", ""));
    }
}
