// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * MySQL/MariaDB binding: two per-run databases created and dropped with the admin login, which is
 * also the synchronizer's login (the current database is the configured schema).
 */
final class IdentifierQuotingMySqlFamilyBinding {
    final String url;
    final String adminUser;
    final String adminPassword;
    final String database = "ss_ident_test_" + LiveTestSupport.randomHex(8);
    final String replay = "ss_ident_test_" + LiveTestSupport.randomHex(8);

    IdentifierQuotingMySqlFamilyBinding(String prefix) {
        this.url = System.getProperty(prefix + ".url");
        this.adminUser = System.getProperty(prefix + ".admin.user");
        this.adminPassword = System.getProperty(prefix + ".admin.password");
    }

    /** {@code url} with its database path replaced by {@code database}. */
    static String withDatabase(String url, String database) {
        int hosts = url.indexOf("//") + 2;
        int query = url.indexOf('?', hosts);
        int end = query < 0 ? url.length() : query;
        int path = url.indexOf('/', hosts);
        String authority = url.substring(0, path >= 0 && path < end ? path : end);
        return authority + "/" + database + url.substring(end);
    }

    Connection open(String db) throws Exception {
        return DriverManager.getConnection(withDatabase(url, db), adminUser, adminPassword);
    }

    void createAll() throws Exception {
        try (Connection admin = DriverManager.getConnection(url, adminUser, adminPassword);
             var statement = admin.createStatement()) {
            for (String db : new String[]{database, replay}) {
                statement.execute("DROP DATABASE IF EXISTS " + LiveTestSupport.backtick(
                        LiveTestSupport.requireTestNamespace(db, "database")));
                statement.execute("CREATE DATABASE " + LiveTestSupport.backtick(db));
            }
        }
    }

    void dropAll() throws Exception {
        try (Connection admin = DriverManager.getConnection(url, adminUser, adminPassword);
             var statement = admin.createStatement()) {
            for (String db : new String[]{database, replay}) {
                statement.execute("DROP DATABASE IF EXISTS " + LiveTestSupport.backtick(
                        LiveTestSupport.requireTestNamespace(db, "database")));
            }
        }
    }

    void cleanAll() throws Exception {
        for (String db : new String[]{database, replay}) {
            try (Connection connection = open(db)) {
                LiveTestSupport.cleanMySqlFamilyDatabase(connection);
            }
        }
    }

    static boolean tablesCaseSensitive(Connection connection) throws Exception {
        return "0".equals(LiveTestSupport.scalar(connection, "SELECT @@lower_case_table_names"));
    }
}
