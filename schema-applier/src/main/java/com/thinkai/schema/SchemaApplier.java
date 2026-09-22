package com.thinkai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
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

    public SchemaApplier(ObjectMapper objectMapper, DataSource dataSource) {
        this(objectMapper, dataSource, "/schema-definition.json");
    }

    public SchemaApplier(ObjectMapper objectMapper, DataSource dataSource, String classpathResource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.classpathResource = classpathResource;
    }

    /** Load definition from classpath and apply. */
    public void applyFromClasspath() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            InputStream fromClass = getClass().getResourceAsStream(classpathResource);
            InputStream resource = fromClass != null ? fromClass
                    : Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(classpathResource.startsWith("/")
                            ? classpathResource.substring(1) : classpathResource);
            if (resource == null) {
                log.debug("[SchemaApplier] No {} on classpath — nothing to do", classpathResource);
                return;
            }
            try (InputStream in = resource) {
                SchemaDefinition def = objectMapper.readValue(in, SchemaDefinition.class);
                applySchema(conn, def);
            }
        }
    }

    public void applySchema(Connection conn, SchemaDefinition def) throws Exception {
        if (def.tables() == null || def.tables().isEmpty()) {
            return;
        }

        DatabaseMetaData meta = conn.getMetaData();
        Set<String> existingTables = getExistingTables(meta);

        int tablesCreated = 0;
        int columnsAdded = 0;
        int columnsAltered = 0;
        List<String> pendingSql = new ArrayList<>();

        for (Map.Entry<String, SchemaDefinition.TableDef> entry : def.tables().entrySet()) {
            String tableName = entry.getKey().toLowerCase(Locale.ROOT);
            SchemaDefinition.TableDef tableDef = entry.getValue();

            if (!existingTables.contains(tableName)) {
                if (tableDef.createSql() != null) {
                    execute(conn, tableDef.createSql());
                    log.info("[SchemaApplier] Created table: {}", tableName);
                    tablesCreated++;
                    existingTables.add(tableName);
                } else {
                    log.warn("[SchemaApplier] Table '{}' missing but no createSql provided — skipping", tableName);
                    continue;
                }
            }

            if (tableDef.columns() != null) {
                Map<String, LiveColumn> liveColumns = getLiveColumns(meta, tableName);
                Set<String> targetColumns = new HashSet<>();

                for (SchemaDefinition.ColumnDef col : tableDef.columns()) {
                    String colName = col.name().toLowerCase(Locale.ROOT);
                    targetColumns.add(colName);
                    if (!liveColumns.containsKey(colName)) {
                        if (col.definition() != null) {
                            String sql = String.format(
                                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s",
                                    tableName, col.name(), col.definition()
                            );
                            execute(conn, sql);
                            log.info("[SchemaApplier] Added column {}.{}", tableName, col.name());
                            columnsAdded++;
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
                        log.debug("[SchemaApplier] Skip alter {}.{}: {}", tableName, col.name(), ex.getMessage());
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
    }

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
        return t.startsWith("flyway_") || t.equals("shedlock");
    }

    public static boolean isIgnorableSchemaIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) return true;
        String i = indexName.toLowerCase(Locale.ROOT);
        return i.startsWith("flyway_") || i.equals("shedlock_pkey");
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
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?")) {
                    stmt.setString(1, tableName);
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
            log.debug("[SchemaApplier] Orphaned index detection skipped (non-PostgreSQL?): {}", e.getMessage());
        }
        return orphanedIndexes;
    }

    private Set<String> getExistingTables(DatabaseMetaData meta) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = meta.getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    Map<String, LiveColumn> getLiveColumns(DatabaseMetaData meta, String tableName) throws SQLException {
        Map<String, LiveColumn> columns = new HashMap<>();
        try (ResultSet rs = meta.getColumns(null, "public", tableName.toLowerCase(Locale.ROOT), "%")) {
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

    private void repairIdIdentity(Connection conn, String tableName) {
        boolean prevAutoCommit = true;
        boolean txnStarted = false;
        try {
            String attidentity = null;
            String seq = null;
            try (var stmt = conn.prepareStatement(
                    "SELECT a.attidentity, pg_get_serial_sequence(?, 'id') " +
                    "FROM pg_attribute a " +
                    "JOIN pg_class c ON a.attrelid = c.oid " +
                    "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                    "WHERE n.nspname = 'public' AND c.relname = ? AND a.attname = 'id' " +
                    "AND a.attnum > 0 AND NOT a.attisdropped")) {
                stmt.setString(1, tableName);
                stmt.setString(2, tableName);
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
            prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            txnStarted = true;
            execute(conn, PkIdentity.lockTableSql(tableName));
            if (attach) {
                execute(conn, PkIdentity.addGeneratedIdentitySql(tableName));
            }
            SeqState after = readSequenceState(conn, tableName);
            if (after != null
                    && PkIdentity.needsSequenceAdvance(after.lastValue, after.maxId)) {
                execute(conn, PkIdentity.syncIdentitySequenceSql(tableName));
            }
            conn.commit();
            if (attach) {
                log.info("[SchemaApplier] Added IDENTITY on {}.id", tableName);
            }
        } catch (Exception e) {
            if (txnStarted) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // keep original error
                }
            }
            log.warn("[SchemaApplier] Could not repair IDENTITY on {}.id: {}", tableName, e.getMessage());
        } finally {
            if (txnStarted) {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // startup continues
                }
            }
        }
    }
}
