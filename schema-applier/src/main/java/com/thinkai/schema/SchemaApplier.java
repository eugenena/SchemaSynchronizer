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
        boolean previousAutoCommit = conn.getAutoCommit();
        boolean ownsTransaction = previousAutoCommit;
        Savepoint savepoint = null;
        String previousSearchPath = null;
        ChangeSetExecutor executor = new ChangeSetExecutor();
        List<SchemaDefinition.ChangeSet> allChanges = executor.validate(def.changes());
        plannedSql.set(new ArrayList<>());
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
            rollback(conn, ownsTransaction, savepoint);
            throw exception;
        } finally {
            plannedSql.remove();
            if (ownsTransaction) {
                conn.setAutoCommit(previousAutoCommit);
            }
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
                            execute(conn, sql);
                            log.info("[SchemaApplier] Altered {}.{}: {}", tableName, col.name(), sql);
                            columnsAltered++;
                        }
                        pendingSql.addAll(plan.pendingSql());
                    } catch (IllegalArgumentException ex) {
                        pendingSql.add("-- pending: cannot safely reconcile " + tableName + "." + colName
                                + ": " + ex.getMessage());
                    }
                }

                for (String existingCol : liveColumns.keySet()) {
                    if (!targetColumns.contains(existingCol)) {
                        pendingSql.add(String.format(
                                "ALTER TABLE %s DROP COLUMN %s;", tableName, existingCol
                        ));
                    }
                }
            }

            if (tableDef.indexes() != null) {
                for (String indexSql : tableDef.indexes()) {
                    NonDestructiveSqlPolicy.requireCreateIndex(indexSql);
                    execute(conn, indexSql);
                }
            }

            if (PkIdentity.createSqlWantsIdIdentity(tableDef.createSql())) {
                repairIdIdentity(conn, tableName);
            }
        }

        List<String> orphanedIndexes = detectOrphanedIndexes(conn, def, existingTables);

        if (tablesCreated > 0 || columnsAdded > 0 || columnsAltered > 0) {
            log.info("[SchemaApplier] Applied: {} table(s) created, {} column(s) added, {} column alter(s)",
                    tablesCreated, columnsAdded, columnsAltered);
        } else {
            log.debug("[SchemaApplier] Schema is up to date — no changes needed");
        }

        if (!pendingSql.isEmpty()) {
            log.warn("");
            log.warn("╔══════════════════════════════════════════════════════════════════╗");
            log.warn("║         PENDING MANUAL SCHEMA CHANGES DETECTED                  ║");
            log.warn("║  Destructive or unsafe diffs vs schema-definition.json          ║");
            log.warn("║  Run these manually if you intend them:                         ║");
            log.warn("╚══════════════════════════════════════════════════════════════════╝");
            for (String sql : pendingSql) {
                log.warn("    {}", sql);
            }
        }

        if (!orphanedIndexes.isEmpty()) {
            log.warn("");
            log.warn("╔══════════════════════════════════════════════════════════════════╗");
            log.warn("║         ORPHANED INDEXES DETECTED                               ║");
            log.warn("║  Indexes exist in DB but are absent from schema-definition.json ║");
            log.warn("║  Run these manually if you intend to drop them:                 ║");
            log.warn("╚══════════════════════════════════════════════════════════════════╝");
            for (String sql : orphanedIndexes) {
                log.warn("    {}", sql);
            }
        }
        pendingSql.addAll(orphanedIndexes);
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
        return t.startsWith("flyway_") || t.startsWith("thinkai_schema_");
    }

    public static boolean isIgnorableSchemaIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) return true;
        String i = indexName.toLowerCase(Locale.ROOT);
        return i.startsWith("flyway_") || i.startsWith("thinkai_schema_");
    }

    private List<String> detectOrphanedIndexes(
            Connection conn, SchemaDefinition def, Set<String> existingTables) {
        List<String> orphanedIndexes = new ArrayList<>();
        try {
            Set<String> serializedIndexNames = new HashSet<>();
            for (SchemaDefinition.TableDef tableDef : def.tables().values()) {
                if (tableDef.indexes() == null) continue;
                for (String idx : tableDef.indexes()) {
                    String name = idx.replaceFirst("^CREATE (UNIQUE )?INDEX IF NOT EXISTS ", "");
                    int onPos = name.indexOf(" ON ");
                    if (onPos > 0) {
                        serializedIndexNames.add(name.substring(0, onPos).toLowerCase(Locale.ROOT));
                    }
                }
            }
            for (String tableName : existingTables) {
                if (isIgnorableSchemaTable(tableName)) continue;
                try (var stmt = conn.prepareStatement(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ?")) {
                    stmt.setString(1, options.schema());
                    stmt.setString(2, tableName);
                    try (var rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String idxName = rs.getString("indexname").toLowerCase(Locale.ROOT);
                            if (isIgnorableSchemaIndex(idxName)) continue;
                            if (!serializedIndexNames.contains(idxName)) {
                                orphanedIndexes.add(String.format(
                                        "DROP INDEX IF EXISTS %s; -- table=%s", idxName, tableName));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (options.failOnPending()) {
                throw new IllegalStateException("Unable to verify indexes in schema " + options.schema(), e);
            }
            log.warn("[SchemaApplier] Orphaned index detection skipped: {}", e.getMessage());
        }
        return orphanedIndexes;
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
                boolean notNull = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String colDefault = rs.getString("COLUMN_DEF");
                Integer length = null;
                String normalized = ColumnDefinitionParser.normalizeType(typeName);
                if ("VARCHAR".equals(normalized) && size > 0 && size < 10_000) {
                    length = size;
                }
                columns.put(name, new LiveColumn(normalized, length, notNull, colDefault));
            }
        }
        return columns;
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
