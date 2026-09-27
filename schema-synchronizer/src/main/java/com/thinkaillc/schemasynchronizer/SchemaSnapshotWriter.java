// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * Command-line entry point; see the class documentation for the arguments.
     *
     * @throws SchemaDefinitionException for bad arguments or a schema that cannot be declared
     * @throws SchemaDatabaseException when a catalog read fails
     * @throws SchemaSynchronizationException when the output cannot be written
     */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception failure) {
            throw SchemaExceptions.translate(failure);
        }
    }

    private static void run(String[] args) throws Exception {
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
        String schema = SqlIdentifiers.requireIdentifierPreservingCase(args[3], "schema");
        Path outputPath = Paths.get(args[4]);

        log.info("[SchemaSnapshotWriter] Connecting to source database");
        try (Connection conn = CliCredentials.connect(url, user, password)) {
            DatabaseDialect dialect = DatabaseDialect.detect(conn.getMetaData());
            log.info("[SchemaSnapshotWriter] Detected {} {}", dialect.id(),
                    conn.getMetaData().getDatabaseProductVersion());
            Map<String, Object> tables = buildSnapshot(conn.getMetaData(), conn, namespace(schema, dialect), dialect);
            writeJson(tables, outputPath, dialect);
        }

        log.info("[SchemaSnapshotWriter] Done → {}", outputPath.toAbsolutePath());
        log.info("[SchemaSnapshotWriter] Review the diff, then commit schema-definition.json.");
    }

    /**
     * Programmatic serialize path for tests and library callers (no argv password). Reads every
     * table of {@code schema} over {@code conn} and writes the definition to {@code outputPath},
     * keeping an existing file's {@code changes}. Only reads the catalog; the connection's state is
     * not changed and it is not closed.
     *
     * @throws SchemaDefinitionException when an argument is invalid or a live name cannot be declared
     *         (not a plain identifier, a case-sensitive PostgreSQL/Oracle spelling, or two names
     *         that differ only in letter case)
     * @throws SchemaDatabaseException when a catalog read fails
     * @throws SchemaSynchronizationException when the output cannot be written
     */
    public static void writeSnapshot(Connection conn, String schema, Path outputPath) {
        if (conn == null) {
            throw new SchemaDefinitionException("connection is required");
        }
        if (outputPath == null) {
            throw new SchemaDefinitionException("output path is required");
        }
        try {
            String scoped = SqlIdentifiers.requireIdentifierPreservingCase(
                    schema, "schema", SqlIdentifiers.EXTENDED_MAX_LENGTH);
            DatabaseDialect dialect = DatabaseDialect.detect(conn.getMetaData());
            Map<String, Object> tables = buildSnapshot(conn.getMetaData(), conn, namespace(scoped, dialect), dialect);
            writeJson(tables, outputPath, dialect);
        } catch (Exception failure) {
            throw SchemaExceptions.translate(failure);
        }
    }

    /** The catalog spelling of the namespace (see {@link SqlIdentifiers#storedNamespace}). */
    private static String namespace(String schema, DatabaseDialect dialect) {
        return SqlIdentifiers.storedNamespace(dialect, schema);
    }

    private static Map<String, Object> buildSnapshot(DatabaseMetaData meta, Connection conn, String schema,
                                                     DatabaseDialect dialect)
            throws Exception {
        Map<String, Object> tables = new TreeMap<>();

        String catalog = dialect.metadataCatalog(conn, schema);
        if (dialect.usesCatalogNamespace()) {
            // Metadata reads are bound to the schema; SHOW CREATE TABLE and the driver use DATABASE().
            catalog = SchemaSynchronizer.mySqlCurrentDatabase(conn);
            if (!schema.equals(catalog)) {
                throw new IllegalStateException("Connected " + dialect.id() + " database '" + catalog
                        + "' does not match schema '" + schema + "' (compared case-sensitively)");
            }
        }
        String schemaPattern = dialect.metadataSchemaPattern(schema);
        SchemaSynchronizer.NameRules rules = SchemaSynchronizer.NameRules.read(conn, dialect);
        Map<String, String> liveTableByDeclared = new HashMap<>();

        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (!DatabaseDialect.isRequestedObject(rs, schemaPattern, null)) {
                    continue;
                }
                String metadataTable = rs.getString("TABLE_NAME");
                String lowerTable = metadataTable.toLowerCase(Locale.ROOT);
                if (EXCLUDE.contains(lowerTable) || SchemaSynchronizer.isIgnorableSchemaTable(lowerTable)) {
                    continue;
                }
                String tableName = declaredNameOf(dialect, metadataTable, "table", rules.tablesCaseSensitive());
                if (!liveTableByDeclared.containsKey(tableName)) {
                    liveTableByDeclared.put(tableName, metadataTable);
                } else {
                    throw caseCollision(dialect, "tables", liveTableByDeclared.get(tableName), metadataTable, tableName);
                }

                List<Map<String, String>> columns = new ArrayList<>();
                SortedMap<Short, String> pkBySequence = new TreeMap<>();
                List<Map<String, Object>> pendingCols = new ArrayList<>();
                Map<String, String> liveColumnByDeclared = new HashMap<>();
                Set<String> generatedDefaults = dialect == DatabaseDialect.MYSQL
                        ? mysqlGeneratedDefaultColumns(conn, catalog, metadataTable) : Set.of();
                Map<String, Integer> datetimePrecisions = dialect.isMySqlFamily()
                        ? mysqlDatetimePrecisions(conn, catalog, metadataTable) : Map.of();
                Map<String, String> dataTypes = dialect.isMySqlFamily()
                        ? mysqlDataTypes(conn, catalog, metadataTable) : Map.of();
                Map<String, String> onUpdates = dialect.isMySqlFamily()
                        ? mysqlOnUpdate(conn, catalog, metadataTable) : Map.of();
                Map<String, String> mariaDbDefaults = dialect == DatabaseDialect.MARIADB
                        ? mariaDbColumnDefaults(conn, catalog, metadataTable) : Map.of();
                Map<String, String> binaryDefaults = dialect.isMySqlFamily()
                        ? mysqlBinaryDefaults(conn, catalog, metadataTable, dialect == DatabaseDialect.MARIADB)
                        : Map.of();
                Map<String, String[]> columnClauses = dialect.isMySqlFamily()
                        ? mysqlColumnClauses(conn, catalog, metadataTable, dialect == DatabaseDialect.MARIADB)
                        : Map.of();

                try (ResultSet cols = meta.getColumns(catalog, schemaPattern, metadataTable, "%")) {
                    while (cols.next()) {
                        // Oracle JDBC exposes COLUMN_DEF as LONG — read it before any other column.
                        String colDefault = cols.getString("COLUMN_DEF");
                        if (!DatabaseDialect.isRequestedObject(cols, schemaPattern, metadataTable)) {
                            continue;
                        }
                        String liveColumn = cols.getString("COLUMN_NAME");
                        String colName = declaredNameOf(dialect, liveColumn, "column of table " + tableName,
                                rules.columnsCaseSensitive());
                        String previousColumn = liveColumnByDeclared.putIfAbsent(colName, liveColumn);
                        if (previousColumn != null) {
                            throw caseCollision(dialect, "columns of table " + tableName, previousColumn,
                                    liveColumn, colName);
                        }
                        if (mariaDbDefaults.containsKey(colName)) {
                            colDefault = mariaDbDefaults.get(colName);
                        }
                        String typeName = cols.getString("TYPE_NAME");
                        if (dialect == DatabaseDialect.POSTGRESQL) {
                            typeName = ColumnDefinitionParser.splitRenderedType(typeName).name();
                        }
                        typeName = typeName.toUpperCase(Locale.ROOT);
                        if (dialect.isMySqlFamily() && !typeName.startsWith("TINYINT")) {
                            String stored = mysqlTypeName(typeName, dataTypes.get(colName));
                            typeName = "TINYINT".equals(stored) ? "BOOLEAN" : stored;
                        }
                        if (dialect.isMySqlFamily()) {
                            Integer zerofillWidth = mysqlZerofillCustomWidth(dataTypes.get(colName));
                            if (zerofillWidth != null) {
                                throw new IllegalStateException("Cannot snapshot " + tableName + "." + colName
                                        + ": a ZEROFILL display width (" + zerofillWidth + ") cannot be declared");
                            }
                            typeName = mysqlZerofill(mysqlUnsignedTinyint1(typeName, dataTypes.get(colName)),
                                    dataTypes.get(colName));
                        }
                        int size        = cols.getInt("COLUMN_SIZE");
                        int scale       = cols.getInt("DECIMAL_DIGITS");
                        boolean scaleNull = cols.wasNull();
                        if (datetimePrecisions.containsKey(colName)) {
                            scale = datetimePrecisions.get(colName);
                            scaleNull = false;
                        }
                        String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "" : " NOT NULL";
                        if (SchemaSynchronizer.reportsNoDefaultAsNullText(dialect) && "NULL".equalsIgnoreCase(colDefault)) {
                            colDefault = null;
                        }
                        boolean autoInc = "YES".equalsIgnoreCase(cols.getString("IS_AUTOINCREMENT"));
                        if (dialect == DatabaseDialect.SQLSERVER && typeName.endsWith(" IDENTITY")) {
                            typeName = typeName.substring(0, typeName.length() - " IDENTITY".length()).trim();
                            autoInc = true;
                        }
                        if (colDefault != null) {
                            colDefault = colDefault.trim();
                        }
                        if (dialect == DatabaseDialect.MYSQL) {
                            colDefault = mysqlLiteralDefault(colDefault, typeName,
                                    generatedDefaults.contains(colName));
                        }
                        if (binaryDefaults.containsKey(colName)) {
                            colDefault = binaryDefaults.get(colName);
                            if (UNREADABLE_BINARY_DEFAULT.equals(colDefault)) {
                                throw new IllegalStateException("Cannot read the binary default of " + tableName
                                        + "." + colName + " exactly (SHOW CREATE TABLE is denied or has an "
                                        + "unexpected shape)");
                            }
                        }
                        if (dialect == DatabaseDialect.ORACLE && colDefault != null
                                && colDefault.toUpperCase(Locale.ROOT).contains("ISEQ$$")) {
                            autoInc = true;
                            colDefault = null;
                        }

                        if (dialect == DatabaseDialect.POSTGRESQL && "VECTOR".equals(typeName)) {
                            size = readVectorDimension(conn, schema, metadataTable, liveColumn);
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
                        if (!DatabaseDialect.isRequestedObject(pk, schemaPattern, metadataTable)) {
                            continue;
                        }
                        // getPrimaryKeys orders rows by COLUMN_NAME; KEY_SEQ is the key order.
                        pkBySequence.put(pk.getShort("KEY_SEQ"), declaredNameOf(dialect,
                                pk.getString("COLUMN_NAME"), "primary key column of table " + tableName,
                                rules.columnsCaseSensitive()));
                    }
                }
                List<String> pkCols = new ArrayList<>(pkBySequence.values());

                Map<String, String> typeSchemas = dialect == DatabaseDialect.POSTGRESQL
                        ? postgresForeignTypeSchemas(conn, schema, metadataTable) : Map.of();
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

                    String onUpdate = onUpdates.containsKey(colName) ? " ON UPDATE " + onUpdates.get(colName) : "";
                    String colDef = buildDefinition(typeName, size, scale, nullable, colDefault, autoInc, dialect)
                            + onUpdate;

                    Map<String, String> col = new LinkedHashMap<>();
                    col.put("name", colName);
                    col.put("definition", colDef);
                    columns.add(col);

                    String rawType = buildCreateType(
                            typeName, size, scale, nullable, colDefault, autoInc, isPk, dialect) + onUpdate;
                    String[] clauses = columnClauses.get(colName);
                    if (clauses != null) {
                        String type = columnType(typeName, size, scale, dialect);
                        rawType = type + clauses[0] + rawType.substring(type.length()) + clauses[1];
                    }
                    String liveColumn = liveColumnByDeclared.get(colName);
                    if (typeSchemas.containsKey(liveColumn)) {
                        String qualifier = SqlIdentifiers.quoteExact(dialect, typeSchemas.get(liveColumn)) + ".";
                        colDef = qualifier + colDef;
                        col.put("definition", colDef);
                        rawType = qualifier + rawType;
                    }
                    Map<String, String> rawCol = new LinkedHashMap<>();
                    rawCol.put("name", colName);
                    rawCol.put("type", rawType);
                    rawColumns.add(rawCol);
                }

                List<String> indexes = readIndexes(meta, conn, schema, metadataTable, dialect, rules);
                Set<String> indexNames = new HashSet<>();
                for (String index : indexes) {
                    String indexName = IndexDefinition.parse(index, dialect).name();
                    if (!indexNames.add(indexName)) {
                        throw new IllegalStateException("Cannot snapshot table " + tableName + ": two indexes "
                                + "differ only in letter case and would both be declared as " + indexName);
                    }
                }
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

    private static IllegalStateException caseCollision(DatabaseDialect dialect, String kind, String first,
                                                       String second, String declared) {
        return new IllegalStateException("Cannot snapshot " + kind + " " + SqlIdentifiers.quoteExact(dialect, first)
                + " and " + SqlIdentifiers.quoteExact(dialect, second)
                + ": they differ only in letter case and would both be declared as " + declared);
    }

    /**
     * Columns whose type lives outside pg_catalog and the snapshot schema (extension types such as
     * pgvector installed in {@code public}), with the type's schema. Synchronization resolves types
     * with search_path set to the target schema only, so a snapshot names these schema-qualified.
     */
    private static Map<String, String> postgresForeignTypeSchemas(Connection conn, String schema, String tableName)
            throws SQLException {
        String sql = "SELECT a.attname, n.nspname FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid "
                + "JOIN pg_namespace n ON n.oid = t.typnamespace "
                + "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped "
                + "AND n.nspname <> 'pg_catalog' AND n.nspname <> ?";
        Map<String, String> schemas = new HashMap<>();
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, schema) + "."
                    + SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, tableName));
            statement.setString(2, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    schemas.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return schemas;
    }

    /** {@code tableName} and {@code colName} are exact catalog spellings. */
    private static int readVectorDimension(Connection conn, String schema, String tableName, String colName)
            throws Exception {
        String sql = "SELECT format_type(a.atttypid, a.atttypmod) AS fmt " +
                "FROM pg_attribute a JOIN pg_type t ON a.atttypid = t.oid " +
                "WHERE t.typname = 'vector' AND a.attrelid = ?::regclass AND a.attname = ? AND a.attnum > 0";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, schema) + "."
                    + SqlIdentifiers.quoteExact(DatabaseDialect.POSTGRESQL, tableName));
            stmt.setString(2, colName);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Integer dimension = ColumnDefinitionParser.vectorDimension(rs.getString("fmt"));
                    if (dimension != null) {
                        return dimension;
                    }
                }
            }
        }
        throw new IllegalStateException("Could not determine vector dimension for "
                + schema + "." + tableName + "." + colName);
    }

    /**
     * How a catalog reader names objects in the CREATE INDEX it renders. A snapshot writes declared
     * names (see {@link #declaredNameOf}), quoted, rejecting spellings the server's {@code rules} make
     * impossible to declare. A live reading keeps the exact catalog spelling of the index and table; a
     * column is written in its folded form when the backend compares column names case-insensitively
     * or the stored spelling is already the folded one, so it compares equal to the same column in a
     * declared index key.
     */
    record IndexNaming(boolean snapshot, SchemaSynchronizer.NameRules rules) {
        static IndexNaming snapshot(SchemaSynchronizer.NameRules rules) {
            return new IndexNaming(true, rules);
        }

        static IndexNaming live(SchemaSynchronizer.NameRules rules) {
            return new IndexNaming(false, rules);
        }

        String table(DatabaseDialect dialect, String liveName) {
            return snapshot
                    ? SqlIdentifiers.quote(dialect, declaredNameOf(dialect, liveName, "table", rules.tablesCaseSensitive()))
                    : SqlIdentifiers.quoteExact(dialect, liveName);
        }

        String index(DatabaseDialect dialect, String liveName) {
            return snapshot
                    ? SqlIdentifiers.quote(dialect, declaredNameOf(dialect, liveName, "index", rules.indexesCaseSensitive()))
                    : SqlIdentifiers.quoteExact(dialect, liveName);
        }

        String column(DatabaseDialect dialect, String liveName) {
            if (snapshot) {
                return SqlIdentifiers.quote(dialect,
                        declaredNameOf(dialect, liveName, "index column", rules.columnsCaseSensitive()));
            }
            boolean foldable = SqlIdentifiers.isIdentifier(liveName) && (!rules.columnsCaseSensitive()
                    || liveName.equals(SqlIdentifiers.storedForm(dialect, liveName)));
            return foldable ? SqlIdentifiers.quote(dialect, liveName) : SqlIdentifiers.quoteExact(dialect, liveName);
        }
    }

    /**
     * The declared (lower-case) name a snapshot writes for a live object. The name must be a plain
     * identifier, and where the server compares this kind of name case-sensitively
     * ({@code caseSensitive}: always on PostgreSQL and Oracle; MySQL/MariaDB tables with
     * {@code lower_case_table_names=0}; SQL Server case-sensitive collations) its stored spelling must be
     * the one a declared name produces, since only that spelling round-trips through a declaration.
     */
    static String declaredNameOf(DatabaseDialect dialect, String liveName, String kind, boolean caseSensitive) {
        if (liveName == null || !SqlIdentifiers.isIdentifier(liveName)) {
            throw new IllegalStateException("Cannot snapshot " + kind + " "
                    + (liveName == null ? "null" : SqlIdentifiers.quoteExact(dialect, liveName))
                    + ": only names matching [A-Za-z_][A-Za-z0-9_]* can be declared");
        }
        if (caseSensitive && !liveName.equals(SqlIdentifiers.storedForm(dialect, liveName))) {
            throw new IllegalStateException("Cannot snapshot " + kind + " "
                    + SqlIdentifiers.quoteExact(dialect, liveName) + ": this " + dialect.id()
                    + " database compares " + kind.replaceFirst(" of table .*$", "")
                    + " names case-sensitively and a declared name is always "
                    + SqlIdentifiers.quote(dialect, liveName) + ", so this spelling cannot be declared; rename it");
        }
        return liveName.toLowerCase(Locale.ROOT);
    }

    private static List<String> readIndexes(DatabaseMetaData meta, Connection conn, String schema, String liveTable,
                                            DatabaseDialect dialect, SchemaSynchronizer.NameRules rules)
            throws Exception {
        if (dialect == DatabaseDialect.POSTGRESQL) {
            return readPostgresIndexes(conn, schema, liveTable);
        }
        if (dialect.isMySqlFamily()) {
            return readMySqlFamilyIndexes(conn, schema, liveTable, dialect, IndexNaming.snapshot(rules));
        }
        return readJdbcIndexes(meta, dialect, dialect.metadataCatalog(conn, schema),
                dialect.metadataSchemaPattern(schema), liveTable, IndexNaming.snapshot(rules));
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

    /**
     * Plain column indexes of {@code liveTable} (its exact catalog spelling) from
     * {@link DatabaseMetaData#getIndexInfo}, skipping the primary key, UNIQUE-constraint indexes,
     * and indexes a column list cannot express. Names are rendered per {@code naming}.
     */
    static List<String> readJdbcIndexes(DatabaseMetaData meta, DatabaseDialect dialect, String catalog,
                                        String schemaPattern, String liveTable, IndexNaming naming)
            throws SQLException {
        Set<String> skipIndexes = new HashSet<>();
        try (ResultSet rows = meta.getPrimaryKeys(catalog, schemaPattern, liveTable)) {
            while (rows.next()) {
                if (!DatabaseDialect.isRequestedObject(rows, schemaPattern, liveTable)) {
                    continue;
                }
                String pkName = rows.getString("PK_NAME");
                if (pkName != null && !pkName.isBlank()) {
                    skipIndexes.add(pkName);
                }
            }
        }
        skipIndexes.addAll(uniqueConstraintIndexNames(meta.getConnection(), dialect, catalog, schemaPattern,
                liveTable));
        skipIndexes.addAll(complexIndexNames(meta.getConnection(), dialect, catalog, schemaPattern, liveTable));
        record IndexParts(boolean unique, SortedMap<Short, String> columns) {}
        Map<String, IndexParts> byName = new TreeMap<>();
        // approximate=true: ojdbc otherwise runs ANALYZE TABLE (needs privileges and rewrites optimizer stats).
        try (ResultSet rows = meta.getIndexInfo(catalog, schemaPattern, liveTable, false, true)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                short type = rows.getShort("TYPE");
                if (name == null || type == DatabaseMetaData.tableIndexStatistic || skipIndexes.contains(name)) {
                    continue;
                }
                String column = rows.getString("COLUMN_NAME");
                if (column == null) {
                    continue;
                }
                boolean unique = !rows.getBoolean("NON_UNIQUE");
                short position = rows.getShort("ORDINAL_POSITION");
                String direction = rows.getString("ASC_OR_DESC");
                String columnSql = naming.column(dialect, column) + ("D".equalsIgnoreCase(direction) ? " DESC" : "");
                IndexParts parts = byName.computeIfAbsent(name, ignored -> new IndexParts(unique, new TreeMap<>()));
                if (parts.unique() != unique) {
                    throw new SQLException(dialect.id() + " returned inconsistent uniqueness for index: " + name);
                }
                parts.columns().put(position, columnSql);
            }
        }
        List<String> indexes = new ArrayList<>();
        String tableSql = naming.table(dialect, liveTable);
        byName.forEach((name, parts) -> {
            if (parts.columns().isEmpty()) {
                return;
            }
            indexes.add("CREATE " + (parts.unique() ? "UNIQUE " : "")
                    + "INDEX " + naming.index(dialect, name) + " ON " + tableSql + " ("
                    + String.join(", ", parts.columns().values()) + ")");
        });
        return indexes;
    }

    /**
     * Index names that back UNIQUE constraints (not free-standing CREATE UNIQUE INDEX).
     * Excluding them avoids false "drop index" drift when the constraint is managed via CREATE TABLE
     * or change sets. {@code tableName} and {@code schemaPattern} are exact catalog spellings, and the
     * returned names are exact too.
     */
    static Set<String> uniqueConstraintIndexNames(Connection conn, DatabaseDialect dialect, String catalog,
                                                  String schemaPattern, String tableName) throws SQLException {
        if (conn == null || (dialect != DatabaseDialect.SQLSERVER && dialect != DatabaseDialect.ORACLE)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        if (dialect == DatabaseDialect.SQLSERVER) {
            String sql = "SELECT kc.name FROM sys.key_constraints kc "
                    + "INNER JOIN sys.tables t ON t.object_id = kc.parent_object_id "
                    + "INNER JOIN sys.schemas s ON s.schema_id = t.schema_id "
                    + "WHERE kc.type = 'UQ' AND t.name = ? AND s.name = ?";
            try (var statement = conn.prepareStatement(sql)) {
                statement.setString(1, tableName);
                statement.setString(2, schemaPattern == null ? "dbo" : schemaPattern);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String name = rows.getString(1);
                        if (name != null) {
                            names.add(name);
                        }
                    }
                }
            }
            return names;
        }
        // The backing index may be named differently from the constraint (USING INDEX / pre-existing index).
        String sql = "SELECT constraint_name, index_name FROM all_constraints "
                + "WHERE ((constraint_type = 'U') OR (constraint_type = 'P' AND index_name IS NOT NULL)) "
                + "AND table_name = ? AND owner = ?";
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, schemaPattern == null ? tableName : schemaPattern);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (int column = 1; column <= 2; column++) {
                        String name = rows.getString(column);
                        if (name != null) {
                            names.add(name);
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * Indexes that {@link DatabaseMetaData#getIndexInfo} cannot reconstruct as a plain column-list
     * CREATE INDEX: SQL Server filtered, INCLUDE, clustered (non-PK), columnstore, XML, spatial;
     * Oracle function-based (including DESC), bitmap, domain. Omit them so serialize/sync does not
     * invent incomplete DDL.
     */
    static Set<String> complexIndexNames(Connection conn, DatabaseDialect dialect, String catalog,
                                         String schemaPattern, String tableName) throws SQLException {
        if (conn == null || (dialect != DatabaseDialect.SQLSERVER && dialect != DatabaseDialect.ORACLE)) {
            return Set.of();
        }
        String sql = dialect == DatabaseDialect.SQLSERVER
                ? "SELECT i.name FROM sys.indexes i "
                        + "INNER JOIN sys.tables t ON t.object_id = i.object_id "
                        + "INNER JOIN sys.schemas s ON s.schema_id = t.schema_id "
                        + "WHERE t.name = ? AND s.name = ? AND i.name IS NOT NULL AND i.is_primary_key = 0 "
                        + "AND (i.type <> 2 OR i.has_filter = 1 OR EXISTS ("
                        + "SELECT 1 FROM sys.index_columns ic "
                        + "WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id "
                        + "AND ic.is_included_column = 1))"
                : "SELECT index_name FROM all_indexes "
                        + "WHERE table_name = ? AND table_owner = ? AND index_type <> 'NORMAL'";
        String schema = dialect == DatabaseDialect.SQLSERVER
                ? (schemaPattern == null ? "dbo" : schemaPattern)
                : (schemaPattern == null ? tableName : schemaPattern);
        Set<String> names = new HashSet<>();
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static final Set<String> MYSQL_NUMERIC_TYPES = Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT",
            "INTEGER", "BIGINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL", "BIT", "BOOLEAN", "BOOL", "YEAR");

    /** MySQL columns whose default is an expression (EXTRA = DEFAULT_GENERATED), not a literal. */
    static Set<String> mysqlGeneratedDefaultColumns(Connection conn, String schema, String tableName) throws SQLException {
        Set<String> names = new HashSet<>();
        try (var statement = conn.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND extra LIKE '%DEFAULT_GENERATED%'")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static final java.util.regex.Pattern MYSQL_EXTRA_ON_UPDATE = java.util.regex.Pattern.compile(
            "(?i)\\bon update ([a-z_]+(?:\\(\\d*\\))?)");

    /** MySQL/MariaDB ON UPDATE expression by column, from information_schema EXTRA ({@code CURRENT_TIMESTAMP(3)}). */
    static Map<String, String> mysqlOnUpdate(Connection conn, String schema, String tableName) throws SQLException {
        Map<String, String> onUpdates = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT column_name, extra FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND LOWER(extra) LIKE '%on update%'")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    java.util.regex.Matcher onUpdate = MYSQL_EXTRA_ON_UPDATE.matcher(rows.getString(2));
                    if (onUpdate.find()) {
                        onUpdates.put(rows.getString(1).toLowerCase(Locale.ROOT),
                                onUpdate.group(1).toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return onUpdates;
    }

    /**
     * MariaDB information_schema COLUMN_DEFAULT by column: literals quoted, expressions bare,
     * {@code NULL} for none. MySQL Connector/J reports MariaDB defaults unquoted, so the
     * driver's COLUMN_DEF is not used.
     */
    static Map<String, String> mariaDbColumnDefaults(Connection conn, String schema, String tableName) throws SQLException {
        Map<String, String> defaults = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT column_name, column_default "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    defaults.put(rows.getString(1).toLowerCase(Locale.ROOT), rows.getString(2));
                }
            }
        }
        return defaults;
    }

    /**
     * Column attributes a declaration cannot carry, as CREATE TABLE clauses by column:
     * {@code [0]} goes after the type (COMPRESSED, CHARACTER SET/COLLATE when not the table's),
     * {@code [1]} at the end (INVISIBLE, COMMENT).
     */
    static Map<String, String[]> mysqlColumnClauses(Connection conn, String schema, String tableName,
                                                    boolean mariaDb) throws SQLException {
        Map<String, String[]> clauses = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT c.column_name, c.character_set_name, c.collation_name, "
                + "t.table_collation, c.column_comment, c.extra, c.column_type FROM information_schema.columns c "
                + "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
                + "WHERE c.table_schema = ? AND c.table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String[] column = mysqlColumnClauses(rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), rows.getString(6), rows.getString(7), mariaDb);
                    if (!column[0].isEmpty() || !column[1].isEmpty()) {
                        clauses.put(rows.getString(1).toLowerCase(Locale.ROOT), column);
                    }
                }
            }
        }
        return clauses;
    }

    private static final Pattern MYSQL_INVISIBLE_EXTRA = Pattern.compile("(?i)(?:^|[\\s,])INVISIBLE(?:$|[\\s,])");

    static String[] mysqlColumnClauses(String charset, String collation, String tableCollation, String comment,
                                       String extra, String columnType, boolean mariaDb) {
        StringBuilder afterType = new StringBuilder();
        if (mariaDb && columnType != null && MARIADB_COMPRESSED_COMMENT.matcher(columnType).find()) {
            afterType.append(" COMPRESSED");
        }
        if (charset != null && collation != null && !collation.equals(tableCollation)) {
            afterType.append(" CHARACTER SET ").append(charset).append(" COLLATE ").append(collation);
        }
        StringBuilder trailing = new StringBuilder();
        if (extra != null && MYSQL_INVISIBLE_EXTRA.matcher(extra).find()) {
            trailing.append(" INVISIBLE");
        }
        if (comment != null && !comment.isEmpty()) {
            trailing.append(" COMMENT '").append(comment.replace("\\", "\\\\").replace("'", "''")).append("'");
        }
        return new String[] {afterType.toString(), trailing.toString()};
    }

    /** MySQL/MariaDB information_schema COLUMN_TYPE by column (lower case, e.g. {@code tinyint(1) unsigned}). */
    static Map<String, String> mysqlDataTypes(Connection conn, String schema, String tableName) throws SQLException {
        Map<String, String> types = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT column_name, column_type "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    types.put(rows.getString(1).toLowerCase(Locale.ROOT), rows.getString(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return types;
    }

    /** The versioned COMPRESSED comment MariaDB renders after the type (and MariaDB Connector/J in TYPE_NAME). */
    static final Pattern MARIADB_COMPRESSED_COMMENT = Pattern.compile("(?i)\\s*/\\*M?!\\d+\\s+COMPRESSED\\s*\\*/");

    /**
     * Connector/J reports TINYINT(1) as BIT and MariaDB Connector/J reports TINYINT(1) [UNSIGNED [ZEROFILL]]
     * as BOOLEAN; MySQL BOOLEAN is signed TINYINT(1). Returns the column type the server stores for
     * those, otherwise the driver's TYPE_NAME.
     */
    static String mysqlTypeName(String typeName, String columnType) {
        typeName = MARIADB_COMPRESSED_COMMENT.matcher(typeName).replaceAll("");
        String normalized = ColumnDefinitionParser.normalizeType(typeName);
        if ((!"BIT".equals(normalized) && !"BOOLEAN".equals(normalized)) || columnType == null
                || !columnType.startsWith("tinyint")) {
            return typeName;
        }
        String attributes = columnType.replaceFirst("^tinyint(?:\\(\\d+\\))?", "").trim().toUpperCase(Locale.ROOT);
        return attributes.isEmpty() ? "TINYINT" : "TINYINT " + attributes;
    }

    /**
     * Unsigned TINYINT(1) has no BOOLEAN spelling, so its display width must stay in the snapshot type:
     * a replayed {@code TINYINT UNSIGNED} would no longer read as BOOLEAN/BIT through the drivers.
     */
    static String mysqlUnsignedTinyint1(String typeName, String columnType) {
        return "tinyint(1) unsigned".equals(columnType) ? "TINYINT(1) UNSIGNED" : typeName;
    }

    private static final Pattern MYSQL_NUMERIC_ATTRIBUTES = Pattern.compile("^(.+?) (UNSIGNED(?: ZEROFILL)?)$");
    private static final Pattern MYSQL_ZEROFILL_COLUMN_TYPE = Pattern.compile("^[a-z]+(?:\\([\\d,]+\\))? unsigned zerofill$");

    private static final Pattern MYSQL_ZEROFILL_INTEGER = Pattern.compile(
            "^(tinyint|smallint|mediumint|int|bigint)\\((\\d+)\\) unsigned zerofill$");
    private static final Map<String, Integer> MYSQL_ZEROFILL_DEFAULT_WIDTH = Map.of(
            "tinyint", 3, "smallint", 5, "mediumint", 8, "int", 10, "bigint", 20);

    /**
     * The display width of a ZEROFILL integer when it is not the type's default: declarations cannot
     * spell it, so replaying or MODIFY COLUMN would change the zero padding. Null otherwise.
     */
    static Integer mysqlZerofillCustomWidth(String columnType) {
        Matcher matcher = columnType == null ? null : MYSQL_ZEROFILL_INTEGER.matcher(columnType);
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        int width = Integer.parseInt(matcher.group(2));
        return width == MYSQL_ZEROFILL_DEFAULT_WIDTH.get(matcher.group(1)) ? null : width;
    }

    /** MySQL Connector/J omits ZEROFILL from TYPE_NAME (MariaDB Connector/J reports it); COLUMN_TYPE has it. */
    static String mysqlZerofill(String typeName, String columnType) {
        return columnType != null && typeName.toUpperCase(Locale.ROOT).endsWith(" UNSIGNED")
                && MYSQL_ZEROFILL_COLUMN_TYPE.matcher(columnType).matches() ? typeName + " ZEROFILL" : typeName;
    }

    /**
     * MySQL/MariaDB fractional-second precision by column. Connector/J and MariaDB Connector/J
     * report DECIMAL_DIGITS as null for DATETIME/TIMESTAMP/TIME.
     */
    static Map<String, Integer> mysqlDatetimePrecisions(Connection conn, String schema, String tableName) throws SQLException {
        Map<String, Integer> precisions = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT column_name, datetime_precision "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "AND datetime_precision IS NOT NULL")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    precisions.put(rows.getString(1).toLowerCase(Locale.ROOT), rows.getInt(2));
                }
            }
        }
        return precisions;
    }

    /**
     * MySQL reports literal defaults unquoted ({@code new}, {@code 2020-01-01}); quote non-numeric
     * literals so they compare with declarations and replay as valid SQL.
     */
    static String mysqlLiteralDefault(String columnDefault, String typeName, boolean generated) {
        if (columnDefault == null) {
            return null;
        }
        String baseType = typeName.toUpperCase(Locale.ROOT).replaceAll("\\s+UNSIGNED.*$", "")
                .replaceAll("\\(.*$", "").trim();
        // Servers that do not flag DEFAULT_GENERATED still report CURRENT_TIMESTAMP on temporal columns;
        // on any other type it is the literal text 'CURRENT_TIMESTAMP'.
        boolean temporal = baseType.equals("DATETIME") || baseType.equals("TIMESTAMP");
        if (temporal && columnDefault.matches("(?i)CURRENT_TIMESTAMP(?:\\(\\d*\\))?")) {
            return columnDefault;
        }
        if (generated) {
            // MySQL requires parentheses around expression defaults and reports them escaped one level (\' and \\).
            String expression = doubleEscapedQuotes(unescapeOneLevel(columnDefault)).trim();
            boolean wrapped = !ColumnDefinitionParser.stripOuterParentheses(expression).equals(expression);
            return wrapped ? expression : "(" + expression + ")";
        }
        if (columnDefault.startsWith("'")) {
            return columnDefault;
        }
        if (MYSQL_NUMERIC_TYPES.contains(baseType)) {
            return columnDefault;
        }
        // MySQL reports binary literal defaults as hex (0x6162), which is itself a valid literal.
        if ((baseType.equals("BINARY") || baseType.equals("VARBINARY")) && columnDefault.matches("0x[0-9A-Fa-f]*")) {
            return columnDefault.length() == 2 ? "''" : columnDefault;
        }
        return "'" + columnDefault.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * Literal BINARY/VARBINARY defaults by column as {@code 0x…}, read from SHOW CREATE TABLE:
     * information_schema truncates them at the first zero byte (MySQL) or replaces invalid bytes
     * with {@code ?} (MariaDB before 11.8), and {@code DEFAULT(col)} needs a row, so it answers
     * NULL for NOT NULL columns of an empty table. Expression defaults are left to COLUMN_DEFAULT.
     */
    static Map<String, String> mysqlBinaryDefaults(Connection conn, String schema, String tableName, boolean mariaDb)
            throws SQLException {
        List<String> literalColumns = new ArrayList<>();
        try (var statement = conn.prepareStatement("SELECT column_name, column_default, extra "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "AND data_type IN ('binary', 'varbinary') AND column_default IS NOT NULL")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (isLiteralBinaryDefault(rows.getString(2), rows.getString(3), mariaDb)) {
                        literalColumns.add(rows.getString(1));
                    }
                }
            }
        }
        Map<String, String> defaults = new HashMap<>();
        binaryDefaultsFromCreateTable(conn, schema, tableName, literalColumns)
                .forEach((column, value) -> defaults.put(column.toLowerCase(Locale.ROOT), value));
        return defaults;
    }

    /** Whether a BINARY/VARBINARY COLUMN_DEFAULT is a literal (not an expression default). */
    static boolean isLiteralBinaryDefault(String columnDefault, String extra, boolean mariaDb) {
        if (columnDefault == null) {
            return false;
        }
        return mariaDb
                ? columnDefault.startsWith("'") || columnDefault.matches("(?i)x'[0-9a-f]*'")
                : !(extra == null ? "" : extra).toUpperCase(Locale.ROOT).contains("DEFAULT_GENERATED");
    }

    /** The exact defaults of {@code literalColumns}, keyed by the names as given, from SHOW CREATE TABLE. */
    static Map<String, String> binaryDefaultsFromCreateTable(Connection conn, String schema, String tableName,
                                                             List<String> literalColumns) throws SQLException {
        Map<String, String> defaults = new HashMap<>();
        if (literalColumns.isEmpty()) {
            return defaults;
        }
        byte[] createTable;
        try {
            createTable = mysqlCreateTableBytes(conn, schema, tableName);
        } catch (SQLException denied) {
            return unreadableBinaryDefaults(literalColumns, denied);
        }
        for (String column : literalColumns) {
            String exact = binaryDefaultInCreateTable(createTable, column);
            defaults.put(column, exact == null ? UNREADABLE_BINARY_DEFAULT : exact);
        }
        return defaults;
    }

    /**
     * SHOW CREATE TABLE with character_set_results=binary, the one server rendering that keeps
     * binary default bytes exactly on MySQL 8 and every MariaDB version; the session setting is
     * restored afterwards. A restore failure is suppressed onto the primary failure, or thrown
     * when the read itself succeeded: the session would otherwise keep returning binary results.
     */
    static byte[] mysqlCreateTableBytes(Connection conn, String schema, String tableName)
            throws SQLException {
        String saved;
        try (var statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SELECT @@SESSION.character_set_results")) {
            if (!rows.next()) {
                throw new SQLException("SELECT @@SESSION.character_set_results returned no row");
            }
            saved = rows.getString(1);
        }
        try (var statement = conn.createStatement()) {
            statement.execute("SET SESSION character_set_results = binary");
            Throwable primary = null;
            try (ResultSet rows = statement.executeQuery("SHOW CREATE TABLE " + backtickQuoted(schema) + "."
                    + backtickQuoted(tableName))) {
                if (!rows.next()) {
                    throw new SQLException("SHOW CREATE TABLE returned no row for " + tableName);
                }
                return requireBaseTable(rows.getBytes(2), tableName);
            } catch (SQLException | RuntimeException failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    statement.execute("SET SESSION character_set_results = "
                            + (saved == null ? "NULL" : "'" + saved.replace("'", "''") + "'"));
                } catch (SQLException | RuntimeException restoreFailure) {
                    if (primary == null) {
                        throw restoreFailure;
                    }
                    SchemaExceptions.suppress(primary, restoreFailure);
                }
            }
        }
    }

    /** Fails when DDL on {@code tableName} would hit a session TEMPORARY table instead of the base table. */
    static void requireNoTemporaryShadow(Connection conn, String schema, String tableName) throws SQLException {
        try (var statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SHOW CREATE TABLE " + backtickQuoted(schema) + "."
                     + backtickQuoted(tableName))) {
            if (rows.next()) {
                requireBaseTable(rows.getBytes(2), tableName);
            }
        } catch (SQLException missing) {
            // ER_NO_SUCH_TABLE: neither a base nor a TEMPORARY table has this name.
            if (missing.getErrorCode() != 1146) {
                throw missing;
            }
        }
    }

    private static final Pattern CREATE_TEMPORARY_TABLE = Pattern.compile("(?i)^\\s*CREATE\\s+TEMPORARY\\b");

    /** A session TEMPORARY table shadows the base table of the same name in SHOW CREATE TABLE and DDL. */
    static byte[] requireBaseTable(byte[] createTable, String tableName) {
        if (CREATE_TEMPORARY_TABLE.matcher(new String(createTable, StandardCharsets.ISO_8859_1)).find()) {
            throw new IllegalStateException("A TEMPORARY table named " + tableName + " shadows the base table in"
                    + " this session; drop it or run on a connection without it");
        }
        return createTable;
    }

    private static final Pattern CREATE_TABLE_BINARY_DEFAULT = Pattern.compile(
            "(?i)^\\s+(?:var)?binary\\(\\d+\\)(?:\\s+/\\*M?!\\d+\\s+COMPRESSED\\s*\\*/)?(?:\\s+(?:NOT\\s+)?NULL)?(?:\\s+INVISIBLE)?\\s+DEFAULT\\s+(?:_binary\\s*)?"
                    + "(0x[0-9A-F]+|x'[0-9A-F]*'|'(?:[^'\\\\]|''|\\\\.)*')");

    /**
     * The default of a BINARY/VARBINARY column in raw SHOW CREATE TABLE bytes as {@code 0x…}
     * (or {@code ''}), or null when the column line does not have that shape.
     */
    static String binaryDefaultInCreateTable(byte[] createTable, String column) {
        String ddl = new String(createTable, StandardCharsets.ISO_8859_1);
        String name = new String(column.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        String[] prefixes = {"`" + name.replace("`", "``") + "`", "\"" + name.replace("\"", "\"\"") + "\"", name};
        for (String line : ddl.split("\n")) {
            String trimmed = line.stripLeading();
            for (String prefix : prefixes) {
                if (trimmed.startsWith(prefix + " ")) {
                    Matcher literal = CREATE_TABLE_BINARY_DEFAULT.matcher(trimmed.substring(prefix.length()));
                    if (literal.find()) {
                        return binaryLiteralHex(literal.group(1));
                    }
                }
            }
        }
        return null;
    }

    private static String binaryLiteralHex(String literal) {
        StringBuilder hex = new StringBuilder();
        if (literal.startsWith("0x") || literal.startsWith("0X")) {
            hex.append(literal.substring(2));
        } else if (literal.charAt(0) == 'x' || literal.charAt(0) == 'X') {
            hex.append(literal, 2, literal.length() - 1);
        } else {
            String body = literal.substring(1, literal.length() - 1);
            for (int index = 0; index < body.length(); index++) {
                char current = body.charAt(index);
                if (current == '\'') {
                    index++;
                } else if (current == '\\') {
                    index++;
                    current = switch (body.charAt(index)) {
                        case '0' -> 0;
                        case 'b' -> '\b';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'Z' -> 0x1A;
                        default -> body.charAt(index);
                    };
                }
                hex.append(String.format("%02X", (int) current & 0xFF));
            }
        }
        return hex.isEmpty() ? "''" : "0x" + hex.toString().toUpperCase(Locale.ROOT);
    }

    /** Never equal to a declared default: the column stays pending and a snapshot refuses to write it. */
    static final String UNREADABLE_BINARY_DEFAULT = "<binary default not readable exactly>";

    /** A sync user without any privilege on the table (MySQL errors 1142/1143) cannot read its DDL. */
    static Map<String, String> unreadableBinaryDefaults(List<String> columns, SQLException failure)
            throws SQLException {
        if (failure.getErrorCode() != 1142 && failure.getErrorCode() != 1143) {
            throw failure;
        }
        Map<String, String> unreadable = new HashMap<>();
        for (String column : columns) {
            unreadable.put(column, UNREADABLE_BINARY_DEFAULT);
        }
        return unreadable;
    }

    private static String backtickQuoted(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String unescapeOneLevel(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length()) {
                current = text.charAt(++index);
            }
            result.append(current);
        }
        return result.toString();
    }

    /** Rewrites {@code \'} inside string literals as {@code ''}, which every SQL mode reads the same way. */
    static String doubleEscapedQuotes(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (quoted && current == '\\' && index + 1 < sql.length()) {
                char next = sql.charAt(++index);
                result.append(next == '\'' ? "''" : "\\" + next);
            } else if (quoted && current == '\'' && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                result.append("''");
                index++;
            } else {
                if (current == '\'') {
                    quoted = !quoted;
                }
                result.append(current);
            }
        }
        return result.toString();
    }

    /** A {@code pg_get_indexdef} result as a declared, schema-free, quoted CREATE INDEX IF NOT EXISTS. */
    static String portablePostgresIndex(String indexDefinition) {
        IndexDefinition parsed = IndexDefinition.parse(indexDefinition);
        return parsed.toSql(DatabaseDialect.POSTGRESQL, SqlIdentifiers.quote(DatabaseDialect.POSTGRESQL, parsed.table()));
    }

    static List<String> readMySqlFamilyIndexes(Connection conn, String schema, String tableName, DatabaseDialect dialect)
            throws SQLException {
        return readMySqlFamilyIndexes(conn, schema, tableName, dialect,
                IndexNaming.snapshot(new SchemaSynchronizer.NameRules(false, false, false)));
    }

    /** Column indexes of {@code tableName} (exact catalog spelling) from information_schema, named per {@code naming}. */
    static List<String> readMySqlFamilyIndexes(Connection conn, String schema, String tableName, DatabaseDialect dialect,
                                               IndexNaming naming) throws SQLException {
        record IndexParts(boolean unique, SortedMap<Short, String> columns) {}
        Map<String, IndexParts> byName = new TreeMap<>();
        try (var statement = conn.prepareStatement("SELECT index_name, non_unique, seq_in_index, column_name, "
                + "sub_part, collation "
                + "FROM information_schema.statistics WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY index_name, seq_in_index")) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
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
                boolean unique = !rows.getBoolean("non_unique");
                short position = rows.getShort("seq_in_index");
                int prefixLength = rows.getInt("sub_part");
                boolean hasPrefix = !rows.wasNull();
                String direction = rows.getString("collation");
                String columnSql = naming.column(dialect, column) + (hasPrefix ? "(" + prefixLength + ")" : "")
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
        String tableSql = naming.table(dialect, tableName);
        byName.forEach((name, parts) -> indexes.add("CREATE " + (parts.unique() ? "UNIQUE " : "")
                + "INDEX" + ifNotExists + " " + naming.index(dialect, name) + " ON " + tableSql + " ("
                + String.join(", ", parts.columns().values()) + ")"));
        return indexes;
    }

    /** CREATE TABLE for declared names, every identifier quoted in the dialect's style. */
    static String buildCreateSql(String tableName, List<Map<String, String>> columns, List<String> pkCols,
                                 DatabaseDialect dialect) {
        if (columns.isEmpty()) return null;
        String cols = columns.stream()
                .map(c -> SqlIdentifiers.quote(dialect, c.get("name")) + " " + c.get("type"))
                .collect(Collectors.joining(", "));
        if (!pkCols.isEmpty()) {
            cols += ", PRIMARY KEY (" + pkCols.stream().map(pk -> SqlIdentifiers.quote(dialect, pk))
                    .collect(Collectors.joining(", ")) + ")";
        }
        String ifNotExists = dialect.supportsCreateTableIfNotExists() ? " IF NOT EXISTS" : "";
        return "CREATE TABLE" + ifNotExists + " " + SqlIdentifiers.quote(dialect, tableName) + " (" + cols + ")";
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
            case "BIGSERIAL", "SERIAL" -> typeName;
            default -> columnType(typeName, size, scale, dialect);
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
    static String buildDefinition(String typeName, int size, Integer scale, String nullable,
                                  String columnDefault, boolean autoIncrement, DatabaseDialect dialect) {
        String type = columnType(typeName, size, scale, dialect);
        if (dialect == DatabaseDialect.ORACLE) {
            // Oracle grammar: type [DEFAULT expr | GENERATED … AS IDENTITY] [NOT NULL].
            if (autoIncrement) {
                type += " GENERATED BY DEFAULT AS IDENTITY";
            } else if (columnDefault != null) {
                type += " DEFAULT " + columnDefault;
            }
            return type + nullable;
        }
        String base = type + nullable;
        if (columnDefault != null && !columnDefault.contains("nextval(")) {
            base += " DEFAULT " + columnDefault;
        }
        if (autoIncrement) {
            if (dialect.isMySqlFamily()) {
                base += " AUTO_INCREMENT";
            } else if (dialect == DatabaseDialect.SQLSERVER) {
                base += " IDENTITY(1,1)";
            }
        }
        return base;
    }

    static String columnType(String typeName, int size, Integer scale, DatabaseDialect dialect) {
        // DECIMAL(p,s) UNSIGNED [ZEROFILL]: the precision goes before the attributes.
        Matcher attributes = MYSQL_NUMERIC_ATTRIBUTES.matcher(typeName);
        if (dialect.isMySqlFamily() && typeName.indexOf('(') < 0 && attributes.matches()) {
            return columnType(attributes.group(1), size, scale, dialect) + " " + attributes.group(2);
        }
        return switch (typeName) {
            case "VARCHAR", "CHARACTER VARYING", "VARCHAR2" -> portableVarchar(size, "", dialect, false);
            case "NVARCHAR", "NVARCHAR2" -> portableVarchar(size, "", dialect, true);
            case "VARBINARY" -> portableVarbinary(size, "", dialect);
            // Fixed-length and Oracle RAW types require their length; without it CHAR means CHAR(1).
            case "CHAR", "CHARACTER", "BPCHAR" -> sized("CHAR", size);
            case "NCHAR" -> sized("NCHAR", size);
            case "BINARY" -> sized("BINARY", size);
            case "BIT" -> dialect == DatabaseDialect.SQLSERVER ? "BIT" : sized("BIT", size);
            case "VARBIT" -> size > 0 && size < Integer.MAX_VALUE ? "VARBIT(" + size + ")" : "VARBIT";
            case "RAW" -> sized("RAW", size);
            // Oracle NUMBER without precision is unbounded; ANSI NUMERIC there means NUMBER(38,0).
            case "NUMERIC", "DECIMAL", "NUMBER" -> dialect == DatabaseDialect.ORACLE && size <= 0
                    ? "NUMBER" : numericType(size, scale);
            // Oracle REAL is FLOAT(63); bare FLOAT is FLOAT(126).
            case "FLOAT" -> dialect == DatabaseDialect.ORACLE && size > 0
                    && size != SchemaSynchronizer.ORACLE_FLOAT_MAX_PRECISION ? "FLOAT(" + size + ")" : typeName;
            case "INT2" -> "SMALLINT";
            case "VECTOR" -> "vector(" + size + ")";
            // Fractional-second precision; SQL Server legacy DATETIME has no precision argument.
            case "DATETIME2", "DATETIMEOFFSET" -> dialect == DatabaseDialect.SQLSERVER && scale != null
                    ? typeName + "(" + scale + ")" : typeName;
            case "TIME" -> (dialect == DatabaseDialect.SQLSERVER && scale != null)
                    || (dialect.isMySqlFamily() && scale != null && scale > 0)
                    || (dialect == DatabaseDialect.POSTGRESQL && scale != null && scale != 6)
                    ? typeName + "(" + scale + ")" : typeName;
            case "DATETIME", "TIMESTAMP" -> (dialect.isMySqlFamily() && scale != null && scale > 0)
                    || (dialect == DatabaseDialect.POSTGRESQL && scale != null && scale != 6)
                    ? typeName + "(" + scale + ")" : typeName;
            case "TIMESTAMPTZ", "TIMETZ" -> dialect == DatabaseDialect.POSTGRESQL && scale != null && scale != 6
                    ? typeName + "(" + scale + ")" : typeName;
            default -> typeName;
        };
    }

    private static String sized(String type, int size) {
        return size > 0 ? type + "(" + size + ")" : type;
    }

    private static String portableVarchar(int size, String nullable, DatabaseDialect dialect, boolean national) {
        String type = national ? "NVARCHAR" : "VARCHAR";
        if (dialect == DatabaseDialect.SQLSERVER && (size <= 0 || size >= 10_000)) {
            return type + "(MAX)" + nullable;
        }
        // Oracle MAX_STRING_SIZE=EXTENDED allows up to 32767.
        if (dialect == DatabaseDialect.ORACLE && national) {
            return (size > 0 && size <= 32_767 ? "NVARCHAR2(" + size + ")" : "NVARCHAR2(2000)") + nullable;
        }
        if (dialect == DatabaseDialect.ORACLE && !national) {
            return (size > 0 && size <= 32_767 ? "VARCHAR2(" + size + ")" : "VARCHAR2(4000)") + nullable;
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
