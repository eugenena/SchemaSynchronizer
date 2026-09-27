// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a synchronization reads from MySQL/MariaDB {@code information_schema.COLUMNS} about one
 * table, in one query. Keys are column names exactly as the server stores them, which is also how
 * JDBC {@code getColumns} reports them.
 */
final class MySqlTableFacts {
    private static final Pattern EXTRA_ON_UPDATE = Pattern.compile("(?i)\\bon update ([a-z_]+(?:\\(\\d*\\))?)");

    record Column(String name, String columnDefault, String extra, String columnType, String dataType,
                  Integer datetimePrecision, String collation, String characterSet, String comment,
                  String generationExpression, String charsetDefaultCollation) {}

    private final String tableCollation;
    private final Map<String, Column> columns;
    private final Map<String, String> binaryDefaults;

    private MySqlTableFacts(String tableCollation, Map<String, Column> columns, Map<String, String> binaryDefaults) {
        this.tableCollation = tableCollation;
        this.columns = columns;
        this.binaryDefaults = binaryDefaults;
    }

    /**
     * Reads the facts for {@code table} (its exact stored name). Literal BINARY/VARBINARY defaults
     * also need one SHOW CREATE TABLE, only when the table has any.
     */
    static MySqlTableFacts read(Connection conn, String schema, String table, boolean mariaDb) throws SQLException {
        String sql = """
                SELECT c.COLUMN_NAME, c.COLUMN_DEFAULT, c.EXTRA, c.COLUMN_TYPE, c.DATA_TYPE, c.DATETIME_PRECISION,
                       c.COLLATION_NAME, c.CHARACTER_SET_NAME, c.COLUMN_COMMENT, c.GENERATION_EXPRESSION,
                       cs.DEFAULT_COLLATE_NAME, t.TABLE_COLLATION
                FROM information_schema.COLUMNS c
                JOIN information_schema.TABLES t
                  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                LEFT JOIN information_schema.CHARACTER_SETS cs
                  ON cs.CHARACTER_SET_NAME = c.CHARACTER_SET_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ?
                ORDER BY c.ORDINAL_POSITION
                """;
        Map<String, Column> columns = new LinkedHashMap<>();
        String tableCollation = null;
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    int precision = rs.getInt(6);
                    Integer datetimePrecision = rs.wasNull() ? null : precision;
                    Column column = new Column(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), datetimePrecision, rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11));
                    tableCollation = rs.getString(12);
                    columns.put(column.name(), column);
                }
            }
        }
        List<String> literalBinary = new ArrayList<>();
        for (Column column : columns.values()) {
            String type = column.dataType() == null ? "" : column.dataType().toLowerCase(Locale.ROOT);
            if ((type.equals("binary") || type.equals("varbinary"))
                    && SchemaSnapshotWriter.isLiteralBinaryDefault(column.columnDefault(), column.extra(), mariaDb)) {
                literalBinary.add(column.name());
            }
        }
        Map<String, String> binaryDefaults =
                SchemaSnapshotWriter.binaryDefaultsFromCreateTable(conn, schema, table, literalBinary);
        return new MySqlTableFacts(tableCollation, Collections.unmodifiableMap(columns), binaryDefaults);
    }

    Column column(String name) {
        return columns.get(name);
    }

    /** MySQL columns whose default is an expression ({@code EXTRA} has {@code DEFAULT_GENERATED}). */
    Set<String> generatedDefaultColumns() {
        Set<String> names = new HashSet<>();
        columns.values().forEach(column -> {
            if (column.extra() != null && column.extra().toUpperCase(Locale.ROOT).contains("DEFAULT_GENERATED")) {
                names.add(column.name());
            }
        });
        return names;
    }

    /** ON UPDATE expression by column, upper case ({@code CURRENT_TIMESTAMP(3)}). */
    Map<String, String> onUpdate() {
        Map<String, String> onUpdates = new HashMap<>();
        columns.values().forEach(column -> {
            if (column.extra() != null) {
                Matcher onUpdate = EXTRA_ON_UPDATE.matcher(column.extra());
                if (onUpdate.find()) {
                    onUpdates.put(column.name(), onUpdate.group(1).toUpperCase(Locale.ROOT));
                }
            }
        });
        return onUpdates;
    }

    /** COLUMN_DEFAULT by column (MariaDB reports literals quoted, expressions bare, NULL for none). */
    Map<String, String> columnDefaults() {
        Map<String, String> defaults = new HashMap<>();
        columns.values().forEach(column -> defaults.put(column.name(), column.columnDefault()));
        return defaults;
    }

    /** COLUMN_TYPE by column, lower case (e.g. {@code tinyint(1) unsigned}). */
    Map<String, String> dataTypes() {
        Map<String, String> types = new HashMap<>();
        columns.values().forEach(column -> {
            if (column.columnType() != null) {
                types.put(column.name(), column.columnType().toLowerCase(Locale.ROOT));
            }
        });
        return types;
    }

    /** DATETIME_PRECISION by column, for the columns that have one. */
    Map<String, Integer> datetimePrecisions() {
        Map<String, Integer> precisions = new HashMap<>();
        columns.values().forEach(column -> {
            if (column.datetimePrecision() != null) {
                precisions.put(column.name(), column.datetimePrecision());
            }
        });
        return precisions;
    }

    /** Exact literal BINARY/VARBINARY defaults by column (see {@link SchemaSnapshotWriter#mysqlBinaryDefaults}). */
    Map<String, String> binaryDefaults() {
        return binaryDefaults;
    }

    /** The table's character set: collation names start with it, and character set names have no underscore. */
    String tableCharset() {
        return tableCollation == null ? null : tableCollation.split("_", 2)[0];
    }

    /** What MODIFY COLUMN would rewrite on {@code name}, for {@link SchemaSynchronizer#mySqlBlockReason}. */
    SchemaSynchronizer.MySqlColumnFacts columnFacts(String name) {
        Column column = columns.get(name);
        if (column == null) {
            return new SchemaSynchronizer.MySqlColumnFacts(false, null, null, null, null, null, null, null, null);
        }
        return new SchemaSynchronizer.MySqlColumnFacts(true, column.collation(), tableCollation,
                column.characterSet(), column.extra(), column.comment(), column.generationExpression(),
                column.charsetDefaultCollation(), column.columnType());
    }
}
