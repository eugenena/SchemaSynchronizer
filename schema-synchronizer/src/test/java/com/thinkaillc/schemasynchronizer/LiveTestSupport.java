// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Assumptions;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Safety rails for the live-database suites: destructive statements only ever run inside a
 * namespace whose name marks it as test-only, cleanup drops every object in that namespace, and
 * leftovers fail the suite instead of being swallowed.
 */
final class LiveTestSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    /** Per-run PostgreSQL schemas: {@code prefix_} plus a dash-less UUID. */
    private static final Pattern PER_RUN_SCHEMA = Pattern.compile("[a-z][a-z0-9_]*_[0-9a-f]{32}");

    private LiveTestSupport() {
    }

    /**
     * A name is test-only when one of its {@code _}/punctuation-separated tokens is {@code test}
     * (whole token: {@code latest} and {@code contest} do not count), when it is one of the
     * dedicated local/CI accounts ({@code ss}, {@code schema_sync}), or when it is a per-run
     * UUID-suffixed schema.
     */
    static boolean isTestNamespace(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String folded = name.trim().toLowerCase(Locale.ROOT);
        if (folded.equals("ss") || folded.equals("schema_sync")) {
            return true;
        }
        if (PER_RUN_SCHEMA.matcher(folded).matches()) {
            return true;
        }
        return Arrays.asList(TOKEN_SPLIT.split(folded)).contains("test");
    }

    static String requireTestNamespace(String name, String what) {
        if (!isTestNamespace(name)) {
            throw new IllegalStateException("refusing destructive test cleanup: " + what + " '" + name
                    + "' is not a test-only namespace (needs a 'test' token, 'ss', 'schema_sync', "
                    + "or a per-run UUID schema)");
        }
        return name;
    }

    static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** Skips, or fails under {@code schema.test.require.live=true}, when an optional prerequisite is missing. */
    static void assumeOrRequire(boolean condition, String message) {
        if (!condition && LiveDatabaseExtension.requireLive()) {
            throw new AssertionError(LiveDatabaseExtension.REQUIRE_LIVE + "=true: " + message);
        }
        Assumptions.assumeTrue(condition, message);
    }

    static String backtick(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String bracket(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    static String doubleQuote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Drops every view, table, sequence, routine, and event in the connection's current
     * MySQL/MariaDB database, then fails if anything is left.
     */
    static void cleanMySqlFamilyDatabase(Connection connection) throws SQLException {
        String database = requireTestNamespace(scalar(connection, "SELECT DATABASE()"), "database");
        List<String> errors = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String[] row : rows(connection, "SELECT TABLE_TYPE, TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_TYPE DESC", 2)) {
                    String kind = switch (row[0]) {
                        case "VIEW", "SYSTEM VIEW" -> "VIEW";
                        case "SEQUENCE" -> "SEQUENCE";
                        default -> "TABLE";
                    };
                    execute(statement, "DROP " + kind + " IF EXISTS " + backtick(row[1]), errors);
                }
                for (String[] row : rows(connection, "SELECT ROUTINE_TYPE, ROUTINE_NAME FROM information_schema.ROUTINES "
                        + "WHERE ROUTINE_SCHEMA = DATABASE()", 2)) {
                    execute(statement, "DROP " + row[0] + " IF EXISTS " + backtick(row[1]), errors);
                }
                for (String[] row : rows(connection, "SELECT EVENT_NAME FROM information_schema.EVENTS "
                        + "WHERE EVENT_SCHEMA = DATABASE()", 1)) {
                    execute(statement, "DROP EVENT IF EXISTS " + backtick(row[0]), errors);
                }
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
        List<String> leftovers = new ArrayList<>();
        for (String[] row : rows(connection, "SELECT CONCAT('table ', TABLE_NAME) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() UNION ALL "
                + "SELECT CONCAT(ROUTINE_TYPE, ' ', ROUTINE_NAME) FROM information_schema.ROUTINES "
                + "WHERE ROUTINE_SCHEMA = DATABASE() UNION ALL "
                + "SELECT CONCAT('trigger ', TRIGGER_NAME) FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA = DATABASE() UNION ALL "
                + "SELECT CONCAT('event ', EVENT_NAME) FROM information_schema.EVENTS "
                + "WHERE EVENT_SCHEMA = DATABASE()", 1)) {
            leftovers.add(row[0]);
        }
        failOnLeftovers(database, leftovers, errors);
    }

    /** Drops every user object in the connection's current SQL Server database, then fails if anything is left. */
    static void cleanSqlServerDatabase(Connection connection) throws SQLException {
        String database = requireTestNamespace(scalar(connection, "SELECT DB_NAME()"), "database");
        List<String> errors = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            for (String[] row : rows(connection, "SELECT s.name, f.name, t.name FROM sys.foreign_keys f "
                    + "JOIN sys.tables t ON t.object_id = f.parent_object_id "
                    + "JOIN sys.schemas s ON s.schema_id = t.schema_id", 3)) {
                execute(statement, "ALTER TABLE " + bracket(row[0]) + "." + bracket(row[2])
                        + " DROP CONSTRAINT " + bracket(row[1]), errors);
            }
            for (String[] row : rows(connection, "SELECT s.name, o.name, o.type FROM sys.objects o "
                    + "JOIN sys.schemas s ON s.schema_id = o.schema_id "
                    + "WHERE o.is_ms_shipped = 0 AND o.parent_object_id = 0 "
                    + "AND o.type IN ('V', 'U', 'P', 'FN', 'IF', 'TF', 'SO', 'SN', 'TR') "
                    + "ORDER BY CASE o.type WHEN 'V' THEN 0 WHEN 'TR' THEN 1 ELSE 2 END", 3)) {
                String kind = switch (row[2].trim()) {
                    case "V" -> "VIEW";
                    case "U" -> "TABLE";
                    case "P" -> "PROCEDURE";
                    case "SO" -> "SEQUENCE";
                    case "SN" -> "SYNONYM";
                    case "TR" -> "TRIGGER";
                    default -> "FUNCTION";
                };
                execute(statement, "DROP " + kind + " " + bracket(row[0]) + "." + bracket(row[1]), errors);
            }
            for (String[] row : rows(connection, "SELECT s.name, t.name FROM sys.types t "
                    + "JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE t.is_user_defined = 1", 2)) {
                execute(statement, "DROP TYPE " + bracket(row[0]) + "." + bracket(row[1]), errors);
            }
        }
        List<String> leftovers = new ArrayList<>();
        for (String[] row : rows(connection, "SELECT o.type_desc, s.name, o.name FROM sys.objects o "
                + "JOIN sys.schemas s ON s.schema_id = o.schema_id WHERE o.is_ms_shipped = 0 "
                + "AND o.type NOT IN ('PK', 'UQ', 'D', 'C', 'F', 'IT', 'S', 'SQ')", 3)) {
            leftovers.add(row[0] + " " + row[1] + "." + row[2]);
        }
        for (String[] row : rows(connection, "SELECT s.name, t.name FROM sys.types t "
                + "JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE t.is_user_defined = 1", 2)) {
            leftovers.add("TYPE " + row[0] + "." + row[1]);
        }
        failOnLeftovers(database, leftovers, errors);
    }

    /**
     * Drops every object the connected Oracle user owns, then fails if anything but
     * recycle-bin entries is left. System-generated LOB, identity-sequence, and PL/SQL
     * pipelined types go with their owners, so any survivor is a real leak.
     */
    static void cleanOracleSchema(Connection connection) throws SQLException {
        String owner = requireTestNamespace(scalar(connection, "SELECT USER FROM dual"), "schema");
        List<String> errors = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            // Two passes: dependency order between types, views, and tables is not known up front.
            for (int pass = 0; pass < 2; pass++) {
                List<String> passErrors = pass == 0 ? new ArrayList<>() : errors;
                for (String[] row : rows(connection, "SELECT object_type, object_name FROM user_objects "
                        + "WHERE object_name NOT LIKE 'BIN$%' AND generated = 'N' "
                        + "AND object_type IN ('VIEW', 'MATERIALIZED VIEW', 'TABLE', 'SEQUENCE', 'SYNONYM', "
                        + "'FUNCTION', 'PROCEDURE', 'PACKAGE', 'TRIGGER', 'TYPE') "
                        + "ORDER BY CASE object_type WHEN 'VIEW' THEN 0 WHEN 'TRIGGER' THEN 1 "
                        + "WHEN 'TABLE' THEN 2 WHEN 'TYPE' THEN 9 ELSE 5 END", 2)) {
                    String name = doubleQuote(row[1]);
                    String sql = switch (row[0]) {
                        case "TABLE" -> "DROP TABLE " + name + " CASCADE CONSTRAINTS PURGE";
                        case "TYPE" -> "DROP TYPE " + name + " FORCE";
                        default -> "DROP " + row[0] + " " + name;
                    };
                    execute(statement, sql, passErrors);
                }
            }
            execute(statement, "PURGE RECYCLEBIN", errors);
        }
        List<String> leftovers = new ArrayList<>();
        for (String[] row : rows(connection, "SELECT object_type || ' ' || object_name FROM user_objects "
                + "WHERE object_name NOT LIKE 'BIN$%' ORDER BY object_type, object_name", 1)) {
            leftovers.add(row[0]);
        }
        failOnLeftovers(owner, leftovers, errors);
    }

    private static void failOnLeftovers(String namespace, List<String> leftovers, List<String> errors) {
        if (!leftovers.isEmpty() || !errors.isEmpty()) {
            throw new AssertionError("test cleanup left objects in '" + namespace + "': " + leftovers
                    + (errors.isEmpty() ? "" : "; drop errors: " + errors));
        }
    }

    private static void execute(Statement statement, String sql, List<String> errors) {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            errors.add(sql + " -> " + e.getMessage());
        }
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("no row from " + sql);
            }
            return rows.getString(1);
        }
    }

    private static List<String[]> rows(Connection connection, String sql, int width) throws SQLException {
        List<String[]> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String[] row = new String[width];
                for (int i = 0; i < width; i++) {
                    row[i] = rows.getString(i + 1);
                }
                result.add(row);
            }
        }
        return result;
    }
}
