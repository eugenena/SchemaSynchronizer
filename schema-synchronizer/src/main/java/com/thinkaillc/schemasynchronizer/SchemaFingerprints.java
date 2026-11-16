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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
     * columns, and indexes, sorted by name. Raw JSON ordering and formatting do not participate;
     * absent tables, columns, and indexes count as empty, matching synchronization.
     */
    public static String definitionFingerprint(SchemaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("schema definition is required");
        }
        SqlLexer.Mode mode = lexerMode(definition);
        StringBuilder raw = new StringBuilder();
        Map<String, SchemaDefinition.TableDef> tables =
                definition.tables() == null ? Map.of() : definition.tables();
        tables.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toLowerCase(Locale.ROOT)))
                .forEach(entry -> {
                    var table = entry.getValue();
                    raw.append("table:").append(entry.getKey().toLowerCase(Locale.ROOT)).append(NL);
                    raw.append("create:").append(normalize(table.createSql(), mode)).append(NL);
                    if (table.columns() != null) {
                        table.columns().stream()
                                .filter(column -> column != null && column.name() != null)
                                .sorted(Comparator.comparing(
                                        column -> column.name().toLowerCase(Locale.ROOT)))
                                .forEach(column -> raw.append("column:")
                                        .append(column.name().toLowerCase(Locale.ROOT)).append(":")
                                        .append(normalize(column.definition(), mode)).append(NL));
                    }
                    if (table.indexes() != null) {
                        table.indexes().stream().filter(Objects::nonNull).sorted()
                                .forEach(index -> raw.append("index:").append(normalize(index, mode)).append(NL));
                    }
                });
        return sha256(raw.toString());
    }

    /**
     * Hashes the live schema as JDBC reports it: user tables with their columns (name, type,
     * nullability) and foreign keys (table, column, referenced table and column, delete rule).
     * The synchronization history table is excluded. Catalog ordering never participates:
     * column entries are hashed sorted by name. Metadata reads use the dialect's stored schema
     * spelling and each table's exact catalog name, and rows a wildcard pattern merely admitted
     * (a neighboring schema or table) are excluded.
     */
    public static String liveSchemaFingerprint(Connection connection, SchemaSynchronizerOptions options)
            throws SQLException {
        if (connection == null || options == null) {
            throw new IllegalArgumentException("connection and options are required");
        }
        DatabaseMetaData metadata = connection.getMetaData();
        DatabaseDialect dialect = DatabaseDialect.detect(metadata);
        String catalog = dialect.metadataCatalog(connection, options.schema());
        String schema = dialect.metadataSchemaPattern(options.schema());
        Map<String, StringBuilder> tables = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.equalsIgnoreCase(options.historyTable())
                        || !DatabaseDialect.isRequestedObject(rs, schema, null)) {
                    continue;
                }
                tables.put(name, new StringBuilder("table:")
                        .append(name.toLowerCase(Locale.ROOT)).append(NL));
            }
        }
        Map<String, List<String>> columnEntries = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getColumns(catalog, schema, "%", "%")) {
            while (rs.next()) {
                if (!DatabaseDialect.isRequestedObject(rs, schema, null)) {
                    continue;
                }
                String table = rs.getString("TABLE_NAME");
                if (table == null || !tables.containsKey(table)) {
                    continue;
                }
                columnEntries.computeIfAbsent(table, key -> new ArrayList<>())
                        .add("column:" + rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)
                                + ":" + rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT)
                                + ":nullable=" + rs.getInt("NULLABLE"));
            }
        }
        // Physical column order is catalog data, not schema shape: hash each table's entries sorted.
        for (Map.Entry<String, StringBuilder> entry : tables.entrySet()) {
            List<String> entries = columnEntries.get(entry.getKey());
            if (entries != null) {
                entries.sort(Comparator.naturalOrder());
                entries.forEach(entry.getValue()::append);
            }
        }
        List<String> foreignKeys = new ArrayList<>();
        for (String table : tables.keySet()) {
            try (ResultSet rs = metadata.getImportedKeys(catalog, schema, table)) {
                while (rs.next()) {
                    if (!requestedImportedKey(rs, schema, table)) {
                        continue;
                    }
                    foreignKeys.add(table.toLowerCase(Locale.ROOT)
                            + ":" + rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT)
                            + "->" + rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT)
                            + ":" + rs.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT)
                            + ":delete=" + rs.getString("DELETE_RULE"));
                }
            }
        }
        foreignKeys.sort(String::compareTo);
        StringBuilder raw = new StringBuilder();
        tables.keySet().stream()
                .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
                .forEach(name -> raw.append(tables.get(name)));
        raw.append("--- foreign keys").append(NL);
        foreignKeys.forEach(key -> raw.append("fk:").append(key).append(NL));
        return sha256(raw.toString());
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

    private static SqlLexer.Mode lexerMode(SchemaDefinition definition) {
        try {
            return definition.declaredDialect().lexerMode();
        } catch (RuntimeException unknownDialect) {
            // The dialect itself is rejected by validation; profile reporting stays usable.
            return SqlLexer.Mode.POSTGRES;
        }
    }

    /**
     * Collapses whitespace outside string literals and quoted identifiers. Their contents are
     * data, so {@code DEFAULT 'a  b'} and {@code DEFAULT 'a b'} must not share a fingerprint.
     */
    private static String normalize(String sql, SqlLexer.Mode mode) {
        if (sql == null) {
            return "";
        }
        List<SqlLexer.Span> spans;
        try {
            spans = SqlLexer.spans(sql, mode);
        } catch (RuntimeException unlexable) {
            return normalizeUnlexable(sql);
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean space = false;
        for (SqlLexer.Span span : spans) {
            String text = sql.substring(span.start(), span.end());
            if (span.kind() == SqlLexer.Kind.STRING || span.kind() == SqlLexer.Kind.QUOTED_IDENT) {
                if (space) {
                    out.append(' ');
                    space = false;
                }
                out.append(text);
                continue;
            }
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isWhitespace(character)) {
                    space = true;
                } else if (space) {
                    out.append(' ');
                    space = false;
                    out.append(character);
                } else {
                    out.append(character);
                }
            }
        }
        return out.toString().trim();
    }

    /** Legacy whitespace collapse for SQL the lexer cannot read (it would also fail synchronization). */
    private static String normalizeUnlexable(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean space = false;
        for (char character : sql.toCharArray()) {
            if (Character.isWhitespace(character)) {
                space = true;
            } else if (space) {
                out.append(' ');
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
