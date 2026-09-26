// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
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
public class SchemaSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(SchemaSynchronizer.class);
    private static final Pattern CREATE_TABLE_TARGET = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?:(?:([a-zA-Z_][a-zA-Z0-9_]*)\\.)?([a-zA-Z_][a-zA-Z0-9_]*))\\s*\\(.*");
    private static final Pattern PRIMARY_KEY_COLUMNS = Pattern.compile(
            "(?is)\\bPRIMARY\\s+KEY\\s*(?:(?:NON)?CLUSTERED\\s*)?\\(([^)]+)\\)");
    private static final Pattern INLINE_PRIMARY_KEY = Pattern.compile(
            "(?is)(?:\\(|,)\\s*(?!(?:CONSTRAINT|PRIMARY|UNIQUE)\\b)([a-zA-Z_][a-zA-Z0-9_]*)\\s+[^,]*?\\bPRIMARY\\s+KEY\\b");

    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final String classpathResource;
    private final SchemaSynchronizerOptions options;
    private final ThreadLocal<List<String>> plannedSql = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Standalone entry point that synchronizes a target database from a schema file.
     *
     * <p>Usage:
     * <pre>
     * SchemaSynchronizer &lt;jdbc-url&gt; &lt;user&gt; &lt;password-or--&gt; &lt;schema-file&gt;
     *     [schema] [history-table]
     * </pre>
     * Use {@code -} for the password to read {@code SCHEMA_DB_PASSWORD}. Literal
     * passwords on the command line are rejected. The optional schema and history
     * table default to {@code public} and {@code schema_synchronizer_history}.
     */
    public static void main(String[] args) throws Exception {
        runFromArgs(args, false);
    }

    /**
     * Dry-run entry point with the same arguments as {@link #main(String[])}.
     * Plans work without executing DDL. Dialects where
     * {@link DatabaseDialect#supportsTransactionalDryRun()} is true also roll back
     * any transactional control work; others only skip statement execution.
     */
    public static void dryRunMain(String[] args) throws Exception {
        runFromArgs(args, true);
    }

    /**
     * Offline validation entry point.
     *
     * <p>Usage: {@code SchemaSynchronizer validate &lt;schema-file&gt; [schema]}
     * or {@code &lt;schema-file&gt; [schema]} when invoked through the CLI {@code validate}
     * command (without the literal {@code validate} token).
     */
    public static void validateMain(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "Usage: SchemaSynchronizer validate <schema-file> [schema]");
        }
        Path schemaFile = Path.of(args[0]);
        String schema = SqlIdentifiers.requireIdentifierPreservingCase(
                args.length >= 2 ? args[1] : "public", "schema");
        SchemaDefinitionValidator.validateFile(schemaFile, schema);
        log.info("[SchemaSynchronizer] Definition is valid: {}", schemaFile.toAbsolutePath());
    }

    private static void runFromArgs(String[] args, boolean dryRun) throws Exception {
        if (args.length < 4 || args.length > 6) {
            throw new IllegalArgumentException("Usage: SchemaSynchronizer <jdbc-url> <user> "
                    + "<password-or--> <schema-file> [schema] [history-table]");
        }

        String password = CliCredentials.requirePasswordFromEnv(args[2]);
        Path schemaFile = Path.of(args[3]);
        String schema = SqlIdentifiers.requireIdentifierPreservingCase(
                args.length >= 5 ? args[4] : "public", "schema");
        String historyTable = SqlIdentifiers.requireIdentifier(
                args.length >= 6 ? args[5] : "schema_synchronizer_history", "history table");
        ObjectMapper mapper = new ObjectMapper();
        SchemaDefinition definition = readDefinition(mapper, schemaFile);
        SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(
                schema, historyTable, 7_249_031_147L, dryRun, true, true);
        SchemaSynchronizer synchronizer = new SchemaSynchronizer(mapper, null, "", options);

        log.info("[SchemaSynchronizer] Connecting to target database{}", dryRun ? " (dry-run)" : "");
        try (Connection connection = DriverManager.getConnection(args[0], args[1], password)) {
            SchemaSynchronizationResult result = synchronizer.synchronizeWithResult(connection, definition);
            log.info("[SchemaSynchronizer] Complete: {} table(s) created, {} column(s) added, "
                            + "{} column alteration(s), {} change set(s) applied, {} pending statement(s)",
                    result.tablesCreated(), result.columnsAdded(), result.columnsAltered(),
                    result.changeSetsApplied(), result.pendingSql().size());
            if (!result.plannedSql().isEmpty()) {
                log.info("[SchemaSynchronizer] Planned statements: {}", result.plannedSql().size());
            }
        }
    }

    private static SchemaDefinition readDefinition(ObjectMapper mapper, Path schemaFile) throws IOException {
        if (!Files.isRegularFile(schemaFile)) {
            throw new IllegalArgumentException("Schema definition does not exist: " + schemaFile.toAbsolutePath());
        }
        return mapper.readValue(schemaFile.toFile(), SchemaDefinition.class);
    }

    public SchemaSynchronizer(ObjectMapper objectMapper, DataSource dataSource) {
        this(objectMapper, dataSource, "/schema-definition.json", SchemaSynchronizerOptions.defaults());
    }

    public SchemaSynchronizer(ObjectMapper objectMapper, DataSource dataSource, String classpathResource) {
        this(objectMapper, dataSource, classpathResource, SchemaSynchronizerOptions.defaults());
    }

    public SchemaSynchronizer(ObjectMapper objectMapper, DataSource dataSource, String classpathResource,
                         SchemaSynchronizerOptions options) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.classpathResource = classpathResource;
        this.options = options;
    }

    /** Load definition from classpath and apply. */
    public SchemaSynchronizationResult synchronizeFromClasspath() throws Exception {
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
                log.warn("[SchemaSynchronizer] No {} on classpath — schema management is inactive", classpathResource);
                return new SchemaSynchronizationResult(0, 0, 0, 0, List.of(), List.of());
            }
            try (InputStream in = resource) {
                SchemaDefinition def = objectMapper.readValue(in, SchemaDefinition.class);
                return synchronizeWithResult(conn, def);
            }
        }
    }

    public void synchronize(Connection conn, SchemaDefinition def) throws Exception {
        synchronizeWithResult(conn, def);
    }

    public SchemaSynchronizationResult synchronizeWithResult(Connection conn, SchemaDefinition def) throws Exception {
        DatabaseDialect targetDialect = DatabaseDialect.detect(conn.getMetaData());
        if (def.declaredDialect() != targetDialect) {
            throw new IllegalStateException("Schema dialect '" + def.declaredDialect().id()
                    + "' cannot be applied to " + targetDialect.id() + " target");
        }
        if (def.effectiveFormatVersion() > SchemaDefinition.CURRENT_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported schema format version: " + def.effectiveFormatVersion());
        }
        int maxId = targetDialect.maxIdentifierLength();
        if (options.schema().length() > maxId || options.historyTable().length() > maxId) {
            throw new IllegalArgumentException(targetDialect.id() + " identifiers must be at most "
                    + maxId + " characters (schema='" + options.schema() + "', history='"
                    + options.historyTable() + "')");
        }
        rejectMisleadingDefaultSchema(targetDialect, options.schema());
        if (options.dryRun() && !targetDialect.supportsTransactionalDryRun()) {
            log.warn("[SchemaSynchronizer] Dry-run on {} skips statement execution and verificationSql, "
                            + "but this dialect does not support transactional dry-run rollback "
                            + "(DatabaseDialect.supportsTransactionalDryRun()=false). "
                            + "Use a disposable instance for rehearsals that must touch the database.",
                    targetDialect.id());
        }
        if (targetDialect.ddlMayCommitImplicitly()) {
            return synchronizeImplicitDdlDialect(conn, def, targetDialect);
        }
        if (!targetDialect.supportsTransactionalDryRun() && options.dryRun()) {
            // Defensive: transactional path assumes rollback is meaningful.
            log.warn("[SchemaSynchronizer] Entering transactional sync path on {} without "
                    + "supportsTransactionalDryRun; dry-run will still attempt rollback", targetDialect.id());
        }
        return synchronizeTransactionalDialect(conn, def, targetDialect);
    }

    /** Boot and CLI often leave schema=public; SQL Server/Oracle need dbo / connected user. */
    private static void rejectMisleadingDefaultSchema(DatabaseDialect dialect, String schema) {
        if (!"public".equalsIgnoreCase(schema)) {
            return;
        }
        if (dialect == DatabaseDialect.SQLSERVER) {
            throw new IllegalArgumentException(
                    "SQL Server schema must not be 'public'; set schema-synchronizer.schema=dbo "
                            + "(or the application schema name)");
        }
        if (dialect == DatabaseDialect.ORACLE) {
            throw new IllegalArgumentException(
                    "Oracle schema must not be 'public'; set schema-synchronizer.schema to the "
                            + "connected user/schema name");
        }
        if (dialect.isMySqlFamily()) {
            throw new IllegalArgumentException(
                    dialect.id() + " uses the database/catalog as its namespace; set "
                            + "schema-synchronizer.schema to the catalog name (not 'public')");
        }
    }

    private SchemaSynchronizationResult synchronizeTransactionalDialect(Connection conn, SchemaDefinition def,
                                                                        DatabaseDialect dialect) throws Exception {
        boolean previousAutoCommit = conn.getAutoCommit();
        boolean ownsTransaction = previousAutoCommit;
        Savepoint savepoint = null;
        String previousSearchPath = null;
        ChangeSetExecutor executor = new ChangeSetExecutor();
        validateDeclarativeDefinition(def, dialect);
        List<SchemaDefinition.ChangeSet> allChanges = executor.validate(def.changes(), options, dialect);
        plannedSql.set(new ArrayList<>());
        Exception primaryFailure = null;
        String lockToken = null;
        try {
            if (ownsTransaction) {
                conn.setAutoCommit(false);
            } else {
                savepoint = conn.setSavepoint("schema_synchronizer");
                if (dialect == DatabaseDialect.POSTGRESQL) {
                    previousSearchPath = readSearchPath(conn);
                }
            }
            if (dialect == DatabaseDialect.POSTGRESQL) {
                executeControl(conn, "SET LOCAL search_path TO " + options.schema());
            } else if (dialect == DatabaseDialect.SQLSERVER) {
                bindSqlServerSchema(conn, options.schema());
            }
            lockToken = DialectSupport.acquireLock(conn, dialect, options.schema(), options.advisoryLockId());
            executor.validateHistory(conn, allChanges, options, dialect);
            ChangeSetExecutor.Result beforeChanges = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA), options,
                    dialect);
            plannedSql.get().addAll(beforeChanges.plannedSql());
            DeclarativeResult declarative = applyDeclarativeSchema(conn, def, dialect, true);
            ChangeSetExecutor.Result afterChanges = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA), options,
                    dialect);
            plannedSql.get().addAll(afterChanges.plannedSql());
            if (options.failOnPending() && !declarative.pendingSql().isEmpty()) {
                throw new IllegalStateException("unsafe or destructive schema differences require manual resolution: "
                        + String.join(" | ", declarative.pendingSql()));
            }
            if (options.dryRun()) {
                if (!dialect.supportsTransactionalDryRun()) {
                    throw new IllegalStateException(dialect.id()
                            + " does not support transactional dry-run rollback");
                }
                rollback(conn, ownsTransaction, savepoint);
            } else if (ownsTransaction) {
                conn.commit();
            } else {
                if (dialect == DatabaseDialect.POSTGRESQL) {
                    restoreSearchPath(conn, previousSearchPath);
                }
                DialectSupport.releaseSavepoint(conn, dialect, savepoint);
            }
            return new SchemaSynchronizationResult(beforeChanges.applied() + afterChanges.applied(),
                    declarative.tablesCreated(),
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
            Exception cleanupFailure = null;
            try {
                DialectSupport.releaseLock(conn, dialect, lockToken);
            } catch (SQLException | RuntimeException releaseFailure) {
                cleanupFailure = releaseFailure;
            }
            if (ownsTransaction) {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException | RuntimeException restoreFailure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = restoreFailure;
                    } else {
                        cleanupFailure.addSuppressed(restoreFailure);
                    }
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private SchemaSynchronizationResult synchronizeImplicitDdlDialect(Connection conn, SchemaDefinition def,
                                                                      DatabaseDialect dialect) throws Exception {
        validateDeclarativeDefinition(def, dialect);
        ChangeSetExecutor executor = new ChangeSetExecutor();
        List<SchemaDefinition.ChangeSet> allChanges = executor.validate(def.changes(), options, dialect);
        plannedSql.set(new ArrayList<>());
        if (dialect.usesCatalogNamespace()
                && conn.getCatalog() != null
                && !conn.getCatalog().equals(options.schema())) {
            throw new IllegalStateException("Connected " + dialect.id() + " catalog '" + conn.getCatalog()
                    + "' does not match configured schema '" + options.schema()
                    + "' (compared case-sensitively; configure the catalog exactly as the server reports it)");
        }
        if (dialect == DatabaseDialect.ORACLE) {
            requireOracleSchema(conn, options.schema());
        }
        String lockToken = null;
        Exception primaryFailure = null;
        try {
            lockToken = DialectSupport.acquireLock(conn, dialect, options.schema(), options.advisoryLockId());
            executor.validateHistory(conn, allChanges, options, dialect);
            DeclarativeResult preflight = applyDeclarativeSchema(conn, def, dialect, false);
            if (options.failOnPending() && !preflight.pendingSql().isEmpty()) {
                throw new IllegalStateException("unsafe or destructive schema differences require manual resolution: "
                        + String.join(" | ", preflight.pendingSql()));
            }
            ChangeSetExecutor.Result before = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA), options, dialect);
            plannedSql.get().addAll(before.plannedSql());
            DeclarativeResult declarative = applyDeclarativeSchema(conn, def, dialect, true);
            ChangeSetExecutor.Result after = executor.apply(conn,
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA), options, dialect);
            plannedSql.get().addAll(after.plannedSql());
            if (options.failOnPending() && !declarative.pendingSql().isEmpty()) {
                throw new IllegalStateException("unsafe or destructive schema differences require manual resolution: "
                        + String.join(" | ", declarative.pendingSql()));
            }
            return new SchemaSynchronizationResult(before.applied() + after.applied(), declarative.tablesCreated(),
                    declarative.columnsAdded(), declarative.columnsAltered(), List.copyOf(plannedSql.get()),
                    declarative.pendingSql());
        } catch (Exception failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            plannedSql.remove();
            try {
                DialectSupport.releaseLock(conn, dialect, lockToken);
            } catch (SQLException | RuntimeException releaseFailure) {
                if (primaryFailure == null) {
                    throw releaseFailure;
                }
                primaryFailure.addSuppressed(releaseFailure);
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

    private DeclarativeResult applyDeclarativeSchema(Connection conn, SchemaDefinition def,
                                                     DatabaseDialect dialect, boolean applyChanges) throws Exception {
        if (def.tables() == null || def.tables().isEmpty()) {
            return new DeclarativeResult(0, 0, 0, List.of());
        }

        DatabaseMetaData meta = conn.getMetaData();
        Set<String> existingTables = getExistingTables(meta, dialect);

        int tablesCreated = 0;
        int columnsAdded = 0;
        int columnsAltered = 0;
        List<String> pendingSql = new ArrayList<>();

        for (Map.Entry<String, SchemaDefinition.TableDef> entry : def.tables().entrySet()) {
            String tableName = entry.getKey().toLowerCase(Locale.ROOT);
            SqlIdentifiers.requireIdentifier(tableName, "table", dialect.maxIdentifierLength());
            SchemaDefinition.TableDef tableDef = entry.getValue();
            Set<String> livePrimaryKeyColumns = existingTables.contains(tableName)
                    ? getLivePrimaryKeyColumns(meta, tableName, dialect) : Set.of();
            List<String> deferredNullabilitySql = new ArrayList<>();

            if (!existingTables.contains(tableName)) {
                if (tableDef.createSql() != null) {
                    NonDestructiveSqlPolicy.requireCreateTable(tableDef.createSql(), dialect);
                    if (applyChanges) {
                        execute(conn, dialect.executableSql(tableDef.createSql()));
                        log.info("[SchemaSynchronizer] Created table: {}", tableName);
                        tablesCreated++;
                        existingTables.add(tableName);
                    } else {
                        continue;
                    }
                } else {
                    log.warn("[SchemaSynchronizer] Table '{}' missing but no createSql provided — skipping", tableName);
                    pendingSql.add("-- pending: table " + tableName + " is missing and createSql is absent");
                    continue;
                }
            }

            if (tableDef.columns() != null) {
                Map<String, LiveColumn> liveColumns = getLiveColumns(meta, tableName, dialect);
                Set<String> targetColumns = new HashSet<>();

                for (SchemaDefinition.ColumnDef col : tableDef.columns()) {
                    String colName = col.name().toLowerCase(Locale.ROOT);
                    SqlIdentifiers.requireIdentifier(colName, "column", dialect.maxIdentifierLength());
                    targetColumns.add(colName);
                    if (!liveColumns.containsKey(colName)) {
                        if (col.definition() != null) {
                            ColumnDefinitionParser.parse(col.definition());
                            String sql = addColumnSql(tableName, col.name(), col.definition(), dialect);
                            if (applyChanges) {
                                execute(conn, sql);
                                log.info("[SchemaSynchronizer] Added column {}.{}", tableName, col.name());
                                columnsAdded++;
                            }
                        } else {
                            pendingSql.add("-- pending: column " + tableName + "." + colName
                                    + " is missing and its definition is absent");
                        }
                        continue;
                    }

                    if (col.definition() == null || shouldSkipAlter(col.definition(), liveColumns.get(colName))) {
                        continue;
                    }
                    try {
                        ColumnSpec target = foldNationalType(ColumnDefinitionParser.parse(col.definition()), dialect);
                        LiveColumn comparableLive = dialect == DatabaseDialect.ORACLE
                                ? oracleComparableLive(liveColumns.get(colName), target, col.definition())
                                : liveColumns.get(colName);
                        NonDestructiveAlterPlanner.Plan plan =
                                NonDestructiveAlterPlanner.plan(tableName, col.name(), target, comparableLive);
                        if (dialect.isMySqlFamily()) {
                            String blockedReason = plan.applySql().isEmpty() ? null
                                    : mySqlBlockReason(mySqlColumnFacts(conn, tableName, colName), col.definition());
                            plan = mySqlFamilyColumnPlan(tableName, col.name(), col.definition(), plan, blockedReason);
                        } else if (dialect == DatabaseDialect.SQLSERVER) {
                            LiveColumn liveCol = liveColumns.get(colName);
                            plan = sqlServerColumnPlan(tableName, col.name(), col.definition(), plan,
                                    sqlServerColumnFacts(conn, options.schema(), tableName, colName,
                                            col.definition(), target, liveCol, plan));
                        } else if (dialect == DatabaseDialect.ORACLE) {
                            plan = oracleColumnPlan(tableName, col.name(), col.definition(), plan);
                        }
                        boolean relaxesPrimaryKey = livePrimaryKeyColumns.contains(colName)
                                && plan.applyOps().contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
                        for (String sql : plan.applySql()) {
                            boolean deferred = relaxesPrimaryKey && (dialect != DatabaseDialect.POSTGRESQL
                                    || sql.endsWith(" DROP NOT NULL"));
                            if (deferred) {
                                deferredNullabilitySql.add(terminated(sql));
                            } else {
                                if (applyChanges) {
                                    execute(conn, sql);
                                    log.info("[SchemaSynchronizer] Altered {}.{}: {}", tableName, col.name(), sql);
                                    columnsAltered++;
                                }
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

            reconcilePrimaryKey(meta, tableName, tableDef, pendingSql, dialect);
            pendingSql.addAll(deferredNullabilitySql);
            reconcileIndexes(conn, meta, tableName, tableDef, pendingSql, dialect, applyChanges);

            if (applyChanges && dialect == DatabaseDialect.POSTGRESQL
                    && PkIdentity.createSqlWantsIdIdentity(tableDef.createSql())) {
                repairIdIdentity(conn, tableName);
            }
        }

        List<String> orphanedTables = detectOrphanedTables(def, existingTables);

        if (tablesCreated > 0 || columnsAdded > 0 || columnsAltered > 0) {
            log.info("[SchemaSynchronizer] Applied: {} table(s) created, {} column(s) added, {} column alter(s)",
                    tablesCreated, columnsAdded, columnsAltered);
        } else {
            log.debug("[SchemaSynchronizer] Schema is up to date — no changes needed");
        }

        pendingSql.addAll(orphanedTables);
        if (!pendingSql.isEmpty()) {
            log.warn("");
            log.warn("╔══════════════════════════════════════════════════════════════════╗");
            log.warn("║         PENDING MANUAL SCHEMA CHANGES DETECTED                  ║");
            log.warn("║  Destructive or unsafe diffs vs schema-definition.json          ║");
            log.warn("║  Run these manually if you intend them:                         ║");
            log.warn("╚══════════════════════════════════════════════════════════════════╝");
            if (dialect == DatabaseDialect.POSTGRESQL) {
                log.warn("    BEGIN;");
                log.warn("    SET LOCAL search_path TO {};", options.schema());
            } else if (dialect == DatabaseDialect.SQLSERVER) {
                log.warn("    BEGIN TRANSACTION;");
                log.warn("    -- review statements below, then COMMIT TRANSACTION or ROLLBACK TRANSACTION");
            } else if (dialect.usesCatalogNamespace()) {
                log.warn("    USE {};", options.schema());
                log.warn("    -- {} DDL may auto-commit; execute each reviewed statement separately.",
                        dialect.id());
            } else if (dialect == DatabaseDialect.ORACLE) {
                log.warn("    -- oracle schema {}; DDL may auto-commit; execute each reviewed statement separately.",
                        options.schema());
            } else {
                log.warn("    -- {} pending SQL for operator review", dialect.id());
            }
            for (String sql : pendingSql) {
                log.warn("    {}", sql);
            }
            if (dialect == DatabaseDialect.POSTGRESQL) {
                log.warn("    COMMIT;");
            } else if (dialect == DatabaseDialect.SQLSERVER) {
                log.warn("    COMMIT TRANSACTION;");
            }
        }
        return new DeclarativeResult(tablesCreated, columnsAdded, columnsAltered, List.copyOf(pendingSql));
    }

    private record DeclarativeResult(int tablesCreated, int columnsAdded, int columnsAltered,
                                     List<String> pendingSql) {}

    static NonDestructiveAlterPlanner.Plan mySqlFamilyColumnPlan(
            String tableName, String columnName, String definition, NonDestructiveAlterPlanner.Plan plan) {
        return mySqlFamilyColumnPlan(tableName, columnName, definition, plan, null);
    }

    static NonDestructiveAlterPlanner.Plan mySqlFamilyColumnPlan(
            String tableName, String columnName, String definition, NonDestructiveAlterPlanner.Plan plan,
            String blockedReason) {
        if (blockedReason != null && !plan.applySql().isEmpty()) {
            return new NonDestructiveAlterPlanner.Plan(List.of(), List.of(
                    "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition
                            + "; -- pending: " + blockedReason + "; handle this change in a reviewed change set"));
        }
        if (!plan.pendingSql().isEmpty()) {
            return new NonDestructiveAlterPlanner.Plan(List.of(), List.of(
                    "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName
                            + " " + definition + "; -- pending: unsafe type/nullability change"));
        }
        if (!plan.applySql().isEmpty()) {
            return new NonDestructiveAlterPlanner.Plan(List.of(
                    "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition), List.of(),
                    requireOps(plan));
        }
        return plan;
    }

    /** Live MySQL/MariaDB column attributes that {@code MODIFY COLUMN <definition>} would rewrite. */
    record MySqlColumnFacts(boolean found, String collation, String tableCollation, String characterSet,
                            String extra, String comment, String generationExpression) {}

    /**
     * {@code MODIFY COLUMN} replaces the whole column definition: attributes the declaration
     * does not repeat (charset/collation, ON UPDATE, AUTO_INCREMENT, INVISIBLE, COMMENT,
     * generation expression) are silently reset, so such columns are pending instead.
     */
    static String mySqlBlockReason(MySqlColumnFacts facts, String declaredDefinition) {
        if (!facts.found()) {
            return "column was not found in information_schema";
        }
        String declared = declaredDefinition.toUpperCase(Locale.ROOT);
        String extra = facts.extra() == null ? "" : facts.extra().toLowerCase(Locale.ROOT);
        if (facts.generationExpression() != null && !facts.generationExpression().isBlank()) {
            return "generated column";
        }
        if (extra.contains("on update") && !declared.contains("ON UPDATE")) {
            return "ON UPDATE attribute would be dropped by MODIFY COLUMN";
        }
        if (extra.contains("auto_increment") && !declared.contains("AUTO_INCREMENT")) {
            return "AUTO_INCREMENT would be dropped by MODIFY COLUMN";
        }
        if (extra.contains("invisible")) {
            return "INVISIBLE attribute would be dropped by MODIFY COLUMN";
        }
        if (facts.comment() != null && !facts.comment().isEmpty() && !declared.contains("COMMENT")) {
            return "column COMMENT would be dropped by MODIFY COLUMN";
        }
        boolean national = declared.matches("(?s)^\\s*(?:NVARCHAR|NCHAR|NATIONAL)\\b.*");
        String charset = facts.characterSet() == null ? "" : facts.characterSet().toLowerCase(Locale.ROOT);
        if (national && !charset.isEmpty() && !charset.equals("utf8mb3") && !charset.equals("utf8")) {
            return "NVARCHAR/NCHAR would change the character set from " + charset + " to utf8mb3";
        }
        if (!national && facts.collation() != null && !facts.collation().equals(facts.tableCollation())
                && !declared.contains("COLLATE") && !declared.contains("CHARACTER SET")) {
            return "column collation " + facts.collation() + " differs from the table default and would be reset";
        }
        return null;
    }

    private static MySqlColumnFacts mySqlColumnFacts(Connection conn, String tableName, String columnName)
            throws SQLException {
        String sql = """
                SELECT c.COLLATION_NAME, t.TABLE_COLLATION, c.CHARACTER_SET_NAME, c.EXTRA, c.COLUMN_COMMENT,
                       c.GENERATION_EXPRESSION
                FROM information_schema.COLUMNS c
                JOIN information_schema.TABLES t
                  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = ? AND c.COLUMN_NAME = ?
                """;
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new MySqlColumnFacts(false, null, null, null, null, null, null);
                }
                return new MySqlColumnFacts(true, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6));
            }
        }
    }

    /** MySQL/MariaDB and PostgreSQL store NVARCHAR/NCHAR as VARCHAR/CHAR (national charset or none). */
    static ColumnSpec foldNationalType(ColumnSpec spec, DatabaseDialect dialect) {
        if (!dialect.isMySqlFamily() && dialect != DatabaseDialect.POSTGRESQL) {
            return spec;
        }
        return switch (spec.baseType()) {
            case "NVARCHAR" -> new ColumnSpec("VARCHAR", spec.length(), spec.scale(), spec.notNull(), spec.defaultExpr());
            case "NCHAR" -> new ColumnSpec("CHAR", spec.length(), spec.scale(), spec.notNull(), spec.defaultExpr());
            default -> spec;
        };
    }

    /** Identity / serial columns must not get DROP DEFAULT from nextval noise. */
    public static boolean shouldSkipAlter(String definition, LiveColumn live) {
        if (definition == null) {
            return true;
        }
        String upper = definition.toUpperCase(Locale.ROOT);
        if (upper.contains("BIGSERIAL") || upper.matches("(?s).*\\bSERIAL\\b.*")
                || upper.contains("AUTO_INCREMENT")
                || upper.contains("IDENTITY")
                || (upper.contains("GENERATED") && upper.contains("IDENTITY"))) {
            return true;
        }
        String liveDef = live.defaultExpr();
        return liveDef != null && isSequenceDefault(liveDef);
    }

    /** PostgreSQL {@code nextval('seq')}, Oracle {@code "S"."ISEQ$$_1".nextval} / {@code seq.NEXTVAL}. */
    static boolean isSequenceDefault(String defaultExpr) {
        return defaultExpr != null
                && (defaultExpr.contains("nextval(") || defaultExpr.matches("(?is).*\\.\\s*nextval\\b.*"));
    }

    /**
     * Oracle stores INTEGER/INT/SMALLINT and ANSI NUMERIC/DECIMAL without precision as
     * {@code NUMBER(38,0)}, and DOUBLE PRECISION/REAL as FLOAT; compare those as the declared type.
     */
    static LiveColumn oracleComparableLive(LiveColumn live, ColumnSpec target, String definition) {
        boolean integerStorage = "NUMERIC".equals(live.baseType())
                && Integer.valueOf(38).equals(live.length())
                && (live.scale() == null || live.scale() == 0);
        boolean ansiUnboundedNumeric = "NUMERIC".equals(target.baseType()) && target.length() == null
                && definition != null && definition.trim().matches("(?is)^(?:NUMERIC|DECIMAL|DEC)\\b(?!\\s*\\().*");
        if (integerStorage && (ColumnDefinitionParser.integerRank(target.baseType()) > 0 || ansiUnboundedNumeric)) {
            return new LiveColumn(target.baseType(), target.length(), target.scale(), live.notNull(),
                    live.defaultExpr());
        }
        if ("FLOAT".equals(live.baseType()) && ColumnDefinitionParser.floatRank(target.baseType()) > 0) {
            return new LiveColumn(target.baseType(), null, null, live.notNull(), live.defaultExpr());
        }
        return live;
    }

    public static boolean isIgnorableSchemaTable(String tableName) {
        if (tableName == null || tableName.isBlank()) return true;
        String t = tableName.toLowerCase(Locale.ROOT);
        if ("flyway_schema_history".equals(t) || "schema_synchronizer_history".equals(t)) {
            return true;
        }
        // SQL Server master catalog helpers sometimes visible under dbo
        return "msreplication_options".equals(t)
                || "spt_fallback_db".equals(t)
                || "spt_fallback_dev".equals(t)
                || "spt_fallback_usg".equals(t)
                || "spt_monitor".equals(t)
                || "spt_values".equals(t);
    }

    public static boolean isIgnorableSchemaIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) return true;
        String i = indexName.toLowerCase(Locale.ROOT);
        return i.startsWith("flyway_schema_history_") || i.startsWith("schema_synchronizer_history_");
    }

    private static final Pattern PLAIN_COLUMN_LIST = Pattern.compile(
            "^\\((?:[a-z_][a-z0-9_]*(?: desc)?)(?:,[a-z_][a-z0-9_]*(?: desc)?)*\\)$");

    /**
     * Declared indexes must be readable back from the live catalog, or every later sync reports
     * drift. SQL Server and Oracle reconstruct only plain column lists (Oracle: ascending only).
     */
    static void requireComparableIndex(IndexDefinition index, DatabaseDialect dialect) {
        if (dialect != DatabaseDialect.SQLSERVER && dialect != DatabaseDialect.ORACLE) {
            return;
        }
        String structure = index.structure();
        boolean plain = index.predicateSql() == null && PLAIN_COLUMN_LIST.matcher(structure).matches();
        if (!plain || (dialect == DatabaseDialect.ORACLE && structure.contains(" desc"))) {
            throw new IllegalArgumentException(dialect.id() + " declared index " + index.name()
                    + " must be a plain column list"
                    + (dialect == DatabaseDialect.ORACLE ? " without DESC" : "")
                    + " (no WHERE, INCLUDE, expressions, or index options); "
                    + "manage it with an ordered change set instead");
        }
    }

    private void validateDeclarativeDefinition(SchemaDefinition definition, DatabaseDialect dialect) {
        validateDeclarative(definition, dialect, options);
    }

    /**
     * Package/public validation of the declarative {@code tables} section used by
     * {@link SchemaDefinitionValidator} and startup synchronization.
     */
    static void validateDeclarative(SchemaDefinition definition, DatabaseDialect dialect,
                                    SchemaSynchronizerOptions options) {
        if (definition == null) {
            throw new IllegalArgumentException("schema definition is null");
        }
        if (definition.tables() == null) {
            return;
        }
        for (Map.Entry<String, SchemaDefinition.TableDef> entry : definition.tables().entrySet()) {
            int maxIdent = dialect.maxIdentifierLength();
            String table = SqlIdentifiers.requireIdentifier(entry.getKey(), "table", maxIdent);
            SchemaDefinition.TableDef tableDef = entry.getValue();
            if (tableDef == null) {
                throw new IllegalArgumentException("table definition is null: " + table);
            }
            if (tableDef.createSql() != null) {
                NonDestructiveSqlPolicy.requireCreateTable(tableDef.createSql(), dialect);
                if (!dialect.supportsCreateTableIfNotExists()
                        && tableDef.createSql().matches("(?is)^\\s*CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\b.*")) {
                    throw new IllegalArgumentException(dialect.id()
                            + " does not support CREATE TABLE IF NOT EXISTS: " + table);
                }
                Matcher matcher = CREATE_TABLE_TARGET.matcher(tableDef.createSql());
                if (!matcher.matches()
                        || (matcher.group(1) != null
                        && !ChangeSetSchemaScope.sameNamespace(options.schema(), matcher.group(1), dialect))
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
                    String name = SqlIdentifiers.requireIdentifier(column.name(), "column", maxIdent);
                    if (!columns.add(name)) {
                        throw new IllegalArgumentException("duplicate column definition: " + table + "." + name);
                    }
                    if (column.definition() != null) {
                        columnSpecs.put(name, ColumnDefinitionParser.parse(column.definition()));
                    }
                }
            }
            for (String primaryKeyColumn : primaryKeyColumns(tableDef.createSql(), dialect)) {
                ColumnSpec spec = columnSpecs.get(primaryKeyColumn);
                if (spec != null && !spec.notNull()) {
                    throw new IllegalArgumentException("primary-key column must be declared NOT NULL: "
                            + table + "." + primaryKeyColumn);
                }
            }
            Set<String> indexes = new HashSet<>();
            if (tableDef.indexes() != null) {
                for (String sql : tableDef.indexes()) {
                    NonDestructiveSqlPolicy.requireCreateIndex(sql, dialect.supportsCreateIndexIfNotExists(), dialect);
                    IndexDefinition index = IndexDefinition.parse(sql, maxIdent);
                    requireComparableIndex(index, dialect);
                    if ((index.schema() != null
                            && !ChangeSetSchemaScope.sameNamespace(options.schema(), index.schema(), dialect))
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

    private void reconcileIndexes(Connection conn, DatabaseMetaData meta, String tableName,
                                  SchemaDefinition.TableDef tableDef, List<String> pendingSql,
                                  DatabaseDialect dialect, boolean applyChanges) throws SQLException {
        Map<String, String> live = new HashMap<>();
        if (dialect.isMySqlFamily()) {
            for (String sql : SchemaSnapshotWriter.readMySqlFamilyIndexes(conn, tableName, dialect)) {
                IndexDefinition index = IndexDefinition.parse(sql);
                if (live.put(index.name(), sql) != null) {
                    throw new IllegalStateException("duplicate live index name: " + index.name());
                }
            }
        } else if (dialect == DatabaseDialect.POSTGRESQL) {
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
        } else {
            String catalog = dialect.metadataCatalog(conn, options.schema());
            String schemaPattern = dialect.metadataSchemaPattern(options.schema());
            String metadataTable = dialect.metadataObjectName(tableName);
            Set<String> uniqueConstraintIndexes = SchemaSnapshotWriter.uniqueConstraintIndexNames(
                    conn, dialect, catalog, schemaPattern, metadataTable);
            Set<String> complexIndexes = SchemaSnapshotWriter.complexIndexNames(
                    conn, dialect, catalog, schemaPattern, metadataTable);
            for (String sql : SchemaSnapshotWriter.readJdbcIndexes(meta, dialect,
                    catalog, schemaPattern, metadataTable)) {
                IndexDefinition index = IndexDefinition.parse(sql);
                if (live.put(index.name(), sql) != null) {
                    throw new IllegalStateException("duplicate live index name: " + index.name());
                }
            }
            // UNIQUE-constraint indexes exist live but are omitted from JDBC reconstruct —
            // treat as present so we neither CREATE nor DROP.
            for (String name : uniqueConstraintIndexes) {
                live.putIfAbsent(name, "-- omitted-unique-constraint:" + name);
            }
            // FILTER / INCLUDE indexes cannot be compared to a simple CREATE INDEX declaration.
            for (String name : complexIndexes) {
                live.putIfAbsent(name, "-- omitted-complex-index:" + name);
            }
        }
        Set<String> expected = new HashSet<>();
        if (tableDef.indexes() != null) {
            for (String sql : tableDef.indexes()) {
                IndexDefinition target = IndexDefinition.parse(sql);
                expected.add(target.name());
                String liveSql = live.get(target.name());
                if (liveSql == null) {
                    if (applyChanges) {
                        execute(conn, dialect.executableSql(dialectCompatibleIndexSql(sql, dialect)));
                    }
                } else if (liveSql.startsWith("-- omitted-unique-constraint:")) {
                    // Present via UNIQUE constraint; do not recreate.
                } else if (liveSql.startsWith("-- omitted-complex-index:")) {
                    pendingSql.add("-- pending: live index " + target.name()
                            + " on " + tableName
                            + " has FILTER/INCLUDE (or similar) and cannot be compared to the declared "
                            + "definition; manage replacement via change sets");
                    pendingSql.add(dropIndexSql(dialect, target.name(), tableName));
                    pendingSql.add(terminated(dialectCompatibleIndexSql(sql, dialect)));
                } else if (!target.hasSameStructure(IndexDefinition.parse(liveSql))) {
                    addIndexReplacement(pendingSql, target, IndexDefinition.parse(liveSql), sql,
                            tableName, dialect);
                } else if (!target.hasEquivalentPredicate(IndexDefinition.parse(liveSql))) {
                    addIndexReplacement(pendingSql, target, IndexDefinition.parse(liveSql), sql,
                            tableName, dialect);
                } else if (!target.canonicalSql().equals(IndexDefinition.parse(liveSql).canonicalSql())) {
                    log.info("[SchemaSynchronizer] PostgreSQL normalized equivalent predicate casts for {}.{}",
                            tableName, target.name());
                }
            }
        }
        for (String liveName : live.keySet().stream().sorted().toList()) {
            if (!expected.contains(liveName)) {
                String liveSql = live.get(liveName);
                if (liveSql != null && (liveSql.startsWith("-- omitted-unique-constraint:")
                        || liveSql.startsWith("-- omitted-complex-index:"))) {
                    continue;
                }
                pendingSql.add(dropIndexSql(dialect, liveName, tableName)
                        + (dialect.isMySqlFamily() || dialect == DatabaseDialect.SQLSERVER
                        ? "" : " -- table=" + tableName));
            }
        }
    }

    static String dropIndexSql(DatabaseDialect dialect, String indexName, String tableName) {
        return switch (dialect) {
            case MYSQL, MARIADB, SQLSERVER ->
                    "DROP INDEX " + indexName + " ON " + tableName + ";";
            case POSTGRESQL -> "DROP INDEX IF EXISTS " + indexName + ";";
            // DROP INDEX IF EXISTS is 23ai+; 19c/21c reject it.
            case ORACLE -> "DROP INDEX " + indexName + ";";
        };
    }

    private void addIndexReplacement(List<String> pendingSql, IndexDefinition target,
                                     IndexDefinition live, String createSql, String tableName,
                                     DatabaseDialect dialect) {
        pendingSql.add("-- replace index definition drift for " + target.name()
                + "; live: " + live.canonicalSql());
        pendingSql.add(dropIndexSql(dialect, target.name(), tableName));
        pendingSql.add(terminated(dialectCompatibleIndexSql(createSql, dialect)));
    }

    private void reconcilePrimaryKey(DatabaseMetaData meta, String tableName,
                                     SchemaDefinition.TableDef tableDef, List<String> pendingSql,
                                     DatabaseDialect dialect)
            throws SQLException {
        if (tableDef.createSql() == null) {
            return;
        }
        List<String> expected = primaryKeyColumns(tableDef.createSql(), dialect);
        TreeMap<Short, String> orderedLive = new TreeMap<>();
        String constraintName = null;
        try (ResultSet rows = meta.getPrimaryKeys(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                dialect.metadataSchemaPattern(options.schema()), dialect.metadataObjectName(tableName))) {
            while (rows.next()) {
                short sequence = rows.getShort("KEY_SEQ");
                String column = SqlIdentifiers.requireIdentifier(rows.getString("COLUMN_NAME"), "primary-key column",
                        dialect.maxIdentifierLength());
                if (orderedLive.put(sequence, column) != null) {
                    throw new IllegalStateException("duplicate primary-key sequence for table: " + tableName);
                }
                String rowConstraint = rows.getString("PK_NAME");
                if (rowConstraint != null) {
                    rowConstraint = SqlIdentifiers.requireIdentifier(rowConstraint, "primary-key constraint",
                            dialect.maxIdentifierLength());
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
                pendingSql.add("ALTER TABLE " + tableName + (dialect.isMySqlFamily()
                        ? " DROP PRIMARY KEY" : " DROP CONSTRAINT " + constraintName)
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
                pendingSql.add("ALTER TABLE " + tableName + (dialect.isMySqlFamily()
                        ? " DROP PRIMARY KEY;" : " DROP CONSTRAINT " + constraintName + ";"));
                pendingSql.add("ALTER TABLE " + tableName + " ADD PRIMARY KEY ("
                        + String.join(", ", expected) + ");");
            }
        }
    }

    private Set<String> getLivePrimaryKeyColumns(DatabaseMetaData meta, String tableName,
                                                 DatabaseDialect dialect) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rows = meta.getPrimaryKeys(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                dialect.metadataSchemaPattern(options.schema()), dialect.metadataObjectName(tableName))) {
            while (rows.next()) {
                columns.add(SqlIdentifiers.requireIdentifier(
                        rows.getString("COLUMN_NAME"), "primary-key column", dialect.maxIdentifierLength()));
            }
        }
        return Set.copyOf(columns);
    }

    private String terminated(String sql) {
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    static List<String> primaryKeyColumns(String createSql, DatabaseDialect dialect) {
        if (createSql == null) {
            return List.of();
        }
        Matcher matcher = PRIMARY_KEY_COLUMNS.matcher(createSql);
        if (!matcher.find()) {
            Matcher inline = INLINE_PRIMARY_KEY.matcher(createSql);
            if (!inline.find()) {
                return List.of();
            }
            return List.of(SqlIdentifiers.requireIdentifier(inline.group(1), "primary-key column",
                    dialect.maxIdentifierLength()));
        }
        List<String> columns = new ArrayList<>();
        for (String raw : matcher.group(1).split(",")) {
            columns.add(SqlIdentifiers.requireIdentifier(raw.trim().replaceFirst("(?i)\\s+ASC$", ""),
                    "primary-key column", dialect.maxIdentifierLength()));
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

    private Set<String> getExistingTables(DatabaseMetaData meta, DatabaseDialect dialect) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = meta.getTables(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                dialect.metadataSchemaPattern(options.schema()), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    Map<String, LiveColumn> getLiveColumns(DatabaseMetaData meta, String tableName,
                                           DatabaseDialect dialect) throws SQLException {
        Map<String, LiveColumn> columns = new HashMap<>();
        Set<String> generatedDefaults = dialect == DatabaseDialect.MYSQL
                ? SchemaSnapshotWriter.mysqlGeneratedDefaultColumns(meta.getConnection(), tableName)
                : Set.of();
        try (ResultSet rs = meta.getColumns(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                dialect.metadataSchemaPattern(options.schema()),
                dialect.metadataObjectName(tableName.toLowerCase(Locale.ROOT)), "%")) {
            while (rs.next()) {
                // Oracle JDBC exposes COLUMN_DEF as LONG — read it before any other column.
                String colDefault = rs.getString("COLUMN_DEF");
                String name = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                boolean decimalDigitsNull = rs.wasNull();
                boolean notNull = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                if (dialect != DatabaseDialect.POSTGRESQL && "NULL".equalsIgnoreCase(colDefault)) {
                    colDefault = null;
                }
                if (dialect == DatabaseDialect.MYSQL) {
                    colDefault = SchemaSnapshotWriter.mysqlLiteralDefault(colDefault, typeName,
                            generatedDefaults.contains(name));
                }
                Integer length = null;
                Integer scale = null;
                String normalized = ColumnDefinitionParser.normalizeType(typeName);
                // Oracle MAX_STRING_SIZE=EXTENDED allows VARCHAR2/NVARCHAR2/RAW up to 32767.
                int boundedLimit = dialect == DatabaseDialect.ORACLE ? 32_768 : 10_000;
                if (ColumnDefinitionParser.hasLength(normalized)) {
                    if (size > 0 && size < boundedLimit) {
                        length = size;
                    } else if (("VARBINARY".equals(normalized) || "NVARCHAR".equals(normalized))
                            && (size <= 0 || size >= 10_000)) {
                        // SQL Server VARBINARY(MAX)/NVARCHAR(MAX) report COLUMN_SIZE as Integer.MAX_VALUE.
                        length = ColumnDefinitionParser.MAX_LENGTH;
                    }
                    // VARCHAR/CHAR with size >= 10_000 keep length=null (effectiveLength ≡ MAX).
                } else if ("NUMERIC".equals(normalized) && size > 0 && size <= 1_000) {
                    length = size;
                    scale = decimalDigitsNull ? null : decimalDigits;
                } else if (dialect == DatabaseDialect.POSTGRESQL && "VECTOR".equals(normalized)) {
                    length = readVectorDimension(meta.getConnection(), tableName, name);
                }
                columns.put(name, new LiveColumn(normalized, length, scale, notNull, colDefault));
            }
        }
        return columns;
    }

    private String mariaCatalog(DatabaseMetaData metadata) throws SQLException {
        Connection connection = metadata.getConnection();
        return connection != null && connection.getCatalog() != null ? connection.getCatalog() : options.schema();
    }

    static String mysqlCompatibleIndexSql(String sql, DatabaseDialect dialect) {
        return dialectCompatibleIndexSql(sql, dialect);
    }

    static String mysqlCompatibleAddColumnSql(String sql, DatabaseDialect dialect) {
        return dialectCompatibleAddColumnSql(sql, dialect);
    }

    static String dialectCompatibleIndexSql(String sql, DatabaseDialect dialect) {
        if (dialect.supportsCreateIndexIfNotExists()) {
            return sql;
        }
        return sql.replaceFirst("(?i)\\bINDEX\\s+IF\\s+NOT\\s+EXISTS\\s+", "INDEX ");
    }

    static String dialectCompatibleAddColumnSql(String sql, DatabaseDialect dialect) {
        if (dialect.supportsAddColumnIfNotExists()) {
            return sql;
        }
        return sql.replaceFirst("(?i)\\bADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+", "ADD COLUMN ");
    }

    static String addColumnSql(String tableName, String columnName, String definition, DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL, MARIADB -> "ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS "
                    + columnName + " " + definition;
            case MYSQL -> "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition;
            case SQLSERVER -> "ALTER TABLE " + tableName + " ADD " + columnName + " " + definition;
            case ORACLE -> "ALTER TABLE " + tableName + " ADD (" + columnName + " " + definition + ")";
        };
    }

    /**
     * SQL Server facts about the live column that decide whether ALTER COLUMN is executable.
     *
     * @param liveHasDefault a DEFAULT constraint exists (blocks base-type changes, not length changes)
     * @param baseTypeChanges live and declared base types differ (e.g. INT to BIGINT)
     * @param blockedReason why ALTER COLUMN cannot run as-is, or {@code null} when it can
     */
    record SqlServerColumnFacts(boolean liveHasDefault, boolean baseTypeChanges, String blockedReason) {
        static final SqlServerColumnFacts NONE = new SqlServerColumnFacts(false, false, null);
    }

    /** Catalog facts about one live SQL Server column that constrain ALTER COLUMN. */
    record SqlServerDependents(boolean found, boolean primaryKey, boolean foreignKey, boolean expressionDependency,
                               boolean computedFilterOrFullText, boolean index, boolean check, boolean userStatistics,
                               boolean deprecatedType, boolean nonDefaultCollation, boolean parameterizedType) {
        static final SqlServerDependents NONE =
                new SqlServerDependents(true, false, false, false, false, false, false, false, false, false, false);
    }

    private static SqlServerColumnFacts sqlServerColumnFacts(
            Connection conn, String schema, String tableName, String columnName, String definition,
            ColumnSpec target, LiveColumn live, NonDestructiveAlterPlanner.Plan plan) throws SQLException {
        Set<NonDestructiveAlterPlanner.Op> ops = plan.applyOps();
        boolean alterColumn = ops.contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE)
                || ops.contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
        boolean liveHasDefault = live.defaultExpr() != null && !live.defaultExpr().isBlank();
        boolean baseTypeChanges = !ColumnDefinitionParser.normalizeType(live.baseType()).equals(target.baseType());
        if (!alterColumn || !plan.pendingSql().isEmpty()) {
            return new SqlServerColumnFacts(liveHasDefault, baseTypeChanges, null);
        }
        return new SqlServerColumnFacts(liveHasDefault, baseTypeChanges, sqlServerBlockReason(
                sqlServerDependents(conn, schema, tableName, columnName), target, ops, baseTypeChanges,
                columnTypeText(definition)));
    }

    /**
     * SQL Server rejects ALTER COLUMN on key, FK, computed-column, and expression-dependency columns.
     * Index, CHECK, and CREATE STATISTICS dependencies allow only widening a bounded variable-length
     * type. ALTER COLUMN also resets collation to the database default and re-applies the declared
     * type text, so a non-default collation or a declaration that omits the live precision is pending.
     */
    static String sqlServerBlockReason(SqlServerDependents dependents, ColumnSpec target,
                                       Set<NonDestructiveAlterPlanner.Op> ops, boolean baseTypeChanges,
                                       String declaredTypeText) {
        if (!dependents.found()) {
            return "column was not found in the SQL Server catalog";
        }
        if (dependents.primaryKey() || dependents.foreignKey() || dependents.expressionDependency()
                || dependents.computedFilterOrFullText()) {
            return "column is used by a key, foreign key, computed column, filtered index/statistics predicate, "
                    + "full-text index, or view/function expression";
        }
        if (dependents.deprecatedType()) {
            return "text/ntext/image/timestamp columns cannot be altered in place";
        }
        if (dependents.nonDefaultCollation()) {
            return "column has a non-default collation that ALTER COLUMN would reset";
        }
        if (dependents.parameterizedType() && !declaredTypeText.contains("(")) {
            return "declared type omits the live length/precision, so ALTER COLUMN would change it";
        }
        boolean lengthOnlyWiden = !baseTypeChanges
                && ColumnDefinitionParser.isVariableLength(target.baseType())
                && target.length() != null && target.length() != ColumnDefinitionParser.MAX_LENGTH
                && ops.contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE)
                && !ops.contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
        if ((dependents.index() || dependents.check() || dependents.userStatistics()) && !lengthOnlyWiden) {
            return "column is used by an index, CHECK constraint, or statistics object";
        }
        return null;
    }

    private static SqlServerDependents sqlServerDependents(
            Connection conn, String schema, String tableName, String columnName) throws SQLException {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM sys.index_columns ic JOIN sys.indexes i
                     ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                   WHERE ic.object_id = c.object_id AND ic.column_id = c.column_id AND i.is_primary_key = 1),
                  (SELECT COUNT(*) FROM sys.foreign_key_columns f
                   WHERE (f.parent_object_id = c.object_id AND f.parent_column_id = c.column_id)
                      OR (f.referenced_object_id = c.object_id AND f.referenced_column_id = c.column_id)),
                  (SELECT COUNT(*) FROM sys.sql_expression_dependencies d
                   WHERE d.referenced_id = c.object_id AND d.referenced_minor_id = c.column_id),
                  (SELECT COUNT(*) FROM sys.computed_columns cc
                   WHERE cc.object_id = c.object_id
                     AND (cc.column_id = c.column_id OR CHARINDEX('[' + c.name + ']', cc.definition) > 0))
                  + (SELECT COUNT(*) FROM sys.indexes fi
                     WHERE fi.object_id = c.object_id AND fi.has_filter = 1
                       AND CHARINDEX('[' + c.name + ']', fi.filter_definition) > 0)
                  + (SELECT COUNT(*) FROM sys.stats fs
                     WHERE fs.object_id = c.object_id AND fs.has_filter = 1
                       AND CHARINDEX('[' + c.name + ']', fs.filter_definition) > 0)
                  + (SELECT COUNT(*) FROM sys.fulltext_index_columns ft
                     WHERE ft.object_id = c.object_id AND ft.column_id = c.column_id),
                  (SELECT COUNT(*) FROM sys.index_columns ic JOIN sys.indexes i
                     ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                   WHERE ic.object_id = c.object_id AND ic.column_id = c.column_id AND i.is_primary_key = 0),
                  (SELECT COUNT(*) FROM sys.check_constraints k
                   WHERE k.parent_object_id = c.object_id
                     AND (k.parent_column_id = c.column_id
                          OR (k.parent_column_id = 0 AND CHARINDEX('[' + c.name + ']', k.definition) > 0))),
                  (SELECT COUNT(*) FROM sys.stats_columns sc JOIN sys.stats st
                     ON st.object_id = sc.object_id AND st.stats_id = sc.stats_id
                   WHERE sc.object_id = c.object_id AND sc.column_id = c.column_id AND st.user_created = 1),
                  CASE WHEN c.system_type_id IN (34, 35, 99, 189) THEN 1 ELSE 0 END,
                  CASE WHEN c.collation_name IS NOT NULL
                        AND c.collation_name <> CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS sysname)
                       THEN 1 ELSE 0 END,
                  CASE WHEN TYPE_NAME(c.system_type_id) IN ('decimal', 'numeric', 'datetime2', 'time',
                        'datetimeoffset', 'varchar', 'nvarchar', 'char', 'nchar', 'varbinary', 'binary')
                       THEN 1 ELSE 0 END
                FROM sys.columns c
                JOIN sys.tables t ON t.object_id = c.object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = ? AND t.name = ? AND c.name = ?
                """;
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new SqlServerDependents(false, false, false, false, false, false, false, false,
                            false, false, false);
                }
                return new SqlServerDependents(true, rs.getInt(1) > 0, rs.getInt(2) > 0, rs.getInt(3) > 0,
                        rs.getInt(4) > 0, rs.getInt(5) > 0, rs.getInt(6) > 0, rs.getInt(7) > 0,
                        rs.getInt(8) > 0, rs.getInt(9) > 0, rs.getInt(10) > 0);
            }
        }
    }

    static NonDestructiveAlterPlanner.Plan sqlServerColumnPlan(
            String tableName, String columnName, String definition, NonDestructiveAlterPlanner.Plan plan,
            SqlServerColumnFacts facts) {
        ColumnSpec spec = ColumnDefinitionParser.parse(definition);
        String alterSql = "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName + " "
                + columnTypeText(definition) + (spec.notNull() ? " NOT NULL" : " NULL");
        if (!plan.pendingSql().isEmpty()) {
            return new NonDestructiveAlterPlanner.Plan(List.of(), List.of(
                    alterSql + "; -- pending: unsafe type/nullability change"));
        }
        Set<NonDestructiveAlterPlanner.Op> ops = requireOps(plan);
        boolean alterColumn = ops.contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE)
                || ops.contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL);
        boolean defaultChange = ops.contains(NonDestructiveAlterPlanner.Op.SET_DEFAULT)
                || ops.contains(NonDestructiveAlterPlanner.Op.DROP_DEFAULT);
        List<String> apply = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Set<NonDestructiveAlterPlanner.Op> applied = new HashSet<>();
        if (alterColumn) {
            if (facts.blockedReason() != null) {
                pending.add(alterSql + "; -- pending: " + facts.blockedReason()
                        + "; handle this ALTER COLUMN in a reviewed change set");
            } else if (facts.liveHasDefault() && facts.baseTypeChanges()) {
                pending.add(alterSql + "; -- pending: drop/recreate DEFAULT constraint around ALTER COLUMN "
                        + "(or manage via change sets)");
            } else {
                apply.add(alterSql);
                ops.stream().filter(op -> op == NonDestructiveAlterPlanner.Op.WIDEN_TYPE
                        || op == NonDestructiveAlterPlanner.Op.DROP_NOT_NULL).forEach(applied::add);
            }
        }
        if (defaultChange) {
            pending.add("-- pending: SQL Server DEFAULT for " + tableName + "." + columnName
                    + " differs from the declaration ("
                    + (spec.defaultExpr() == null ? "no default" : "DEFAULT " + spec.defaultExpr())
                    + "); replace the DEFAULT constraint via a change set");
        }
        return new NonDestructiveAlterPlanner.Plan(apply, pending, applied);
    }

    static NonDestructiveAlterPlanner.Plan oracleColumnPlan(
            String tableName, String columnName, String definition, NonDestructiveAlterPlanner.Plan plan) {
        ColumnSpec spec = ColumnDefinitionParser.parse(definition);
        if (!plan.pendingSql().isEmpty()) {
            // Oracle column grammar: type, then DEFAULT, then inline constraints.
            return new NonDestructiveAlterPlanner.Plan(List.of(), List.of(
                    "ALTER TABLE " + tableName + " MODIFY (" + columnName + " " + columnTypeText(definition)
                            + (spec.defaultExpr() == null ? "" : " DEFAULT " + spec.defaultExpr())
                            + (spec.notNull() ? " NOT NULL" : "")
                            + "); -- pending: unsafe type/nullability change"));
        }
        Set<NonDestructiveAlterPlanner.Op> ops = requireOps(plan);
        List<String> clauses = new ArrayList<>();
        if (ops.contains(NonDestructiveAlterPlanner.Op.WIDEN_TYPE)) {
            clauses.add(columnTypeText(definition));
        }
        if (ops.contains(NonDestructiveAlterPlanner.Op.SET_DEFAULT)) {
            clauses.add("DEFAULT " + spec.defaultExpr());
        } else if (ops.contains(NonDestructiveAlterPlanner.Op.DROP_DEFAULT)) {
            clauses.add("DEFAULT NULL");
        }
        if (ops.contains(NonDestructiveAlterPlanner.Op.DROP_NOT_NULL)) {
            clauses.add("NULL");
        }
        if (clauses.isEmpty()) {
            return plan;
        }
        return new NonDestructiveAlterPlanner.Plan(List.of(
                "ALTER TABLE " + tableName + " MODIFY (" + columnName + " " + String.join(" ", clauses) + ")"),
                List.of(), ops);
    }

    private static Set<NonDestructiveAlterPlanner.Op> requireOps(NonDestructiveAlterPlanner.Plan plan) {
        if (!plan.applySql().isEmpty() && plan.applyOps().isEmpty()) {
            throw new IllegalStateException("dialect column rewrite requires planner operations: " + plan.applySql());
        }
        return plan.applyOps();
    }

    /** Declared column type text without DEFAULT, nullability, or identity clauses. */
    static String columnTypeText(String definition) {
        String rest = definition.trim()
                .replaceAll("(?i)\\s+AUTO_INCREMENT\\b", "")
                .replaceAll("(?i)\\s+GENERATED\\s+(?:BY\\s+DEFAULT|ALWAYS)\\s+AS\\s+IDENTITY(?:\\s*\\([^)]*\\))?", "")
                .replaceAll("(?i)\\s+IDENTITY(?:\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\))?", "");
        Matcher defaultClause = Pattern.compile("(?i)\\s+DEFAULT\\s+").matcher(rest);
        if (defaultClause.find()) {
            rest = rest.substring(0, defaultClause.start());
        }
        return rest.replaceAll("(?i)\\s+NOT\\s+NULL\\b", "").replaceAll("(?i)\\s+NULL\\b", "").trim();
    }

    static String stripDefaultClause(String definition) {
        if (definition == null) {
            return "";
        }
        return definition.replaceAll("(?i)\\s+DEFAULT\\s+\\S.*$", "").trim();
    }

    private void bindSqlServerSchema(Connection conn, String schema) throws SQLException {
        SqlIdentifiers.requireIdentifier(schema, "schema");
        conn.setSchema(schema);
        String bound = conn.getSchema();
        // SQL Server resolves unqualified names through the login's default schema;
        // collation may be case-sensitive, so require an exact match.
        if (bound == null || !bound.equals(schema)) {
            throw new IllegalStateException("Connected SQL Server default schema '" + bound
                    + "' does not match configured schema '" + schema
                    + "' (compared case-sensitively; set the login's DEFAULT_SCHEMA)");
        }
    }

    private void requireOracleSchema(Connection conn, String schema) throws SQLException {
        String user = conn.getMetaData().getUserName();
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("Oracle JDBC metadata did not report a user name");
        }
        if (!user.equals(ChangeSetSchemaScope.canonical(schema, false, DatabaseDialect.ORACLE))) {
            throw new IllegalStateException("Connected Oracle user '" + user
                    + "' does not match configured schema '" + schema + "'");
        }
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
                log.info("[SchemaSynchronizer] Added IDENTITY on {}.id", tableName);
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
