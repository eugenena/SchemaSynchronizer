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
 * The {@code -} password argument is required and reads {@code SCHEMA_DB_PASSWORD}
 * (literal passwords on argv are rejected). Or use {@code scripts/serialize-schema.sh}.
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
    private static final Set<String> EXCLUDE = Set.of(
            "flyway_schema_history",
            "schema_synchronizer_history",
            "msreplication_options",
            "spt_fallback_db",
            "spt_fallback_dev",
            "spt_fallback_usg",
            "spt_monitor",
            "spt_values");

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
                    "Usage: SchemaSnapshotWriter <jdbc-url> <user> - <schema> <output-path> "
                            + "or SchemaSnapshotWriter --restore-json [output-path]");
        }

        String url = args[0];
        String user = args[1];
        String password = CliCredentials.requirePasswordFromEnv(args[2]);
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

        String catalog = dialect.metadataCatalog(conn, schema);
        String schemaPattern = dialect.metadataSchemaPattern(schema);

        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (EXCLUDE.contains(tableName) || SchemaSynchronizer.isIgnorableSchemaTable(tableName)) {
                    continue;
                }
                String metadataTable = dialect.metadataObjectName(tableName);

                List<Map<String, String>> columns = new ArrayList<>();
                List<String> pkCols = new ArrayList<>();
                List<Map<String, Object>> pendingCols = new ArrayList<>();

                try (ResultSet cols = meta.getColumns(catalog, schemaPattern, metadataTable, "%")) {
                    while (cols.next()) {
                        // Oracle JDBC exposes COLUMN_DEF as LONG — read it before any other column.
                        String colDefault = cols.getString("COLUMN_DEF");
                        String colName  = cols.getString("COLUMN_NAME").toLowerCase();
                        String typeName = cols.getString("TYPE_NAME").toUpperCase();
                        int size        = cols.getInt("COLUMN_SIZE");
                        int scale       = cols.getInt("DECIMAL_DIGITS");
                        boolean scaleNull = cols.wasNull();
                        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "" : " NOT NULL";
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

                try (ResultSet pk = meta.getPrimaryKeys(catalog, schemaPattern, metadataTable)) {
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
                String createSql = buildCreateSql(tableName, rawColumns, pkCols, dialect);
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
        if (dialect == DatabaseDialect.POSTGRESQL) {
            return readPostgresIndexes(conn, schema, tableName);
        }
        if (dialect.isMySqlFamily()) {
            return readMySqlFamilyIndexes(conn, tableName, dialect);
        }
        return readJdbcIndexes(meta, dialect, dialect.metadataCatalog(conn, schema),
                dialect.metadataSchemaPattern(schema), tableName);
    }

    private static List<String> readPostgresIndexes(Connection conn, String schema, String tableName) throws Exception {
        List<String> indexes = new ArrayList<>();
        String sql = "SELECT index_class.relname AS indexname, "
                + "pg_get_indexdef(index_class.oid) AS indexdef, constraint_meta.contype AS constraint_type "
                + "FROM pg_class table_class "
                + "JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace "
                + "JOIN pg_index index_meta ON index_meta.indrelid = table_class.oid "
                + "JOIN pg_class index_class ON index_class.oid = index_meta.indexrelid "
                + "LEFT JOIN pg_constraint constraint_meta ON constraint_meta.conindid = index_class.oid "
                + "AND constraint_meta.contype IN ('p', 'u', 'x') "
                + "WHERE namespace.nspname = ? AND table_class.relname = ? "
                + "ORDER BY index_class.relname";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String constraintType = rs.getString("constraint_type");
                    if ("p".equals(constraintType)) {
                        continue;
                    }
                    if ("x".equals(constraintType)) {
                        throw new IllegalStateException("PostgreSQL EXCLUDE constraint backed by index '"
                                + rs.getString("indexname") + "' on " + schema + "." + tableName
                                + " cannot be serialized automatically; represent it as an ordered change set");
                    }
                    String indexdef = rs.getString("indexdef");
                    if (indexdef != null) {
                        indexes.add(portablePostgresIndex(indexdef));
                    }
                }
            }
        }
        return indexes;
    }

    static List<String> readJdbcIndexes(DatabaseMetaData meta, DatabaseDialect dialect, String catalog,
                                        String schemaPattern, String tableName) throws SQLException {
        String metadataTable = dialect.metadataObjectName(tableName);
        Set<String> primaryKeyIndexes = new HashSet<>();
        try (ResultSet rows = meta.getPrimaryKeys(catalog, schemaPattern, metadataTable)) {
            while (rows.next()) {
                String pkName = rows.getString("PK_NAME");
                if (pkName != null && !pkName.isBlank()) {
                    primaryKeyIndexes.add(pkName.toLowerCase(Locale.ROOT));
                }
            }
        }
        record IndexParts(boolean unique, SortedMap<Short, String> columns) {}
        Map<String, IndexParts> byName = new TreeMap<>();
        try (ResultSet rows = meta.getIndexInfo(catalog, schemaPattern, metadataTable, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                short type = rows.getShort("TYPE");
                if (name == null || type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                if (primaryKeyIndexes.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String column = rows.getString("COLUMN_NAME");
                if (column == null) {
                    continue;
                }
                name = SqlIdentifiers.requireIdentifier(name.toLowerCase(Locale.ROOT), "index");
                column = SqlIdentifiers.requireIdentifier(column.toLowerCase(Locale.ROOT), "index column");
                boolean unique = !rows.getBoolean("NON_UNIQUE");
                short position = rows.getShort("ORDINAL_POSITION");
                String direction = rows.getString("ASC_OR_DESC");
                String columnSql = column + ("D".equalsIgnoreCase(direction) ? " DESC" : "");
                IndexParts parts = byName.computeIfAbsent(name, ignored -> new IndexParts(unique, new TreeMap<>()));
                if (parts.unique() != unique) {
                    throw new SQLException(dialect.id() + " returned inconsistent uniqueness for index: " + name);
                }
                parts.columns().put(position, columnSql);
            }
        }
        List<String> indexes = new ArrayList<>();
        String canonicalTable = tableName.toLowerCase(Locale.ROOT);
        byName.forEach((name, parts) -> {
            if (parts.columns().isEmpty()) {
                return;
            }
            indexes.add("CREATE " + (parts.unique() ? "UNIQUE " : "")
                    + "INDEX " + name + " ON " + canonicalTable + " ("
                    + String.join(", ", parts.columns().values()) + ")");
        });
        return indexes;
    }

    static String portablePostgresIndex(String indexDefinition) {
        IndexDefinition parsed = IndexDefinition.parse(indexDefinition);
        return parsed.canonicalSql().replaceFirst(
                "^CREATE (UNIQUE )?INDEX ", "CREATE $1INDEX IF NOT EXISTS ");
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

    private static String buildCreateSql(String tableName, List<Map<String, String>> columns, List<String> pkCols,
                                         DatabaseDialect dialect) {
        if (columns.isEmpty()) return null;
        String cols = columns.stream()
                .map(c -> c.get("name") + " " + c.get("type"))
                .collect(Collectors.joining(", "));
        if (!pkCols.isEmpty()) {
            cols += ", PRIMARY KEY (" + String.join(", ", pkCols) + ")";
        }
        String ifNotExists = dialect.supportsCreateTableIfNotExists() ? " IF NOT EXISTS" : "";
        return "CREATE TABLE" + ifNotExists + " " + tableName + " (" + cols + ")";
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
            case "VARCHAR", "CHARACTER VARYING", "VARCHAR2" ->
                    portableVarchar(size, nullable, dialect, false);
            case "NVARCHAR", "NVARCHAR2" ->
                    portableVarchar(size, nullable, dialect, true);
            case "VARBINARY", "BINARY" ->
                    portableVarbinary(size, nullable, dialect);
            case "TEXT", "JSONB", "JSON", "BYTEA",
                 "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE",
                 "TIMESTAMP WITH LOCAL TIME ZONE",
                 "TIMESTAMP", "DATE", "BOOLEAN", "BIGINT",
                 "INTEGER", "INT", "INT4", "INT8", "BIGSERIAL", "SERIAL",
                 "FLOAT4", "FLOAT8", "DOUBLE PRECISION" ->
                    typeName + nullable;
            case "NUMERIC", "DECIMAL", "NUMBER" -> numericType(size, scale) + nullable;
            case "INT2" -> "SMALLINT" + nullable;
            case "VECTOR" -> "vector(" + size + ")" + nullable;
            default -> typeName + nullable;
        };
        if (columnDefault != null && !columnDefault.contains("nextval(")) {
            base += " DEFAULT " + columnDefault;
        }
        if (autoIncrement) {
            if (dialect.isMySqlFamily()) {
                base += " AUTO_INCREMENT";
            } else if (dialect == DatabaseDialect.SQLSERVER) {
                base += " IDENTITY(1,1)";
            } else if (dialect == DatabaseDialect.ORACLE) {
                base += " GENERATED BY DEFAULT AS IDENTITY";
            }
        }
        return base;
    }

    private static String portableVarchar(int size, String nullable, DatabaseDialect dialect, boolean national) {
        String type = national ? "NVARCHAR" : "VARCHAR";
        if (dialect == DatabaseDialect.SQLSERVER && (size <= 0 || size >= 10_000)) {
            return type + "(MAX)" + nullable;
        }
        if (dialect == DatabaseDialect.ORACLE && national) {
            return (size > 0 && size < 10_000 ? "NVARCHAR2(" + size + ")" : "NVARCHAR2(2000)") + nullable;
        }
        if (dialect == DatabaseDialect.ORACLE && !national) {
            return (size > 0 && size < 10_000 ? "VARCHAR2(" + size + ")" : "VARCHAR2(4000)") + nullable;
        }
        if (size > 0 && size < 10_000) {
            return type + "(" + size + ")" + nullable;
        }
        return national ? type + "(MAX)" + nullable : "TEXT" + nullable;
    }

    private static String portableVarbinary(int size, String nullable, DatabaseDialect dialect) {
        // JDBC reports VARBINARY(MAX) COLUMN_SIZE as Integer.MAX_VALUE / 2^31-1.
        if (dialect == DatabaseDialect.SQLSERVER && (size <= 0 || size >= 10_000)) {
            return "VARBINARY(MAX)" + nullable;
        }
        if (size > 0 && size < 10_000) {
            return "VARBINARY(" + size + ")" + nullable;
        }
        if (dialect == DatabaseDialect.POSTGRESQL) {
            return "BYTEA" + nullable;
        }
        return "VARBINARY(MAX)" + nullable;
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
