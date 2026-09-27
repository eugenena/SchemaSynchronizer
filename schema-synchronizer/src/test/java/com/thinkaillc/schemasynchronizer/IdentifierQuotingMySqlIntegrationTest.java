// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@LiveDatabase(engine = "MySQL", properties = {
        "schema.test.mysql.jdbc.url", "schema.test.mysql.jdbc.admin.user", "schema.test.mysql.jdbc.admin.password"})
class IdentifierQuotingMySqlIntegrationTest extends IdentifierQuotingLiveCells {

    private static final IdentifierQuotingMySqlFamilyBinding BINDING =
            new IdentifierQuotingMySqlFamilyBinding("schema.test.mysql.jdbc");

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

    @Test
    void urlDatabaseIsReplaced() {
        assertThat(IdentifierQuotingMySqlFamilyBinding.withDatabase("jdbc:mysql://h:1/db?x=1", "n"))
                .isEqualTo("jdbc:mysql://h:1/n?x=1");
        assertThat(IdentifierQuotingMySqlFamilyBinding.withDatabase("jdbc:mysql://h:1/db", "n"))
                .isEqualTo("jdbc:mysql://h:1/n");
        assertThat(IdentifierQuotingMySqlFamilyBinding.withDatabase("jdbc:mysql://h:1?x=/y", "n"))
                .isEqualTo("jdbc:mysql://h:1/n?x=/y");
    }

    @Override
    DatabaseDialect dialect() {
        return DatabaseDialect.MYSQL;
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
