// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real {@link SchemaSynchronizerCli#main} in a child JVM (so {@code SCHEMA_DB_PASSWORD}
 * can be set) against PostgreSQL: serialize, validate, dry-run, sync, then serialize again.
 *
 * <p>Gated on {@code schema.test.jdbc.url}; with {@code -Dschema.test.require.live=true} a missing
 * URL fails instead of skipping.
 */
class SchemaSynchronizerCliPostgresIntegrationTest {

    private static final String URL = System.getProperty("schema.test.jdbc.url");
    private static final String USER = System.getProperty("schema.test.jdbc.user", "");
    private static final String PASSWORD = System.getProperty("schema.test.jdbc.password", "");
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SOURCE = "cli_test_src_" + SUFFIX;
    private static final String TARGET = "cli_test_dst_" + SUFFIX;
    private static final List<String> created = new ArrayList<>();

    @TempDir
    Path workDir;

    @BeforeAll
    static void requirePostgres() throws SQLException {
        boolean configured = URL != null && !URL.isBlank() && !USER.isBlank();
        if (!configured && Boolean.parseBoolean(System.getProperty("schema.test.require.live", "false").trim())) {
            fail("schema.test.require.live=true but the CLI PostgreSQL suite is missing "
                    + "schema.test.jdbc.url / schema.test.jdbc.user");
        }
        assumeTrue(configured, "CLI PostgreSQL suite skipped; set schema.test.jdbc.url and schema.test.jdbc.user");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SOURCE);
            created.add(SOURCE);
            statement.execute("CREATE SCHEMA " + TARGET);
            created.add(TARGET);
            statement.execute("CREATE TABLE " + SOURCE + ".customer ("
                    + "id BIGINT PRIMARY KEY, "
                    + "email VARCHAR(200) NOT NULL, "
                    + "display_name VARCHAR(100), "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE INDEX idx_customer_email ON " + SOURCE + ".customer (email)");
        }
    }

    @AfterAll
    static void dropSchemas() throws SQLException {
        if (created.isEmpty()) {
            return;
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String schema : created) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void serializeValidateDryRunSyncRoundTripsThroughTheRealCli() throws Exception {
        Path sourceFile = workDir.resolve("source.json");
        Path targetFile = workDir.resolve("target.json");
        Map<String, String> env = Map.of("SCHEMA_DB_PASSWORD", PASSWORD);

        CliProcess.run(env, "serialize", URL, USER, "-", SOURCE, sourceFile.toString()).assertSuccess();
        assertThat(sourceFile).isRegularFile();
        assertThat(Files.readString(sourceFile)).contains("customer", "email");

        CliProcess.run(Map.of(), "validate", sourceFile.toString(), TARGET).assertSuccess();

        CliProcess.run(env, "dry-run", URL, USER, "-", sourceFile.toString(), TARGET).assertSuccess();
        assertThat(tableExists(TARGET, "customer")).as("dry-run must not create tables").isFalse();

        CliProcess.run(env, "sync", URL, USER, "-", sourceFile.toString(), TARGET).assertSuccess();
        assertThat(tableExists(TARGET, "customer")).isTrue();

        CliProcess.run(env, "sync", URL, USER, "-", sourceFile.toString(), TARGET).assertSuccess();

        CliProcess.run(env, "serialize", URL, USER, "-", TARGET, targetFile.toString()).assertSuccess();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode source = mapper.readTree(sourceFile.toFile());
        JsonNode target = mapper.readTree(targetFile.toFile());
        assertThat(target).as("definition serialized from the synced schema").isEqualTo(source);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static boolean tableExists(String schema, String table) throws SQLException {
        try (Connection connection = connect();
             ResultSet tables = connection.getMetaData().getTables(null, schema.toLowerCase(Locale.ROOT),
                     table.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
