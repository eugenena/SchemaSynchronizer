// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time developer tool: reads the live local database schema via JDBC
 * {@link DatabaseMetaData} and writes (or overwrites) {@code schema-definition.json}.
 *
 * <p>Run after every schema change (new migration applied locally):
 * <pre>
 *   mvn exec:java \
 *     -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSnapshotWriter \
 *     -Dexec.args="jdbc:postgresql://localhost:5432/app app_user - public src/main/resources/schema-definition.json" \
 *     -Dexec.classpathScope=compile
 * </pre>
 * The {@code -} password argument reads {@code SCHEMA_DB_PASSWORD}, keeping the
 * credential out of process arguments. Or use {@code scripts/serialize-schema.sh}.
 *
 * <p>The generated file is committed to source control and used by {@link SchemaSynchronizer}
 * on every startup to verify and fix the production schema additively.
 *
 * <p><b>What is captured:</b> every table in the configured schema, with every
 * column name and its SQL type + nullable flag as the {@code definition}. Also
 * captures {@code createSql} (full CREATE TABLE statement), all indexes from
 * {@code pg_indexes}, and correct vector dimensions via {@code pg_attribute}.
 *
 * <p><b>What is NOT captured:</b> sequences, constraints (FK, CHECK), functions, or triggers.
 * These belong in the ordered {@code changes} array, which the serializer preserves. Index capture
 * means dropping an index locally no-ops silently in prod — SchemaSynchronizer logs
 * orphaned indexes as warnings when they differ from the serialized set.
 */
