// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * Creates two per-run users (schemas) with an admin login that can grant {@code EXECUTE ON
 * SYS.DBMS_LOCK}: {@code schema.test.oracle.jdbc.admin.user/password} (e.g. {@code sys as sysdba}).
 */
@LiveDatabase(engine = "Oracle", properties = {
        "schema.test.oracle.jdbc.url", "schema.test.oracle.jdbc.admin.user", "schema.test.oracle.jdbc.admin.password"})
class IdentifierQuotingOracleIntegrationTest extends IdentifierQuotingLiveCells {

    private static final String USER = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    private static final String REPLAY = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    private static final String PASSWORD = "p" + LiveTestSupport.randomHex(12);

    private static String url() {
        return System.getProperty("schema.test.oracle.jdbc.url");
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(url(), System.getProperty("schema.test.oracle.jdbc.admin.user"),
                System.getProperty("schema.test.oracle.jdbc.admin.password"));
    }

    @BeforeAll
    static void createUsers() throws Exception {
        dropUsers();
        try (Connection admin = admin(); var statement = admin.createStatement()) {
            for (String user : List.of(USER, REPLAY)) {
                statement.execute("CREATE USER " + user + " IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS");
                statement.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + user);
                statement.execute("GRANT EXECUTE ON SYS.DBMS_LOCK TO " + user);
            }
        }
    }

    @AfterAll
    static void dropUsers() throws Exception {
        try (Connection admin = admin(); var statement = admin.createStatement()) {
            for (String user : List.of(USER, REPLAY)) {
                LiveTestSupport.requireTestNamespace(user, "schema");
                statement.execute("BEGIN EXECUTE IMMEDIATE 'DROP USER " + user + " CASCADE'; "
                        + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -1918 THEN RAISE; END IF; END;");
            }
        }
    }

    @BeforeEach
    @AfterEach
    void cleanSchemas() throws Exception {
        for (String user : List.of(USER, REPLAY)) {
            try (Connection connection = DriverManager.getConnection(url(), user, PASSWORD)) {
                LiveTestSupport.cleanOracleSchema(connection);
            }
        }
    }

    @Override
    DatabaseDialect dialect() {
        return DatabaseDialect.ORACLE;
    }

    @Override
    Connection connection() throws Exception {
        return DriverManager.getConnection(url(), USER, PASSWORD);
    }

    @Override
    Connection replayConnection() throws Exception {
        return DriverManager.getConnection(url(), REPLAY, PASSWORD);
    }

    @Override
    String schema() {
        return USER;
    }

    @Override
    String replaySchema() {
        return REPLAY;
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
