// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Constraint inventory read through JDBC metadata, replacing hand-written information_schema
 * counting in adoption tests. Schema matching is case-insensitive so H2 (upper-case storage)
 * and PostgreSQL (lower-case storage) report identically. The synchronization history table
 * is excluded when its name is given.
 */
public final class ConstraintInventory {

    /** One foreign key as the database reports it. */
    public record ForeignKey(String table, String column, String referencedTable,
                             String referencedColumn, String deleteRule) {}

    private ConstraintInventory() {
    }

    /** Every foreign key in the schema, sorted by table and column. */
    public static List<ForeignKey> foreignKeys(Connection connection, String schema,
                                               String excludeTable) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> tables = userTables(connection, schema, excludeTable);
        List<ForeignKey> keys = new ArrayList<>();
        for (String table : tables) {
            try (ResultSet rs = metadata.getImportedKeys(connection.getCatalog(), schema, table)) {
                while (rs.next()) {
                    keys.add(new ForeignKey(table, rs.getString("FKCOLUMN_NAME"),
                            rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"),
                            deleteRuleName(rs.getInt("DELETE_RULE"))));
                }
            }
        }
        keys.sort(java.util.Comparator.comparing(ForeignKey::table)
                .thenComparing(ForeignKey::column));
        return keys;
    }

    /** Convenience count of the foreign keys above. */
    public static int countForeignKeys(Connection connection, String schema, String excludeTable)
            throws SQLException {
        return foreignKeys(connection, schema, excludeTable).size();
    }

    /** Primary key column names of one table, in declaration order. */
    public static List<String> primaryKeys(Connection connection, String schema, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), schema, table)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static List<String> userTables(Connection connection, String schema, String excludeTable)
            throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), schema,
                "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || (excludeTable != null
                        && name.equalsIgnoreCase(excludeTable))) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    private static String deleteRuleName(int code) {
        return switch (code) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }
}
