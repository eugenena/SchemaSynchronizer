package com.thinkai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 *     -Dexec.mainClass=com.thinkai.schema.SchemaSerializer \
 *     -Dexec.args="jdbc:postgresql://localhost:5432/jobs enaoumov '' public src/main/resources/schema-definition.json" \
 *     -Dexec.classpathScope=compile
 * </pre>
 * Or using the shortcut script: {@code scripts/serialize-schema.sh}
 *
 * <p>The generated file is committed to source control and used by {@link SchemaApplier}
 * on every startup to verify and fix the production schema additively.
 *
 * <p><b>What is captured:</b> every table in the configured schema, with every
 * column name and its SQL type + nullable flag as the {@code definition}. Also
 * captures {@code createSql} (full CREATE TABLE statement), all indexes from
 * {@code pg_indexes}, and correct vector dimensions via {@code pg_attribute}.
 *
 * <p><b>What is NOT captured:</b> sequences, constraints (FK, CHECK), functions, or triggers.
 * These belong in the ordered {@code changes} array, which the serializer preserves. Index capture
 * means dropping an index locally no-ops silently in prod — SchemaApplier logs
 * orphaned indexes as warnings when they differ from the serialized set.
 */
public class SchemaSerializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaSerializer.class);

    private static final String DEFAULT_URL      = "jdbc:postgresql://localhost:5432/jobs";
    private static final String DEFAULT_USER     = "enaoumov";
    private static final String DEFAULT_PASSWORD = "";

    /** Tables to exclude from snapshot (system / internal tables). */
    private static final Set<String> EXCLUDE = Set.of("flyway_schema_history", "thinkai_schema_history");

    public static void main(String[] args) throws Exception {
        String url      = args.length > 0 ? args[0] : DEFAULT_URL;
        String user     = args.length > 1 ? args[1] : DEFAULT_USER;
        String password = args.length > 2 ? args[2] : DEFAULT_PASSWORD;
        String schema   = args.length > 3 ? SqlIdentifiers.requireIdentifier(args[3], "schema") : "public";

        Path outputPath = Paths.get(args.length > 4 ? args[4] : "src/main/resources/schema-definition.json");

        if (args.length > 0 && "--restore-json".equals(args[0])) {
            restoreIdentityInJson(outputPath);
            log.info("[SchemaSerializer] Restored PK identity in {}", outputPath.toAbsolutePath());
            return;
        }

        log.info("[SchemaSerializer] Connecting to {}", url);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            Map<String, Object> tables = buildSnapshot(conn.getMetaData(), conn, schema);
            writeJson(tables, outputPath);
        }

        log.info("[SchemaSerializer] Done → {}", outputPath.toAbsolutePath());
        log.info("[SchemaSerializer] Review the diff, then commit schema-definition.json.");
    }

    private static Map<String, Object> buildSnapshot(DatabaseMetaData meta, Connection conn, String schema)
            throws Exception {
        Map<String, Object> tables = new TreeMap<>();

        try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME").toLowerCase();
                if (EXCLUDE.contains(tableName)) continue;

                List<Map<String, String>> columns = new ArrayList<>();
                List<String> pkCols = new ArrayList<>();
                List<Map<String, Object>> pendingCols = new ArrayList<>();

                try (ResultSet cols = meta.getColumns(null, schema, tableName, "%")) {
                    while (cols.next()) {
                        String colName  = cols.getString("COLUMN_NAME").toLowerCase();
                        String typeName = cols.getString("TYPE_NAME").toUpperCase();
                        int size        = cols.getInt("COLUMN_SIZE");
                        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "" : " NOT NULL";
                        String colDefault = cols.getString("COLUMN_DEF");
                        boolean autoInc = "YES".equalsIgnoreCase(cols.getString("IS_AUTOINCREMENT"));

                        if ("VECTOR".equals(typeName)) {
                            size = readVectorDimension(conn, schema, tableName, colName);
                        }

                        Map<String, Object> pending = new LinkedHashMap<>();
                        pending.put("name", colName);
                        pending.put("typeName", typeName);
                        pending.put("size", size);
                        pending.put("nullable", nullable);
                        pending.put("colDefault", colDefault);
                        pending.put("autoInc", autoInc);
                        pendingCols.add(pending);
                    }
                }

                try (ResultSet pk = meta.getPrimaryKeys(null, schema, tableName)) {
                    while (pk.next()) {
                        pkCols.add(pk.getString("COLUMN_NAME").toLowerCase());
                    }
                }

                List<Map<String, String>> rawColumns = new ArrayList<>();
                for (Map<String, Object> pending : pendingCols) {
                    String colName = (String) pending.get("name");
                    String typeName = (String) pending.get("typeName");
                    int size = (Integer) pending.get("size");
                    String nullable = (String) pending.get("nullable");
                    String colDefault = (String) pending.get("colDefault");
                    boolean autoInc = Boolean.TRUE.equals(pending.get("autoInc"));
                    boolean isPk = pkCols.contains(colName);

                    String colDef = buildDefinition(typeName, size, nullable, colDefault);

                    Map<String, String> col = new LinkedHashMap<>();
                    col.put("name", colName);
                    col.put("definition", colDef);
                    columns.add(col);

                    Map<String, String> rawCol = new LinkedHashMap<>();
                    rawCol.put("name", colName);
                    rawCol.put("type", buildCreateType(typeName, size, nullable, colDefault, autoInc, isPk));
                    rawColumns.add(rawCol);
                }

                List<String> indexes = readIndexes(conn, schema, tableName);
                String createSql = PkIdentity.restoreCreateSql(buildCreateSql(tableName, rawColumns, pkCols));
                for (Map<String, String> col : columns) {
                    col.put("definition", PkIdentity.restoreIdColumnDefinition(
                            col.get("name"), col.get("definition"), createSql));
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
        return 768; // default fallback
    }

    private static List<String> readIndexes(Connection conn, String schema, String tableName) throws Exception {
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

    private static String buildCreateType(String typeName, int size, String nullable, String columnDefault,
                                          boolean autoIncrement, boolean primaryKey) {
        if (PkIdentity.isSequenceBackedInteger(typeName, columnDefault, autoIncrement)) {
            return PkIdentity.createType(typeName, nullable, columnDefault, autoIncrement, primaryKey);
        }
        String base = switch (typeName) {
            case "VARCHAR" -> size > 0 && size < 10_000 ? "VARCHAR(" + size + ")" : "TEXT";
            case "BIGSERIAL", "SERIAL" -> typeName;
            case "INT2" -> "SMALLINT";
            case "VECTOR" -> "vector(" + size + ")";
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
    private static String buildDefinition(String typeName, int size, String nullable, String columnDefault) {
        String base = switch (typeName) {
            case "VARCHAR", "CHARACTER VARYING" ->
                    size > 0 && size < 10_000 ? "VARCHAR(" + size + ")" + nullable : "TEXT" + nullable;
            case "TEXT", "JSONB", "JSON", "BYTEA",
                 "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE",
                 "TIMESTAMP", "DATE", "BOOLEAN", "BIGINT",
                 "INTEGER", "INT4", "INT8", "BIGSERIAL", "SERIAL",
                 "NUMERIC", "FLOAT4", "FLOAT8", "DOUBLE PRECISION" ->
                    typeName + nullable;
            case "INT2" -> "SMALLINT" + nullable;
            case "VECTOR" -> "vector(" + size + ")" + nullable;
            default -> typeName + nullable;
        };
        if (columnDefault != null && !columnDefault.contains("nextval(")) {
            return base + " DEFAULT " + columnDefault;
        }
        return base;
    }

    private static void writeJson(Map<String, Object> tables, Path outputPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode preservedChanges = null;
        if (outputPath.toFile().isFile()) {
            preservedChanges = mapper.readTree(outputPath.toFile()).get("changes");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("_comment",
                "AUTO-GENERATED by SchemaSerializer — do not edit by hand. " +
                "The serializer preserves the hand-authored ordered changes array. " +
                "Used by SchemaApplier on every startup to verify and fix the live DB additively.");
        root.set("tables", mapper.valueToTree(tables));
        if (preservedChanges != null) {
            root.set("changes", preservedChanges);
        }

        outputPath.toFile().getParentFile().mkdirs();
        mapper.writeValue(outputPath.toFile(), root);
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
        mapper.writeValue(outputPath.toFile(), root);
    }
}
