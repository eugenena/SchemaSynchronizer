package com.thinkai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a {@link SchemaDefinition} to a live Postgres database.
 *
 * <p><b>Auto-applied (non-destructive):</b> create table, add column, create index,
 * SET/DROP DEFAULT, safe type widenings, DROP NOT NULL.
 *
 * <p><b>Never auto-applied:</b> drop table/column/index, type narrowing,
 * SET NOT NULL without an explicit safe path (logged as pending manual SQL).
 */
public class SchemaApplier {

    private static final Logger log = LoggerFactory.getLogger(SchemaApplier.class);
    private static final Pattern CREATE_TABLE_TARGET = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?:(?:([a-zA-Z_][a-zA-Z0-9_]*)\\.)?([a-zA-Z_][a-zA-Z0-9_]*))\\s*\\(.*");
    private static final Pattern PRIMARY_KEY_COLUMNS = Pattern.compile(
            "(?is)\\bPRIMARY\\s+KEY\\s*\\(([^)]+)\\)");
    private static final Pattern INLINE_PRIMARY_KEY = Pattern.compile(
            "(?is)(?:\\(|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s+[^,]*?\\bPRIMARY\\s+KEY\\b");

    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final String classpathResource;
    private final SchemaApplierOptions options;
    private final ThreadLocal<List<String>> plannedSql = ThreadLocal.withInitial(ArrayList::new);

    public SchemaApplier(ObjectMapper objectMapper, DataSource dataSource) {
        this(objectMapper, dataSource, "/schema-definition.json", SchemaApplierOptions.defaults());
    }

    public SchemaApplier(ObjectMapper objectMapper, DataSource dataSource, String classpathResource) {
        this(objectMapper, dataSource, classpathResource, SchemaApplierOptions.defaults());
    }

    public SchemaApplier(ObjectMapper objectMapper, DataSource dataSource, String classpathResource,
                         SchemaApplierOptions options) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.classpathResource = classpathResource;
        this.options = options;
    }

    /** Load definition from classpath and apply. */
    public SchemaApplyResult applyFromClasspath() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            InputStream fromClass = getClass().getResourceAsStream(classpathResource);
            InputStream resource = fromClass != null ? fromClass
                    : Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(classpathResource.startsWith("/")
                            ? classpathResource.substring(1) : classpathResource);
            if (resource == null) {
                if (options.requireDefinition()) {
                    throw new IllegalStateException("Required schema definition is missing from classpath: "
                            + classpathResource);
                }
                log.warn("[SchemaApplier] No {} on classpath — schema management is inactive", classpathResource);
                return new SchemaApplyResult(0, 0, 0, 0, List.of(), List.of());
            }
            try (InputStream in = resource) {
                SchemaDefinition def = objectMapper.readValue(in, SchemaDefinition.class);
                return applySchemaWithResult(conn, def);
            }
        }
    }

    public void applySchema(Connection conn, SchemaDefinition def) throws Exception {
        applySchemaWithResult(conn, def);
    }

    public SchemaApplyResult applySchemaWithResult(Connection conn, SchemaDefinition def) throws Exception {
        requirePostgres(conn);
        boolean previousAutoCommit = conn.getAutoCommit();
        boolean ownsTransaction = previousAutoCommit;
        Savepoint savepoint = null;
        String previousSearchPath = null;
        ChangeSetExecutor executor = new ChangeSetExecutor();
        validateDeclarativeDefinition(def);
        List<SchemaDefinition.ChangeSet> allChanges = executor.validate(def.changes());
        plannedSql.set(new ArrayList<>());
        Exception primaryFailure = null;
        try {
            if (ownsTransaction) {
                conn.setAutoCommit(false);
            } else {
                savepoint = conn.setSavepoint("thinkai_schema_applier");
                previousSearchPath = readSearchPath(conn);
            }
            executeControl(conn, "SET LOCAL search_path TO " + options.schema());
            executeControl(conn, "SELECT pg_advisory_xact_lock(" + options.advisoryLockId() + ")");
            executor.validateHistory(conn, allChanges, options);
            ChangeSetExecutor.Result beforeChanges = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA), options);
            plannedSql.get().addAll(beforeChanges.plannedSql());
            DeclarativeResult declarative = applyDeclarativeSchema(conn, def);
            ChangeSetExecutor.Result afterChanges = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA), options);
            plannedSql.get().addAll(afterChanges.plannedSql());
            if (options.failOnPending() && !declarative.pendingSql().isEmpty()) {
                throw new IllegalStateException("unsafe or destructive schema differences require manual resolution: "
                        + String.join(" | ", declarative.pendingSql()));
            }
            if (options.dryRun()) {
                rollback(conn, ownsTransaction, savepoint);
            } else if (ownsTransaction) {
                conn.commit();
            } else {
                restoreSearchPath(conn, previousSearchPath);
                conn.releaseSavepoint(savepoint);
            }
            return new SchemaApplyResult(beforeChanges.applied() + afterChanges.applied(), declarative.tablesCreated(),
                    declarative.columnsAdded(), declarative.columnsAltered(), List.copyOf(plannedSql.get()),
                    declarative.pendingSql());
        } catch (Exception exception) {
            primaryFailure = exception;
            try {
                rollback(conn, ownsTransaction, savepoint);
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            plannedSql.remove();
            if (ownsTransaction) {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException restoreFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(restoreFailure);
                    } else {
                        throw restoreFailure;
                    }
                }
            }
        }
    }

    private void requirePostgres(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        if (product != null && !product.toLowerCase(Locale.ROOT).contains("postgresql")) {
            throw new IllegalStateException("SchemaApplier supports PostgreSQL only; connected to " + product);
        }
    }

    private List<SchemaDefinition.ChangeSet> changesForPhase(
            List<SchemaDefinition.ChangeSet> changes, SchemaDefinition.ChangeSet.Phase phase) {
        return changes.stream()
                .filter(change -> change.effectivePhase() == phase)
                .toList();
    }

    private void rollback(Connection conn, boolean ownsTransaction, Savepoint savepoint) throws SQLException {
        if (ownsTransaction) {
            conn.rollback();
        } else if (savepoint != null) {
            conn.rollback(savepoint);
        }
    }

    private String readSearchPath(Connection conn) throws SQLException {
        try (var statement = conn.createStatement(); var row = statement.executeQuery("SHOW search_path")) {
            if (!row.next()) {
                throw new SQLException("SHOW search_path returned no row");
            }
            return row.getString(1);
        }
    }

    private void restoreSearchPath(Connection conn, String searchPath) throws SQLException {
        try (var statement = conn.prepareStatement("SELECT set_config('search_path', ?, true)")) {
            statement.setString(1, searchPath);
            statement.execute();
        }
    }

    private DeclarativeResult applyDeclarativeSchema(Connection conn, SchemaDefinition def) throws Exception {
        if (def.tables() == null || def.tables().isEmpty()) {
            return new DeclarativeResult(0, 0, 0, List.of());
        }

        DatabaseMetaData meta = conn.getMetaData();
        Set<String> existingTables = getExistingTables(meta);

        int tablesCreated = 0;
        int columnsAdded = 0;
        int columnsAltered = 0;
        List<String> pendingSql = new ArrayList<>();

        for (Map.Entry<String, SchemaDefinition.TableDef> entry : def.tables().entrySet()) {
            String tableName = entry.getKey().toLowerCase(Locale.ROOT);
            SqlIdentifiers.requireIdentifier(tableName, "table");
            SchemaDefinition.TableDef tableDef = entry.getValue();
            Set<String> livePrimaryKeyColumns = existingTables.contains(tableName)
                    ? getLivePrimaryKeyColumns(meta, tableName) : Set.of();
            List<String> deferredNullabilitySql = new ArrayList<>();

            if (!existingTables.contains(tableName)) {
                if (tableDef.createSql() != null) {
                    NonDestructiveSqlPolicy.requireCreateTable(tableDef.createSql());
                    execute(conn, tableDef.createSql());
                    log.info("[SchemaApplier] Created table: {}", tableName);
                    tablesCreated++;
                    existingTables.add(tableName);
                } else {
                    log.warn("[SchemaApplier] Table '{}' missing but no createSql provided — skipping", tableName);
                    pendingSql.add("CREATE TABLE " + tableName
                            + " (...); -- pending: table missing and createSql is absent");
                    continue;
                }
            }

            if (tableDef.columns() != null) {
                Map<String, LiveColumn> liveColumns = getLiveColumns(meta, tableName);
                Set<String> targetColumns = new HashSet<>();

                for (SchemaDefinition.ColumnDef col : tableDef.columns()) {
                    String colName = col.name().toLowerCase(Locale.ROOT);
                    SqlIdentifiers.requireIdentifier(colName, "column");
                    targetColumns.add(colName);
                    if (!liveColumns.containsKey(colName)) {
                        if (col.definition() != null) {
                            ColumnDefinitionParser.parse(col.definition());
                            String sql = String.format(
                                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s",
                                    tableName, col.name(), col.definition()
                            );
                            execute(conn, sql);
                            log.info("[SchemaApplier] Added column {}.{}", tableName, col.name());
                            columnsAdded++;
                        } else {
                            pendingSql.add("ALTER TABLE " + tableName + " ADD COLUMN " + colName
                                    + " ...; -- pending: column definition is absent");
                        }
                        continue;
                    }

                    if (col.definition() == null || shouldSkipAlter(col.definition(), liveColumns.get(colName))) {
                        continue;
                    }
                    try {
                        ColumnSpec target = ColumnDefinitionParser.parse(col.definition());
                        NonDestructiveAlterPlanner.Plan plan =
                                NonDestructiveAlterPlanner.plan(tableName, col.name(), target, liveColumns.get(colName));
                        for (String sql : plan.applySql()) {
                            if (sql.endsWith(" DROP NOT NULL") && livePrimaryKeyColumns.contains(colName)) {
                                deferredNullabilitySql.add(terminated(sql));
                            } else {
                                execute(conn, sql);
                                log.info("[SchemaApplier] Altered {}.{}: {}", tableName, col.name(), sql);
                                columnsAltered++;
                            }
                        }
                        pendingSql.addAll(plan.pendingSql());
                    } catch (IllegalArgumentException ex) {
                        pendingSql.add("-- pending: cannot safely reconcile " + tableName + "." + colName
                                + ": " + ex.getMessage());
                    }
                }

                for (String existingCol : liveColumns.keySet().stream().sorted().toList()) {
                    if (!targetColumns.contains(existingCol)) {
                        pendingSql.add(String.format(
                                "ALTER TABLE %s DROP COLUMN %s;", tableName, existingCol
                        ));
                    }
                }
            }

            reconcilePrimaryKey(meta, tableName, tableDef, pendingSql);
            pendingSql.addAll(deferredNullabilitySql);
            reconcileIndexes(conn, tableName, tableDef, pendingSql);

            if (PkIdentity.createSqlWantsIdIdentity(tableDef.createSql())) {
                repairIdIdentity(conn, tableName);
            }
        }

        List<String> orphanedTables = detectOrphanedTables(def, existingTables);

        if (tablesCreated > 0 || columnsAdded > 0 || columnsAltered > 0) {
            log.info("[SchemaApplier] Applied: {} table(s) created, {} column(s) added, {} column alter(s)",
                    tablesCreated, columnsAdded, columnsAltered);
        } else {
            log.debug("[SchemaApplier] Schema is up to date — no changes needed");
        }

        pendingSql.addAll(orphanedTables);
        if (!pendingSql.isEmpty()) {
            log.warn("");
            log.warn("╔══════════════════════════════════════════════════════════════════╗");
            log.warn("║         PENDING MANUAL SCHEMA CHANGES DETECTED                  ║");
            log.warn("║  Destructive or unsafe diffs vs schema-definition.json          ║");
            log.warn("║  Run these manually if you intend them:                         ║");
            log.warn("╚══════════════════════════════════════════════════════════════════╝");
            log.warn("    BEGIN;");
            log.warn("    SET LOCAL search_path TO {};", options.schema());
            for (String sql : pendingSql) {
                log.warn("    {}", sql);
            }
            log.warn("    COMMIT;");
        }
        return new DeclarativeResult(tablesCreated, columnsAdded, columnsAltered, List.copyOf(pendingSql));
    }

    private record DeclarativeResult(int tablesCreated, int columnsAdded, int columnsAltered,
                                     List<String> pendingSql) {}

    /** Identity / serial columns must not get DROP DEFAULT from nextval noise. */
    public static boolean shouldSkipAlter(String definition, LiveColumn live) {
        if (definition == null) {
            return true;
        }
        String upper = definition.toUpperCase(Locale.ROOT);
        if (upper.contains("BIGSERIAL") || upper.matches("(?s).*\\bSERIAL\\b.*")
                || (upper.contains("GENERATED") && upper.contains("IDENTITY"))) {
            return true;
        }
        String liveDef = live.defaultExpr();
        return liveDef != null && liveDef.contains("nextval(");
    }

    public static boolean isIgnorableSchemaTable(String tableName) {
        if (tableName == null || tableName.isBlank()) return true;
        String t = tableName.toLowerCase(Locale.ROOT);
        return "flyway_schema_history".equals(t) || "thinkai_schema_history".equals(t);
    }

    public static boolean isIgnorableSchemaIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) return true;
        String i = indexName.toLowerCase(Locale.ROOT);
        return i.startsWith("flyway_schema_history_") || i.startsWith("thinkai_schema_history_");
    }

    private void validateDeclarativeDefinition(SchemaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("schema definition is null");
        }
        if (definition.tables() == null) {
            return;
        }
        for (Map.Entry<String, SchemaDefinition.TableDef> entry : definition.tables().entrySet()) {
            String table = SqlIdentifiers.requireIdentifier(entry.getKey(), "table");
            SchemaDefinition.TableDef tableDef = entry.getValue();
            if (tableDef == null) {
                throw new IllegalArgumentException("table definition is null: " + table);
            }
            if (tableDef.createSql() != null) {
                NonDestructiveSqlPolicy.requireCreateTable(tableDef.createSql());
                Matcher matcher = CREATE_TABLE_TARGET.matcher(tableDef.createSql());
                if (!matcher.matches()
                        || (matcher.group(1) != null && !options.schema().equalsIgnoreCase(matcher.group(1)))
                        || !table.equalsIgnoreCase(matcher.group(2))) {
                    throw new IllegalArgumentException("table createSql target does not match definition: " + table);
                }
            }
            Set<String> columns = new HashSet<>();
            Map<String, ColumnSpec> columnSpecs = new HashMap<>();
            if (tableDef.columns() != null) {
                for (SchemaDefinition.ColumnDef column : tableDef.columns()) {
                    if (column == null) {
                        throw new IllegalArgumentException("null column definition in table: " + table);
                    }
                    String name = SqlIdentifiers.requireIdentifier(column.name(), "column");
                    if (!columns.add(name)) {
                        throw new IllegalArgumentException("duplicate column definition: " + table + "." + name);
                    }
                    if (column.definition() != null) {
                        columnSpecs.put(name, ColumnDefinitionParser.parse(column.definition()));
                    }
                }
            }
            for (String primaryKeyColumn : primaryKeyColumns(tableDef.createSql())) {
                ColumnSpec spec = columnSpecs.get(primaryKeyColumn);
                if (spec != null && !spec.notNull()) {
                    throw new IllegalArgumentException("primary-key column must be declared NOT NULL: "
                            + table + "." + primaryKeyColumn);
                }
            }
            Set<String> indexes = new HashSet<>();
            if (tableDef.indexes() != null) {
                for (String sql : tableDef.indexes()) {
                    NonDestructiveSqlPolicy.requireCreateIndex(sql);
                    IndexDefinition index = IndexDefinition.parse(sql);
                    if ((index.schema() != null && !options.schema().equals(index.schema()))
                            || !table.equals(index.table())) {
                        throw new IllegalArgumentException("index target does not match table definition: "
                                + index.name());
                    }
                    if (!indexes.add(index.name())) {
                        throw new IllegalArgumentException("duplicate index definition: " + index.name());
                    }
                }
            }
        }
    }

    private void reconcileIndexes(Connection conn, String tableName, SchemaDefinition.TableDef tableDef,
                                  List<String> pendingSql) throws SQLException {
        Map<String, String> live = new HashMap<>();
        try (var stmt = conn.prepareStatement(
                "SELECT indexes.indexname, indexes.indexdef "
                        + "FROM pg_indexes indexes "
                        + "JOIN pg_namespace namespace ON namespace.nspname = indexes.schemaname "
                        + "JOIN pg_class index_class ON index_class.relnamespace = namespace.oid "
                        + "AND index_class.relname = indexes.indexname "
                        + "WHERE indexes.schemaname = ? AND indexes.tablename = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM pg_constraint constraint_row "
                        + "WHERE constraint_row.conindid = index_class.oid)")) {
            stmt.setString(1, options.schema());
            stmt.setString(2, tableName);
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) {
                    String name = SqlIdentifiers.requireIdentifier(rows.getString(1), "live index");
                    if (live.put(name, rows.getString(2)) != null) {
                        throw new IllegalStateException("duplicate live index name: " + name);
                    }
                }
            }
        }
        Set<String> expected = new HashSet<>();
        if (tableDef.indexes() != null) {
            for (String sql : tableDef.indexes()) {
                IndexDefinition target = IndexDefinition.parse(sql);
                expected.add(target.name());
                String liveSql = live.get(target.name());
                if (liveSql == null) {
                    execute(conn, sql);
                } else if (!target.hasSameStructure(IndexDefinition.parse(liveSql))) {
                    addIndexReplacement(pendingSql, target, IndexDefinition.parse(liveSql), sql);
                } else if (!target.hasEquivalentPredicate(IndexDefinition.parse(liveSql))) {
                    addIndexReplacement(pendingSql, target, IndexDefinition.parse(liveSql), sql);
                } else if (!target.canonicalSql().equals(IndexDefinition.parse(liveSql).canonicalSql())) {
                    log.info("[SchemaApplier] PostgreSQL normalized equivalent predicate casts for {}.{}",
                            tableName, target.name());
                }
            }
        }
        for (String liveName : live.keySet().stream().sorted().toList()) {
            if (!expected.contains(liveName)) {
                pendingSql.add("DROP INDEX IF EXISTS " + liveName + "; -- table=" + tableName);
            }
        }
    }

    private void addIndexReplacement(List<String> pendingSql, IndexDefinition target,
                                     IndexDefinition live, String createSql) {
        pendingSql.add("-- replace index definition drift for " + target.name()
                + "; live: " + live.canonicalSql());
        pendingSql.add("DROP INDEX IF EXISTS " + target.name() + ";");
        pendingSql.add(terminated(createSql));
    }

    private void reconcilePrimaryKey(DatabaseMetaData meta, String tableName,
                                     SchemaDefinition.TableDef tableDef, List<String> pendingSql)
            throws SQLException {
        if (tableDef.createSql() == null) {
            return;
        }
        List<String> expected = primaryKeyColumns(tableDef.createSql());
        TreeMap<Short, String> orderedLive = new TreeMap<>();
        String constraintName = null;
        try (ResultSet rows = meta.getPrimaryKeys(null, options.schema(), tableName)) {
            while (rows.next()) {
                short sequence = rows.getShort("KEY_SEQ");
                String column = SqlIdentifiers.requireIdentifier(rows.getString("COLUMN_NAME"), "primary-key column");
                if (orderedLive.put(sequence, column) != null) {
                    throw new IllegalStateException("duplicate primary-key sequence for table: " + tableName);
                }
                String rowConstraint = rows.getString("PK_NAME");
                if (rowConstraint != null) {
                    rowConstraint = SqlIdentifiers.requireIdentifier(rowConstraint, "primary-key constraint");
                    if (constraintName != null && !constraintName.equals(rowConstraint)) {
                        throw new IllegalStateException("multiple primary-key constraints reported for table: "
                                + tableName);
                    }
                    constraintName = rowConstraint;
                }
            }
        }
        List<String> live = List.copyOf(orderedLive.values());
        if (expected.equals(live)) {
            return;
        }
        if (expected.isEmpty()) {
            if (constraintName == null) {
                pendingSql.add("-- pending: primary key is absent from definition for " + tableName
                        + ", but JDBC did not report its constraint name");
            } else {
                pendingSql.add("ALTER TABLE " + tableName + " DROP CONSTRAINT " + constraintName
                        + "; -- pending: primary key absent from definition");
            }
        } else if (live.isEmpty()) {
            pendingSql.add("ALTER TABLE " + tableName + " ADD PRIMARY KEY ("
                    + String.join(", ", expected) + "); -- pending: primary key is missing");
        } else {
            if (constraintName == null) {
                pendingSql.add("-- primary key drift for " + tableName + "; expected=" + expected
                        + "; live=" + live + "; JDBC did not report the constraint name");
            } else {
                pendingSql.add("-- replace drifted primary key on " + tableName + "; live=" + live);
                pendingSql.add("ALTER TABLE " + tableName + " DROP CONSTRAINT " + constraintName + ";");
                pendingSql.add("ALTER TABLE " + tableName + " ADD PRIMARY KEY ("
                        + String.join(", ", expected) + ");");
            }
        }
    }

    private Set<String> getLivePrimaryKeyColumns(DatabaseMetaData meta, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rows = meta.getPrimaryKeys(null, options.schema(), tableName)) {
            while (rows.next()) {
                columns.add(SqlIdentifiers.requireIdentifier(
                        rows.getString("COLUMN_NAME"), "primary-key column"));
            }
        }
        return Set.copyOf(columns);
    }

    private String terminated(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private List<String> primaryKeyColumns(String createSql) {
        if (createSql == null) {
            return List.of();
        }
        Matcher matcher = PRIMARY_KEY_COLUMNS.matcher(createSql);
        if (!matcher.find()) {
            Matcher inline = INLINE_PRIMARY_KEY.matcher(createSql);
            if (!inline.find()) {
                return List.of();
            }
            return List.of(SqlIdentifiers.requireIdentifier(inline.group(1), "primary-key column"));
        }
        List<String> columns = new ArrayList<>();
        for (String raw : matcher.group(1).split(",")) {
            columns.add(SqlIdentifiers.requireIdentifier(raw.trim(), "primary-key column"));
        }
        return List.copyOf(columns);
    }

    private List<String> detectOrphanedTables(SchemaDefinition def, Set<String> existingTables) {
        Set<String> expected = new HashSet<>();
        if (def.tables() != null) {
            def.tables().keySet().forEach(name -> expected.add(name.toLowerCase(Locale.ROOT)));
        }
        List<String> pending = new ArrayList<>();
        for (String table : existingTables.stream().sorted().toList()) {
            if (!expected.contains(table) && !isIgnorableSchemaTable(table)
                    && !table.equals(options.historyTable())) {
                pending.add("DROP TABLE " + table + "; -- pending: table absent from definition");
            }
        }
        return pending;
    }

    private Set<String> getExistingTables(DatabaseMetaData meta) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = meta.getTables(null, options.schema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    Map<String, LiveColumn> getLiveColumns(DatabaseMetaData meta, String tableName) throws SQLException {
        Map<String, LiveColumn> columns = new HashMap<>();
        try (ResultSet rs = meta.getColumns(null, options.schema(), tableName.toLowerCase(Locale.ROOT), "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                boolean decimalDigitsNull = rs.wasNull();
                boolean notNull = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String colDefault = rs.getString("COLUMN_DEF");
                Integer length = null;
                Integer scale = null;
                String normalized = ColumnDefinitionParser.normalizeType(typeName);
                if (("VARCHAR".equals(normalized) || "CHAR".equals(normalized))
                        && size > 0 && size < 10_000) {
                    length = size;
                } else if ("NUMERIC".equals(normalized) && size > 0 && size <= 1_000) {
                    length = size;
                    scale = decimalDigitsNull ? null : decimalDigits;
                } else if ("VECTOR".equals(normalized)) {
                    length = readVectorDimension(meta.getConnection(), tableName, name);
                }
                columns.put(name, new LiveColumn(normalized, length, scale, notNull, colDefault));
            }
        }
        return columns;
    }

    private int readVectorDimension(Connection conn, String tableName, String columnName) throws SQLException {
        if (conn == null) {
            throw new SQLException("JDBC metadata did not expose its connection for vector inspection");
        }
        String sql = "SELECT format_type(a.atttypid, a.atttypmod) "
                + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND a.attname = ? "
                + "AND a.attnum > 0 AND NOT a.attisdropped";
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, options.schema());
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    String formatted = rows.getString(1);
                    if (formatted != null && formatted.matches("(?i)^vector\\(\\d+\\)$")) {
                        return Integer.parseInt(formatted.substring(7, formatted.length() - 1));
                    }
                }
            }
        }
        throw new SQLException("Could not determine vector dimension for " + tableName + "." + columnName);
    }

    private void execute(Connection conn, String sql) throws SQLException {
        if (options.dryRun()) {
            plannedSql.get().add(sql);
            return;
        }
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void executeControl(Connection conn, String sql) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private record SeqState(Long lastValue, Long maxId) {}

    private SeqState readSequenceState(Connection conn, String tableName) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(PkIdentity.readSequenceStateSql(tableName))) {
            if (!rs.next()) {
                return null;
            }
            long last = rs.getLong(1);
            Long lastValue = rs.wasNull() ? null : last;
            long max = rs.getLong(2);
            Long maxId = rs.wasNull() ? null : max;
            return new SeqState(lastValue, maxId);
        }
    }

    private void repairIdIdentity(Connection conn, String tableName) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        boolean ownsTransaction = prevAutoCommit;
        try {
            String attidentity = null;
            String seq = null;
            try (var stmt = conn.prepareStatement(
                    "SELECT a.attidentity, pg_get_serial_sequence(?, 'id') " +
                    "FROM pg_attribute a " +
                    "JOIN pg_class c ON a.attrelid = c.oid " +
                    "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                    "WHERE n.nspname = ? AND c.relname = ? AND a.attname = 'id' " +
                    "AND a.attnum > 0 AND NOT a.attisdropped")) {
                stmt.setString(1, options.schema() + "." + tableName);
                stmt.setString(2, options.schema());
                stmt.setString(3, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    attidentity = rs.getString(1);
                    seq = rs.getString(2);
                }
            }
            boolean attach = PkIdentity.needsIdentityRepair(attidentity, seq);
            Long seqLast = null;
            Long maxId = null;
            if (!attach) {
                SeqState state = readSequenceState(conn, tableName);
                if (state != null) {
                    seqLast = state.lastValue;
                    maxId = state.maxId;
                }
                if (!PkIdentity.needsSequenceAdvance(seqLast, maxId)) {
                    return;
                }
            }
            if (ownsTransaction) {
                conn.setAutoCommit(false);
            }
            execute(conn, PkIdentity.lockTableSql(tableName));
            if (attach) {
                execute(conn, PkIdentity.addGeneratedIdentitySql(tableName));
            }
            SeqState after = readSequenceState(conn, tableName);
            if (after != null
                    && PkIdentity.needsSequenceAdvance(after.lastValue, after.maxId)) {
                execute(conn, PkIdentity.syncIdentitySequenceSql(tableName));
            }
            if (ownsTransaction) {
                conn.commit();
            }
            if (attach) {
                log.info("[SchemaApplier] Added IDENTITY on {}.id", tableName);
            }
        } catch (Exception e) {
            if (ownsTransaction) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // keep original error
                }
            }
            throw new SQLException("Could not repair IDENTITY on " + tableName + ".id", e);
        } finally {
            if (ownsTransaction) {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // startup continues
                }
            }
        }
    }
}
