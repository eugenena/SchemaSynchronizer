// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable fingerprints for adoption test assertions. Two databases whose schemas are equally
 * correct produce the same live fingerprint: a migrated legacy database and a fresh
 * definition-only database, or the same database before and after an idempotent second sync.
 * Fingerprints deliberately exclude row data, definition-file ordering, and the history table.
 */
public final class SchemaFingerprints {

    private static final String NL = String.valueOf((char) 10);

    private SchemaFingerprints() {
    }

    /**
     * Hashes the parsed structural shape of a definition: tables with their create SQL,
     * columns, and indexes, sorted by name. Raw JSON ordering and formatting do not participate.
     */
    public static String definitionFingerprint(SchemaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("schema definition is required");
        }
        StringBuilder raw = new StringBuilder();
        definition.tables().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toLowerCase()))
                .forEach(entry -> {
                    var table = entry.getValue();
                    raw.append("table:").append(entry.getKey().toLowerCase()).append(NL);
                    raw.append("create:").append(normalize(table.createSql())).append(NL);
                    table.columns().stream()
                            .sorted(Comparator.comparing(column -> column.name().toLowerCase()))
                            .forEach(column -> raw.append("column:")
                                    .append(column.name().toLowerCase()).append(":")
                                    .append(normalize(column.definition())).append(NL));
                    table.indexes().stream().sorted()
                            .forEach(index -> raw.append("index:").append(normalize(index)).append(NL));
                });
        return sha256(raw.toString());
    }

    /**
     * Hashes the live schema as JDBC reports it: user tables with their columns (name, type,
     * nullability) and foreign keys (table, column, referenced table and column, delete rule).
     * The synchronization history table is excluded. Catalog ordering never participates.
     */
    public static String liveSchemaFingerprint(Connection connection, SchemaSynchronizerOptions options)
            throws SQLException {
        if (connection == null || options == null) {
            throw new IllegalArgumentException("connection and options are required");
        }
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = options.schema();
        Map<String, StringBuilder> tables = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.equalsIgnoreCase(options.historyTable())) {
                    continue;
                }
                tables.put(name.toLowerCase(), new StringBuilder("table:").append(name.toLowerCase()).append(NL));
            }
        }
        try (ResultSet rs = metadata.getColumns(catalog, schema, "%", "%")) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                StringBuilder tableRaw = tables.get(table == null ? null : table.toLowerCase());
                if (tableRaw == null) {
                    continue;
                }
                tableRaw.append("column:").append(rs.getString("COLUMN_NAME").toLowerCase());
                tableRaw.append(":").append(rs.getString("TYPE_NAME").toLowerCase());
                tableRaw.append(":nullable=").append(rs.getInt("NULLABLE"));
                tableRaw.append(NL);
            }
        }
        List<String> foreignKeys = new ArrayList<>();
        for (String table : tables.keySet()) {
            try (ResultSet rs = metadata.getImportedKeys(catalog, schema, table)) {
                while (rs.next()) {
                    foreignKeys.add(table + ":" + rs.getString("FKCOLUMN_NAME").toLowerCase()
                            + "->" + rs.getString("PKTABLE_NAME").toLowerCase()
                            + ":" + rs.getString("PKCOLUMN_NAME").toLowerCase()
                            + ":delete=" + rs.getString("DELETE_RULE"));
                }
            }
        }
        foreignKeys.sort(String::compareTo);
        StringBuilder raw = new StringBuilder();
        tables.keySet().stream().sorted().forEach(name -> raw.append(tables.get(name)));
        raw.append("--- foreign keys").append(NL);
        foreignKeys.forEach(key -> raw.append("fk:").append(key).append(NL));
        return sha256(raw.toString());
    }

    private static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean space = false;
        for (char character : sql.toCharArray()) {
            if (Character.isWhitespace(character)) {
                space = true;
            } else if (space) {
                out.append(" ");
                space = false;
                out.append(character);
            } else {
                out.append(character);
            }
        }
        return out.toString().trim();
    }

    private static String sha256(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", impossible);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
