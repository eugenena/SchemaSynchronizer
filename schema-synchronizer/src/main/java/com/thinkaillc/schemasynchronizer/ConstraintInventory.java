// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Constraint inventory read through JDBC metadata, replacing hand-written information_schema
 * counting in adoption tests. Schema arguments are mapped to the dialect's stored spelling and
 * rows are filtered through the dialect's exact-name check, so H2 (upper-case storage) and
 * PostgreSQL (lower-case storage) report identically and a LIKE wildcard cannot admit a
 * neighboring schema or table. The synchronization history table is excluded when its name is
 * given.
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
        DatabaseDialect dialect = DatabaseDialect.detect(metadata);
        String catalog = dialect.metadataCatalog(connection, schema);
        String schemaPattern = dialect.metadataSchemaPattern(schema);
        List<String> tables = userTables(connection, catalog, schemaPattern, excludeTable);
        List<ForeignKey> keys = new ArrayList<>();
        for (String table : tables) {
            try (ResultSet rs = metadata.getImportedKeys(catalog, schemaPattern, table)) {
                while (rs.next()) {
                    if (!requestedImportedKey(rs, schemaPattern, table)) {
                        continue;
                    }
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

    /** Primary key column names of one table, in declaration order ({@code KEY_SEQ}). */
    public static List<String> primaryKeys(Connection connection, String schema, String table)
            throws SQLException {
        DatabaseDialect dialect = DatabaseDialect.detect(connection.getMetaData());
        String catalog = dialect.metadataCatalog(connection, schema);
        String schemaPattern = dialect.metadataSchemaPattern(schema);
        String storedTable = dialect.metadataObjectName(table);
        // getPrimaryKeys orders rows by COLUMN_NAME; KEY_SEQ is the key order.
        java.util.TreeMap<Short, String> bySequence = new java.util.TreeMap<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(catalog, schemaPattern, storedTable)) {
            while (rs.next()) {
                if (!DatabaseDialect.isRequestedObject(rs, schemaPattern, storedTable)) {
                    continue;
                }
                bySequence.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(bySequence.values());
    }

    private static List<String> userTables(Connection connection, String catalog, String schema,
                                           String excludeTable)
            throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(catalog, schema,
                "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || !DatabaseDialect.isRequestedObject(rs, schema, null)) {
                    continue;
                }
                if (excludeTable != null && name.equalsIgnoreCase(excludeTable)) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    /**
     * Whether an imported-keys row names the requested schema and table rather than a sibling
     * that only a LIKE pattern admitted; the row carries {@code FKTABLE_*} columns.
     */
    private static boolean requestedImportedKey(ResultSet row, String schema, String table) throws SQLException {
        if (schema != null) {
            String reported = row.getString("FKTABLE_SCHEM");
            if (!DatabaseDialect.matchesLiterally(schema,
                    reported != null ? reported : row.getString("FKTABLE_CAT"))) {
                return false;
            }
        }
        return table == null || DatabaseDialect.matchesLiterally(table, row.getString("FKTABLE_NAME"));
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
