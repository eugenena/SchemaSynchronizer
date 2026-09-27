// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;

@LiveDatabase(engine = "PostgreSQL", properties = {
        "schema.test.jdbc.url", "schema.test.jdbc.user", "schema.test.jdbc.password"})
class IdentifierQuotingPostgresIntegrationTest extends IdentifierQuotingLiveCells {

    private static final String SCHEMA = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    private static final String REPLAY = "ss_ident_test_" + LiveTestSupport.randomHex(8);

    private static Connection open() throws Exception {
        return DriverManager.getConnection(System.getProperty("schema.test.jdbc.url"),
                System.getProperty("schema.test.jdbc.user"), System.getProperty("schema.test.jdbc.password"));
    }

    private static void recreate(String schema, boolean create) throws Exception {
        LiveTestSupport.requireTestNamespace(schema, "schema");
        try (Connection connection = open(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + LiveTestSupport.doubleQuote(schema) + " CASCADE");
            if (create) {
                statement.execute("CREATE SCHEMA " + LiveTestSupport.doubleQuote(schema));
            }
        }
    }

    @BeforeAll
    static void createSchemas() throws Exception {
        recreate(SCHEMA, true);
        recreate(REPLAY, true);
    }

    @AfterAll
    static void dropSchemas() throws Exception {
        recreate(SCHEMA, false);
        recreate(REPLAY, false);
    }

    @BeforeEach
    @AfterEach
    void cleanSchemas() throws Exception {
        recreate(SCHEMA, true);
        recreate(REPLAY, true);
    }

    @Override
    DatabaseDialect dialect() {
        return DatabaseDialect.POSTGRESQL;
    }

    @Override
    Connection connection() throws Exception {
        return open();
    }

    @Override
    Connection replayConnection() throws Exception {
        return open();
    }

    @Override
    String schema() {
        return SCHEMA;
    }

    @Override
    String replaySchema() {
        return REPLAY;
    }

    @Override
    String rawTable(String exactName) {
        return LiveTestSupport.doubleQuote(SCHEMA) + "." + LiveTestSupport.doubleQuote(exactName);
    }

    @Override
    boolean tablesCaseSensitive(Connection connection) {
        return true;
    }

    @Override
    boolean columnsCaseSensitive(Connection connection) {
        return true;
    }
}
