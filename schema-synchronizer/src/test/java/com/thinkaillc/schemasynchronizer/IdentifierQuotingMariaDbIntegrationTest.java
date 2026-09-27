// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;

/** Needs an admin login to create per-run databases: {@code schema.test.mariadb.jdbc.admin.user/password}. */
@LiveDatabase(engine = "MariaDB", properties = {
        "schema.test.mariadb.jdbc.url", "schema.test.mariadb.jdbc.admin.user",
        "schema.test.mariadb.jdbc.admin.password"})
class IdentifierQuotingMariaDbIntegrationTest extends IdentifierQuotingLiveCells {

    private static final IdentifierQuotingMySqlFamilyBinding BINDING =
            new IdentifierQuotingMySqlFamilyBinding("schema.test.mariadb.jdbc");

    @BeforeAll
    static void createDatabases() throws Exception {
        BINDING.createAll();
    }

    @AfterAll
    static void dropDatabases() throws Exception {
        BINDING.dropAll();
    }

    @BeforeEach
    @AfterEach
    void cleanDatabases() throws Exception {
        BINDING.cleanAll();
    }

    @Override
    DatabaseDialect dialect() {
        return DatabaseDialect.MARIADB;
    }

    @Override
    Connection connection() throws Exception {
        return BINDING.open(BINDING.database);
    }

    @Override
    Connection replayConnection() throws Exception {
        return BINDING.open(BINDING.replay);
    }

    @Override
    String schema() {
        return BINDING.database;
    }

    @Override
    String replaySchema() {
        return BINDING.replay;
    }

    @Override
    boolean tablesCaseSensitive(Connection connection) throws Exception {
        return IdentifierQuotingMySqlFamilyBinding.tablesCaseSensitive(connection);
    }

    @Override
    boolean columnsCaseSensitive(Connection connection) {
        return false;
    }
}