public class SchemaSnapshotWriter {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotWriter.class);

    /** Tables to exclude from snapshot (system / internal tables). */
    private static final Set<String> EXCLUDE = Set.of("flyway_schema_history", "schema_synchronizer_history");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--restore-json".equals(args[0])) {
            Path outputPath = Paths.get(args.length > 1
                    ? args[1]
                    : "src/main/resources/schema-definition.json");
            restoreIdentityInJson(outputPath);
            log.info("[SchemaSnapshotWriter] Restored PK identity in {}", outputPath.toAbsolutePath());
            return;
        }

        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: SchemaSnapshotWriter <jdbc-url> <user> <password> <schema> <output-path> "
                            + "or SchemaSnapshotWriter --restore-json [output-path]");
        }

        String url = args[0];
        String user = args[1];
        String password = "-".equals(args[2]) ? System.getenv("SCHEMA_DB_PASSWORD") : args[2];
        if (password == null) {
            throw new IllegalArgumentException(
                    "SCHEMA_DB_PASSWORD must be set when the password argument is '-'");
        }
        String schema = SqlIdentifiers.requireIdentifier(args[3], "schema");
        Path outputPath = Paths.get(args[4]);

        log.info("[SchemaSnapshotWriter] Connecting to source database");
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            DatabaseDialect dialect = DatabaseDialect.detect(conn.getMetaData());
            log.info("[SchemaSnapshotWriter] Detected {} {}", dialect.id(),
                    conn.getMetaData().getDatabaseProductVersion());
            Map<String, Object> tables = buildSnapshot(conn.getMetaData(), conn, schema, dialect);
            writeJson(tables, outputPath, dialect);
        }

        log.info("[SchemaSnapshotWriter] Done → {}", outputPath.toAbsolutePath());
        log.info("[SchemaSnapshotWriter] Review the diff, then commit schema-definition.json.");
    }

    private static Map<String, Object> buildSnapshot(DatabaseMetaData meta, Connection conn, String schema,
                                                     DatabaseDialect dialect)
            throws Exception {
        Map<String, Object> tables = new TreeMap<>();

        String catalog = dialect == DatabaseDialect.POSTGRESQL ? null : conn.getCatalog();
        String schemaPattern = dialect == DatabaseDialect.POSTGRESQL ? schema : null;

        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME").toLowerCase();
                if (EXCLUDE.contains(tableName)) continue;

                List<Map<String, String>> columns = new ArrayList<>();
                List<String> pkCols = new ArrayList<>();
                List<Map<String, Object>> pendingCols = new ArrayList<>();

                try (ResultSet cols = meta.getColumns(catalog, schemaPattern, tableName, "%")) {
                    while (cols.next()) {
                        String colName  = cols.getString("COLUMN_NAME").toLowerCase();
                        String typeName = cols.getString("TYPE_NAME").toUpperCase();
                        int size        = cols.getInt("COLUMN_SIZE");
                        int scale       = cols.getInt("DECIMAL_DIGITS");
                        boolean scaleNull = cols.wasNull();
                        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "" : " NOT NULL";
                        String colDefault = cols.getString("COLUMN_DEF");
                        if (dialect != DatabaseDialect.POSTGRESQL && "NULL".equalsIgnoreCase(colDefault)) {
                            colDefault = null;
                        }
                        boolean autoInc = "YES".equalsIgnoreCase(cols.getString("IS_AUTOINCREMENT"));

                        if (dialect == DatabaseDialect.POSTGRESQL && "VECTOR".equals(typeName)) {
                            size = readVectorDimension(conn, schema, tableName, colName);
                        }

                        Map<String, Object> pending = new LinkedHashMap<>();
                        pending.put("name", colName);
                        pending.put("typeName", typeName);
                        pending.put("size", size);
                        pending.put("scale", scaleNull ? null : scale);
                        pending.put("nullable", nullable);
                        pending.put("colDefault", colDefault);
                        pending.put("autoInc", autoInc);
                        pendingCols.add(pending);
                    }
                }

                try (ResultSet pk = meta.getPrimaryKeys(catalog, schemaPattern, tableName)) {
                    while (pk.next()) {
                        pkCols.add(pk.getString("COLUMN_NAME").toLowerCase());
                    }
                }

                List<Map<String, String>> rawColumns = new ArrayList<>();
                for (Map<String, Object> pending : pendingCols) {
                    String colName = (String) pending.get("name");
                    String typeName = (String) pending.get("typeName");
                    int size = (Integer) pending.get("size");
                    Integer scale = (Integer) pending.get("scale");
                    String nullable = (String) pending.get("nullable");
                    String colDefault = (String) pending.get("colDefault");
                    boolean autoInc = Boolean.TRUE.equals(pending.get("autoInc"));
                    boolean isPk = pkCols.contains(colName);

                    String colDef = buildDefinition(typeName, size, scale, nullable, colDefault, autoInc, dialect);

                    Map<String, String> col = new LinkedHashMap<>();
                    col.put("name", colName);
                    col.put("definition", colDef);
                    columns.add(col);

                    Map<String, String> rawCol = new LinkedHashMap<>();
                    rawCol.put("name", colName);
                    rawCol.put("type", buildCreateType(
                            typeName, size, scale, nullable, colDefault, autoInc, isPk, dialect));
                    rawColumns.add(rawCol);
                }

                List<String> indexes = readIndexes(meta, conn, schema, tableName, dialect);
                String createSql = buildCreateSql(tableName, rawColumns, pkCols);
                if (dialect == DatabaseDialect.POSTGRESQL) {
                    createSql = PkIdentity.restoreCreateSql(createSql);
                }
                for (Map<String, String> col : columns) {
                    if (dialect == DatabaseDialect.POSTGRESQL) {
                        col.put("definition", PkIdentity.restoreIdColumnDefinition(
                                col.get("name"), col.get("definition"), createSql));
                    }
                }

                Map<String, Object> tableDef = new LinkedHashMap<>();
                tableDef.put("createSql", createSql);
                tableDef.put("columns", columns);
                tableDef.put("indexes", indexes);
                tables.put(tableName, tableDef);
            }
        }
        return tables;
    }

    private static int readVectorDimension(Connection conn, String schema, String tableName, String colName)
            throws Exception {
        String sql = "SELECT format_type(a.atttypid, a.atttypmod) AS fmt " +
                "FROM pg_attribute a JOIN pg_type t ON a.atttypid = t.oid " +
                "WHERE t.typname = 'vector' AND a.attrelid = ?::regclass AND a.attname = ? AND a.attnum > 0";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schema + "." + tableName);
            stmt.setString(2, colName);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String fmt = rs.getString("fmt");
                    if (fmt != null && fmt.startsWith("vector(")) {
                        return Integer.parseInt(fmt.substring(7, fmt.length() - 1));
                    }
                }
            }
        }
        throw new IllegalStateException("Could not determine vector dimension for "
                + schema + "." + tableName + "." + colName);
    }

    private static List<String> readIndexes(DatabaseMetaData meta, Connection conn, String schema, String tableName,
                                            DatabaseDialect dialect) throws Exception {
        if (dialect != DatabaseDialect.POSTGRESQL) {
            return readMySqlFamilyIndexes(conn, tableName, dialect);
        }
        List<String> indexes = new ArrayList<>();
        String sql = "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexdef = rs.getString("indexdef");
                    if (indexdef != null) {
                        // Strip leading "CREATE [UNIQUE] INDEX " and re-emit with IF NOT EXISTS
                        String result = indexdef.replaceFirst("^CREATE (UNIQUE )?INDEX ", "CREATE $1INDEX IF NOT EXISTS ");
                        indexes.add(result);
                    }
                }
            }
        }
        return indexes;
    }

    static List<String> readMySqlFamilyIndexes(Connection conn, String tableName, DatabaseDialect dialect)
            throws SQLException {
        record IndexParts(boolean unique, SortedMap<Short, String> columns) {}
        Map<String, IndexParts> byName = new TreeMap<>();
        try (var statement = conn.prepareStatement("SELECT index_name, non_unique, seq_in_index, column_name, "
                + "sub_part, collation "
                + "FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? "
                + "ORDER BY index_name, seq_in_index")) {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString("index_name");
                String column = rows.getString("column_name");
                if (name == null || "PRIMARY".equalsIgnoreCase(name)) {
                    continue;
                }
                if (column == null) {
                    throw new SQLException(dialect.id() + " expression index cannot be serialized safely: " + name);
                }
                name = SqlIdentifiers.requireIdentifier(name.toLowerCase(Locale.ROOT), "index");
                column = SqlIdentifiers.requireIdentifier(column.toLowerCase(Locale.ROOT), "index column");
                boolean unique = !rows.getBoolean("non_unique");
                short position = rows.getShort("seq_in_index");
                int prefixLength = rows.getInt("sub_part");
                boolean hasPrefix = !rows.wasNull();
                String direction = rows.getString("collation");
                String columnSql = column + (hasPrefix ? "(" + prefixLength + ")" : "")
                        + ("D".equalsIgnoreCase(direction) ? " DESC" : "");
                IndexParts parts = byName.computeIfAbsent(name,
                        ignored -> new IndexParts(unique, new TreeMap<>()));
                if (parts.unique() != unique) {
                    throw new SQLException(dialect.id() + " returned inconsistent uniqueness for index: " + name);
                }
                parts.columns().put(position, columnSql);
            }
            }
        }
        List<String> indexes = new ArrayList<>();
        String ifNotExists = dialect == DatabaseDialect.MARIADB ? " IF NOT EXISTS" : "";
        byName.forEach((name, parts) -> indexes.add("CREATE " + (parts.unique() ? "UNIQUE " : "")
                + "INDEX" + ifNotExists + " " + name.toLowerCase(Locale.ROOT) + " ON " + tableName + " ("
                + String.join(", ", parts.columns().values()) + ")"));
        return indexes;
    }

    private static String buildCreateSql(String tableName, List<Map<String, String>> columns, List<String> pkCols) {
        if (columns.isEmpty()) return null;
        String cols = columns.stream()
                .map(c -> c.get("name") + " " + c.get("type"))
                .collect(Collectors.joining(", "));
        if (!pkCols.isEmpty()) {
            cols += ", PRIMARY KEY (" + String.join(", ", pkCols) + ")";
        }
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (" + cols + ")";
    }

    private static String buildCreateType(String typeName, int size, Integer scale, String nullable,
                                          String columnDefault,
                                          boolean autoIncrement, boolean primaryKey, DatabaseDialect dialect) {
        if (dialect != DatabaseDialect.POSTGRESQL) {
            return buildDefinition(typeName, size, scale, nullable, columnDefault, autoIncrement, dialect);
        }
        if (PkIdentity.isSequenceBackedInteger(typeName, columnDefault, autoIncrement)) {
            return PkIdentity.createType(typeName, nullable, columnDefault, autoIncrement, primaryKey);
        }
        String base = switch (typeName) {
            case "VARCHAR" -> size > 0 && size < 10_000 ? "VARCHAR(" + size + ")" : "TEXT";
            case "BIGSERIAL", "SERIAL" -> typeName;
            case "INT2" -> "SMALLINT";
            case "VECTOR" -> "vector(" + size + ")";
            case "NUMERIC", "DECIMAL" -> numericType(size, scale);
            default -> typeName;
        };
        base += nullable;
        if (columnDefault != null && !columnDefault.contains("nextval(")) {
            base += " DEFAULT " + columnDefault;
        }
        return base;
    }

    /**
     * Produces a portable SQL column definition from JDBC metadata, including the DEFAULT
     * clause when present. Sequence-backed defaults (nextval) are omitted — those columns
     * are PKs and are never added via ALTER TABLE.
     */
    private static String buildDefinition(String typeName, int size, Integer scale, String nullable,
                                          String columnDefault, boolean autoIncrement, DatabaseDialect dialect) {
        String base = switch (typeName) {
            case "VARCHAR", "CHARACTER VARYING" ->
                    size > 0 && size < 10_000 ? "VARCHAR(" + size + ")" + nullable : "TEXT" + nullable;
            case "TEXT", "JSONB", "JSON", "BYTEA",
                 "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE",
                 "TIMESTAMP", "DATE", "BOOLEAN", "BIGINT",
                 "INTEGER", "INT4", "INT8", "BIGSERIAL", "SERIAL",
                 "FLOAT4", "FLOAT8", "DOUBLE PRECISION" ->
                    typeName + nullable;
            case "NUMERIC", "DECIMAL" -> numericType(size, scale) + nullable;
            case "INT2" -> "SMALLINT" + nullable;
            case "VECTOR" -> "vector(" + size + ")" + nullable;
            default -> typeName + nullable;
        };
        if (columnDefault != null && !columnDefault.contains("nextval(")) {
            base += " DEFAULT " + columnDefault;
        }
        if (dialect != DatabaseDialect.POSTGRESQL && autoIncrement) {
            base += " AUTO_INCREMENT";
        }
        return base;
    }

    private static String numericType(int precision, Integer scale) {
        if (precision <= 0 || precision > 1_000) {
            return "NUMERIC";
        }
        return scale == null ? "NUMERIC(" + precision + ")"
                : "NUMERIC(" + precision + "," + scale + ")";
    }

    private static void writeJson(Map<String, Object> tables, Path outputPath, DatabaseDialect dialect)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode preservedChanges = null;
        if (outputPath.toFile().isFile()) {
            preservedChanges = mapper.readTree(outputPath.toFile()).get("changes");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("_comment",
                "AUTO-GENERATED by SchemaSnapshotWriter — do not edit by hand. " +
                "The serializer preserves the hand-authored ordered changes array. " +
                "Used by SchemaSynchronizer on every startup to verify and fix the live DB additively.");
        root.put("formatVersion", SchemaDefinition.CURRENT_FORMAT_VERSION);
        root.put("dialect", dialect.id());
        root.set("tables", mapper.valueToTree(tables));
        if (preservedChanges != null) {
            root.set("changes", preservedChanges);
        }

        writeAtomically(mapper, root, outputPath);
    }

    static void restoreIdentityInJson(Path outputPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode root = mapper.readTree(outputPath.toFile());
        JsonNode tables = root.get("tables");
        if (tables == null || !tables.isObject()) {
            return;
        }
        tables.fields().forEachRemaining(entry -> {
            if (!(entry.getValue() instanceof ObjectNode table)) {
                return;
            }
            if (!table.hasNonNull("createSql")) {
                return;
            }
            String sql = PkIdentity.restoreCreateSql(table.get("createSql").asText());
            table.put("createSql", sql);
            JsonNode cols = table.get("columns");
            if (cols == null || !cols.isArray()) {
                return;
            }
            for (JsonNode col : cols) {
                if (col instanceof ObjectNode colNode && colNode.has("name") && colNode.has("definition")) {
                    colNode.put("definition", PkIdentity.restoreIdColumnDefinition(
                            colNode.get("name").asText(), colNode.get("definition").asText(), sql));
                }
            }
        });
        writeAtomically(mapper, root, outputPath);
    }

    private static void writeAtomically(ObjectMapper mapper, JsonNode root, Path outputPath) throws Exception {
        Path absolute = outputPath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("output path has no parent: " + outputPath);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
