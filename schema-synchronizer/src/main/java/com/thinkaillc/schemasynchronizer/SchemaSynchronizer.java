// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        List<SchemaDefinition.ChangeSet> allChanges = executor.validateStructure(def.changes());
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
            // Must precede all DDL: policy rejections for unrecorded change sets happen here.
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
                    logCleanupFailure(dialect, cleanupFailure);
                }
            }
        }
    }

    /** Runs after the work is committed (or handed to the caller's transaction); throwing would misreport it. */
    private static void logCleanupFailure(DatabaseDialect dialect, Exception cleanupFailure) {
        log.warn("[SchemaSynchronizer] Synchronization completed, but releasing the {} lock or restoring "
                + "the connection failed; the session may keep the lock until the connection closes",
                dialect.id(), cleanupFailure);
    }

    private SchemaSynchronizationResult synchronizeImplicitDdlDialect(Connection conn, SchemaDefinition def,
                                                                      DatabaseDialect dialect) throws Exception {
        validateDeclarativeDefinition(def, dialect);
        ChangeSetExecutor executor = new ChangeSetExecutor();
        List<SchemaDefinition.ChangeSet> allChanges = executor.validateStructure(def.changes());
        plannedSql.set(new ArrayList<>());
        if (dialect.usesCatalogNamespace()) {
            // Unqualified DDL runs in DATABASE(); metadata reads are bound to the configured schema.
            String current = mySqlCurrentDatabase(conn);
            if (!options.schema().equals(current)) {
                throw new IllegalStateException("Connected " + dialect.id() + " database '" + current
                        + "' does not match configured schema '" + options.schema()
                        + "' (compared case-sensitively; configure the catalog exactly as the server reports it)");
            }
        }
        if (dialect == DatabaseDialect.ORACLE) {
            requireOracleSchema(conn, options.schema());
        }
        String lockToken = null;
        Exception primaryFailure = null;
        try {
            lockToken = DialectSupport.acquireLock(conn, dialect, options.schema(), options.advisoryLockId());
            // Must precede all DDL: policy rejections for unrecorded change sets happen here.
            executor.validateHistory(conn, allChanges, options, dialect);
            DeclarativeResult preflight = applyDeclarativeSchema(conn, def, dialect, false);
            if (options.failOnPending() && !preflight.pendingSql().isEmpty()) {
                throw new IllegalStateException("unsafe or destructive schema differences require manual resolution: "
                        + String.join(" | ", preflight.pendingSql()));
            }
            List<SchemaDefinition.ChangeSet> beforeSchema =
                    changesForPhase(allChanges, SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA);
            // The preflight saw the schema before these change sets; the apply pass may plan TIMESTAMP DDL it did not.
            List<String> timestampTables = dialect.isMySqlFamily() ? timestampTables(def) : List.of();
            if (!timestampTables.isEmpty()) {
                for (SchemaDefinition.ChangeSet change
                        : executor.unappliedUnverified(conn, beforeSchema, options, dialect)) {
                    String table = referencedTable(change.statements(), timestampTables);
                    if (table != null) {
                        requireExplicitTimestampDefaults(mySqlExplicitDefaultsForTimestamp(conn),
                                "reconciling TIMESTAMP columns of " + table + " after BEFORE_SCHEMA change set "
                                        + change.id());
                        break;
                    }
                }
            }
            ChangeSetExecutor.Result before = executor.apply(conn, beforeSchema, options, dialect);
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
                    logCleanupFailure(dialect, releaseFailure);
                } else {
                    primaryFailure.addSuppressed(releaseFailure);
                }
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
        Boolean explicitTimestamps = null;

        for (Map.Entry<String, SchemaDefinition.TableDef> entry : def.tables().entrySet()) {
            String tableName = entry.getKey().toLowerCase(Locale.ROOT);
            SqlIdentifiers.requireIdentifier(tableName, "table", dialect.maxIdentifierLength());
            SchemaDefinition.TableDef tableDef = entry.getValue();
            Set<String> livePrimaryKeyColumns = existingTables.contains(tableName)
                    ? getLivePrimaryKeyColumns(meta, tableName, dialect) : Set.of();
            List<String> deferredNullabilitySql = new ArrayList<>();
            Set<String> unaddedColumns = new HashSet<>();

            if (!existingTables.contains(tableName)) {
                if (tableDef.createSql() != null) {
                    NonDestructiveSqlPolicy.requireCreateTable(tableDef.createSql(), dialect);
                    // CREATE TABLE IF NOT EXISTS would create the base table, then later DDL would hit the TEMPORARY one.
                    requireNoTemporaryShadow(conn, dialect, tableName);
                    if (dialect.isMySqlFamily() && tableDeclaresTimestamp(tableDef)) {
                        explicitTimestamps = explicitTimestamps != null ? explicitTimestamps
                                : mySqlExplicitDefaultsForTimestamp(conn);
                        requireExplicitTimestampDefaults(explicitTimestamps, "creating table " + tableName);
                    }
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
                Map<String, String> liveOnUpdate = dialect.isMySqlFamily() && !liveColumns.isEmpty()
                        ? SchemaSnapshotWriter.mysqlOnUpdate(conn, options.schema(), tableName) : Map.of();
                Set<String> targetColumns = new HashSet<>();

                for (SchemaDefinition.ColumnDef col : tableDef.columns()) {
                    String colName = col.name().toLowerCase(Locale.ROOT);
                    SqlIdentifiers.requireIdentifier(colName, "column", dialect.maxIdentifierLength());
                    targetColumns.add(colName);
                    if (!liveColumns.containsKey(colName)) {
                        if (col.definition() != null) {
                            ColumnSpec added = ColumnDefinitionParser.parse(col.definition());
                            if (dialect.isMySqlFamily() && "TIMESTAMP".equals(added.baseType())) {
                                explicitTimestamps = explicitTimestamps != null ? explicitTimestamps
                                        : mySqlExplicitDefaultsForTimestamp(conn);
                                requireExplicitTimestampDefaults(explicitTimestamps,
                                        "adding column " + tableName + "." + colName);
                            }
                            String sql = addColumnSql(tableName, col.name(), col.definition(), dialect);
                            String unstorable = dialect.isMySqlFamily()
                                    ? mySqlUnstorableAddedDefault(conn, tableName, col.definition()) : null;
                            if (unstorable != null) {
                                pendingSql.add(sql + "; -- pending: " + unstorable);
                                unaddedColumns.add(colName);
                                continue;
                            }
                            requireNoTemporaryShadow(conn, dialect, tableName);
                            if (applyChanges) {
                                execute(conn, sql);
                                log.info("[SchemaSynchronizer] Added column {}.{}", tableName, col.name());
                                columnsAdded++;
                            }
                        } else {
                            pendingSql.add("-- pending: column " + tableName + "." + colName
                                    + " is missing and its definition is absent");
                            unaddedColumns.add(colName);
                        }
                        continue;
                    }

                    if (col.definition() == null || shouldSkipAlter(col.definition(), liveColumns.get(colName))) {
                        continue;
                    }
                    try {
                        ColumnSpec target = withDefaultNumericPrecision(withDefaultFractionalPrecision(
                                foldNationalType(ColumnDefinitionParser.parse(col.definition()), dialect), dialect),
                                col.definition(), dialect);
                        if (dialect == DatabaseDialect.ORACLE) {
                            target = oracleComparableTarget(target);
                        }
                        LiveColumn comparableLive = dialect == DatabaseDialect.ORACLE
                                ? oracleComparableLive(liveColumns.get(colName), target)
                                : liveColumns.get(colName);
                        if (dialect.isMySqlFamily()) {
                            target = new ColumnSpec(target.baseType(), target.length(), target.scale(),
                                    target.notNull(),
                                    mySqlComparableDefault(target.defaultExpr(), target.baseType(), target.length()));
                            comparableLive = new LiveColumn(comparableLive.baseType(), comparableLive.length(),
                                    comparableLive.scale(), comparableLive.notNull(),
                                    mySqlComparableDefault(comparableLive.defaultExpr(), target.baseType(),
                                            comparableLive.length()));
                        }
                        if (!dialect.isMySqlFamily() && ColumnDefinitionParser.onUpdateExpr(col.definition()) != null) {
                            throw new IllegalArgumentException("ON UPDATE is supported only on MySQL/MariaDB");
                        }
                        NonDestructiveAlterPlanner.Plan plan =
                                NonDestructiveAlterPlanner.plan(tableName, col.name(), target, comparableLive);
                        if (dialect.isMySqlFamily()) {
                            boolean national = mySqlDeclaresNational(col.definition());
                            boolean planned = !plan.applySql().isEmpty() || !plan.pendingSql().isEmpty();
                            MySqlColumnFacts facts = planned || national
                                    ? mySqlColumnFacts(conn, options.schema(), tableName, colName) : null;
                            String blockedReason = planned ? mySqlBlockReason(facts, col.definition()) : null;
                            if (blockedReason == null && mySqlUnpredictableDefaultChange(plan, target)) {
                                blockedReason = mySqlUnpredictableDefaultReason(
                                        ColumnDefinitionParser.parse(col.definition()).defaultExpr(),
                                        liveColumns.get(colName).defaultExpr());
                            }
                            if (blockedReason == null && planned) {
                                blockedReason = mySqlUnstorableDefault(conn, col.definition(), facts.characterSet());
                            }
                            plan = mySqlFamilyColumnPlan(tableName, col.name(), col.definition(), plan, blockedReason);
                            plan = mySqlNationalDriftPlan(tableName, col.name(), col.definition(),
                                    mySqlOnUpdateDrift(effectiveOnUpdate(col.definition(), target, dialect),
                                            liveOnUpdate.get(colName)), plan);
                            if (!plan.applySql().isEmpty() && "TIMESTAMP".equals(target.baseType())) {
                                explicitTimestamps = explicitTimestamps != null ? explicitTimestamps
                                        : mySqlExplicitDefaultsForTimestamp(conn);
                                requireExplicitTimestampDefaults(explicitTimestamps,
                                        "modifying column " + tableName + "." + colName);
                            }
                            if (national && plan.applySql().isEmpty() && plan.pendingSql().isEmpty()) {
                                plan = mySqlNationalDriftPlan(tableName, col.name(), col.definition(),
                                        mySqlNationalDrift(facts), plan);
                            }
                        } else if (dialect == DatabaseDialect.SQLSERVER) {
                            LiveColumn liveCol = liveColumns.get(colName);
                            plan = sqlServerColumnPlan(tableName, col.name(), col.definition(), plan,
                                    sqlServerColumnFacts(conn, options.schema(), tableName, colName,
                                            col.definition(), target, liveCol, plan));
                        } else if (dialect == DatabaseDialect.ORACLE) {
                            plan = oracleColumnPlan(tableName, col.name(), col.definition(), plan);
                        }
                        if (!plan.applySql().isEmpty()) {
                            requireNoTemporaryShadow(conn, dialect, tableName);
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
            reconcileIndexes(conn, meta, tableName, tableDef, pendingSql, dialect, applyChanges, unaddedColumns);

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
                            + " " + definition + "; -- pending: unsafe type/nullability change"
                            + (blockedReason == null ? "" : "; also " + blockedReason)));
        }
        if (!plan.applySql().isEmpty()) {
            return new NonDestructiveAlterPlanner.Plan(List.of(
                    "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition), List.of(),
                    requireOps(plan));
        }
        return plan;
    }

    private static final Pattern MYSQL_NOW_SYNONYM = Pattern.compile(
            "(?i)^(CURRENT_TIMESTAMP|LOCALTIMESTAMP|LOCALTIME|NOW)\\s*(\\(\\s*(\\d*)\\s*\\))?$");
    private static final Pattern MYSQL_TEMPORAL_FRACTION = Pattern.compile("^'([^']*?)\\.(\\d*)'$");
    private static final Pattern MYSQL_CURRENT_TIMESTAMP = Pattern.compile("^CURRENT_TIMESTAMP(?:\\((\\d+)\\))?$");
    private static final Pattern MYSQL_DATETIME_LITERAL = Pattern.compile(
            "^'\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.(\\d+))?'$");
    private static final Pattern MYSQL_TIME_LITERAL = Pattern.compile("^'\\d{2}:\\d{2}:\\d{2}(?:\\.(\\d+))?'$");
    private static final Pattern MYSQL_DECIMAL_LITERAL = Pattern.compile("^-?\\d+(?:\\.(\\d+))?$");
    private static final Pattern MYSQL_BIT_LITERAL = Pattern.compile("^(?i)b'([01]+)'$");
    private static final Pattern MYSQL_HEX_LITERAL = Pattern.compile("^(?:0x([0-9A-Fa-f]+)|[xX]'([0-9A-Fa-f]*)')$");
    private static final Pattern MYSQL_STRING_LITERAL = Pattern.compile("^'(?:[^'\\\\]|'')*'$");
    private static final Pattern MYSQL_STRING_LITERAL_WITH_ESCAPES = Pattern.compile("^'(?:[^'\\\\]|''|\\\\.)*'$",
            Pattern.DOTALL);
    private static final Set<String> MYSQL_NUMERIC_DEFAULT_TYPES = Set.of("INTEGER", "BIGINT", "SMALLINT",
            "TINYINT", "MEDIUMINT", "NUMERIC", "REAL", "DOUBLE PRECISION", "FLOAT", "BOOLEAN", "BIT");
    private static final Set<String> MYSQL_INTEGER_DEFAULT_TYPES = Set.of("INTEGER", "BIGINT", "SMALLINT",
            "TINYINT", "MEDIUMINT");
    private static final Set<String> MYSQL_TEMPORAL_DEFAULT_TYPES = Set.of("DATE", "DATETIME", "TIMESTAMP", "TIME");

    /**
     * A MySQL/MariaDB default in the form the server stores it: NOW()/LOCALTIMESTAMP synonyms fold to
     * CURRENT_TIMESTAMP[(n)], numeric and bit literals compare by value, zero fraction digits are dropped.
     * YEAR is left alone: quoted and unquoted values map to different years.
     */
    static String mySqlComparableDefault(String defaultExpr, String normalizedType) {
        return mySqlComparableDefault(defaultExpr, normalizedType, null);
    }

    static String mySqlComparableDefault(String defaultExpr, String normalizedType, Integer length) {
        if (defaultExpr == null) {
            return null;
        }
        String d = ColumnDefinitionParser.stripOuterParentheses(defaultExpr);
        if (normalizedType.equals("BINARY") || normalizedType.equals("VARBINARY")) {
            return mySqlBinaryDefault(d, normalizedType.equals("BINARY") ? length : null, defaultExpr);
        }
        Matcher now = MYSQL_NOW_SYNONYM.matcher(d);
        if (now.matches() && (now.group(2) != null || !now.group(1).equalsIgnoreCase("NOW"))) {
            String digits = now.group(3);
            return digits == null || digits.isEmpty() || Integer.parseInt(digits) == 0
                    ? "CURRENT_TIMESTAMP" : "CURRENT_TIMESTAMP(" + Integer.parseInt(digits) + ")";
        }
        if (MYSQL_NUMERIC_DEFAULT_TYPES.contains(normalizedType)
                || MYSQL_NUMERIC_DEFAULT_TYPES.contains(ColumnDefinitionParser.normalizeType(
                        normalizedType.replaceFirst("(?i)\\s+UNSIGNED(?:\\s+ZEROFILL)?$", "")))) {
            Matcher bits = MYSQL_BIT_LITERAL.matcher(d);
            if (bits.matches()) {
                return new java.math.BigInteger(bits.group(1), 2).toString();
            }
            if (d.equalsIgnoreCase("TRUE")) {
                return "1";
            }
            if (d.equalsIgnoreCase("FALSE")) {
                return "0";
            }
            String literal = d.length() >= 2 && d.startsWith("'") && d.endsWith("'") ? d.substring(1, d.length() - 1) : d;
            try {
                java.math.BigDecimal value = new java.math.BigDecimal(literal.trim());
                // toPlainString of 1E100000000 would build a hundred million digits.
                value = value.stripTrailingZeros();
                if (Math.abs((long) value.precision() - value.scale()) > MYSQL_MAX_DEFAULT_DIGITS) {
                    return value.toString();
                }
                return value.toPlainString();
            } catch (NumberFormatException notNumeric) {
                return defaultExpr;
            }
        }
        if (MYSQL_TEMPORAL_DEFAULT_TYPES.contains(normalizedType)) {
            Matcher fraction = MYSQL_TEMPORAL_FRACTION.matcher(d);
            if (fraction.matches()) {
                String digits = fraction.group(2).replaceFirst("0+$", "");
                return "'" + fraction.group(1) + (digits.isEmpty() ? "" : "." + digits) + "'";
            }
        }
        return defaultExpr;
    }

    /**
     * MySQL reports binary defaults as {@code 0x6162}, MariaDB as {@code 'ab'}; both, and {@code X'6162'},
     * compare as {@code X'6162'}. BINARY(n) pads with zero bytes to n.
     */
    private static String mySqlBinaryDefault(String d, Integer fixedLength, String original) {
        String hex;
        Matcher hexLiteral = MYSQL_HEX_LITERAL.matcher(d);
        if (hexLiteral.matches()) {
            hex = (hexLiteral.group(1) != null ? hexLiteral.group(1) : hexLiteral.group(2)).toUpperCase(Locale.ROOT);
            if (hex.length() % 2 != 0) {
                hex = "0" + hex;
            }
        } else if (d.matches("-?\\d+")) {
            // A bare integer is stored as its decimal text: DEFAULT 007 is '7'.
            hex = java.util.HexFormat.of().withUpperCase().formatHex(
                    new java.math.BigInteger(d).toString().getBytes(StandardCharsets.US_ASCII));
        } else if (MYSQL_STRING_LITERAL.matcher(d).matches() && d.chars().allMatch(c -> c < 0x80)) {
            // The server stores a string literal in the session's character_set_client; only ASCII is the same bytes in all of them.
            byte[] bytes = d.substring(1, d.length() - 1).replace("''", "'").getBytes(StandardCharsets.US_ASCII);
            hex = java.util.HexFormat.of().withUpperCase().formatHex(bytes);
        } else {
            return original;
        }
        if (fixedLength != null && hex.length() < fixedLength * 2) {
            hex = hex + "0".repeat(fixedLength * 2 - hex.length());
        }
        return "X'" + hex + "'";
    }

    /**
     * MySQL/MariaDB round, truncate, and rewrite defaults (expressions, BIT, YEAR, FLOAT, excess
     * scale or fraction digits), so auto-applying such a SET DEFAULT would re-run MODIFY COLUMN on
     * every sync. Dropping a default always converges.
     */
    static boolean mySqlUnpredictableDefaultChange(NonDestructiveAlterPlanner.Plan plan, ColumnSpec comparableTarget) {
        return plan.applyOps().contains(NonDestructiveAlterPlanner.Op.SET_DEFAULT)
                && !mySqlStoredAsDeclared(comparableTarget);
    }

    /** Whether the server stores this canonical default exactly, for the column's type, scale and precision. */
    static boolean mySqlStoredAsDeclared(ColumnSpec comparableTarget) {
        String d = comparableTarget.defaultExpr();
        if (d == null) {
            return true;
        }
        String type = comparableTarget.baseType().replaceFirst(" UNSIGNED$", "");
        int precision = comparableTarget.length() == null ? 0 : comparableTarget.length();
        if (type.equals("DATETIME") || type.equals("TIMESTAMP")) {
            Matcher now = MYSQL_CURRENT_TIMESTAMP.matcher(d);
            if (now.matches()) {
                return (now.group(1) == null ? 0 : Integer.parseInt(now.group(1))) == precision;
            }
            // TIMESTAMP literals are stored in UTC and read back in the session time zone.
            return type.equals("DATETIME") && fractionDigits(MYSQL_DATETIME_LITERAL.matcher(d)) <= precision;
        }
        if (type.equals("TIME")) {
            return fractionDigits(MYSQL_TIME_LITERAL.matcher(d)) <= precision;
        }
        if (type.equals("DATE")) {
            return d.matches("^'\\d{4}-\\d{2}-\\d{2}'$");
        }
        if (MYSQL_INTEGER_DEFAULT_TYPES.contains(type)) {
            return d.matches("^-?\\d+$");
        }
        if (type.equals("NUMERIC")) {
            int scale = comparableTarget.scale() == null ? 0 : comparableTarget.scale();
            return fractionDigits(MYSQL_DECIMAL_LITERAL.matcher(d)) <= scale;
        }
        if (ColumnDefinitionParser.hasLength(type) && !type.contains("BINARY") && !type.equals("BIT")) {
            // MariaDB reports control characters backslash-escaped, so they never compare equal.
            return MYSQL_STRING_LITERAL.matcher(d).matches() && !d.matches("(?s).*\\s'$")
                    && !d.matches("(?s).*\\p{Cntrl}.*");
        }
        return false;
    }

    /** Fraction digits of a matched literal (group 1), or {@link Integer#MAX_VALUE} if it does not match. */
    private static int fractionDigits(Matcher literal) {
        if (!literal.matches()) {
            return Integer.MAX_VALUE;
        }
        return literal.group(1) == null ? 0 : literal.group(1).length();
    }

    static String mySqlUnpredictableDefaultReason(String declaredDefault, String liveDefault) {
        if (declaredDefault != null && MYSQL_STRING_LITERAL_WITH_ESCAPES.matcher(declaredDefault).matches()
                && (declaredDefault.indexOf('\\') >= 0 || !declaredDefault.chars().allMatch(c -> c < 0x80))
                && liveDefault != null && liveDefault.matches("(?i)0x[0-9a-f]*|x'[0-9a-f]*'")) {
            return "DEFAULT " + declaredDefault + " stores bytes that depend on the session character set and"
                    + " sql_mode (server reports " + liveDefault + "); declare the bytes as X'…'";
        }
        return "DEFAULT " + declaredDefault + " is not auto-applied because the server may store it rewritten"
                + " (server reports " + (liveDefault == null ? "no default" : liveDefault)
                + "); declare it as a snapshot writes it";
    }

    /** MySQL Connector/J returns SQL NULL for "no default"; the text NULL is the string default 'NULL'. */
    static boolean reportsNoDefaultAsNullText(DatabaseDialect dialect) {
        return dialect != DatabaseDialect.POSTGRESQL && dialect != DatabaseDialect.MYSQL;
    }

    private static final Pattern TIMESTAMP_COLUMN_TYPE = Pattern.compile(
            "(?i)(?:^|[,(])\\s*(?:`(?:[^`]|``)+`\\s*|\"(?:[^\"]|\"\")+\"\\s*|[\\p{L}\\p{N}_$#@]+\\s+)TIMESTAMP\\b");

    /** Whether a MySQL CREATE TABLE declares a column of type TIMESTAMP (not a column named timestamp). */
    static boolean declaresTimestampColumn(String createSql) {
        return createSql != null && TIMESTAMP_COLUMN_TYPE.matcher(
                SqlLexer.mask(createSql, SqlLexer.Mode.MYSQL, true, false)).find();
    }

    /** Tables whose createSql or column list declares a TIMESTAMP column. */
    static List<String> timestampTables(SchemaDefinition def) {
        return def.tables() == null ? List.of() : def.tables().entrySet().stream()
                .filter(entry -> tableDeclaresTimestamp(entry.getValue()))
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    /**
     * The first of {@code tables} that a statement names as a token outside literals and comments,
     * or null. Matching ignores case: a false match only refuses the sync.
     */
    static String referencedTable(List<String> statements, List<String> tables) {
        for (String statement : statements) {
            String code = SqlLexer.mask(statement, SqlLexer.Mode.MYSQL, true, false);
            // Only table DDL can change what the next pass does to a TIMESTAMP column.
            if (!MYSQL_TABLE_DDL_HEAD.matcher(code).lookingAt()) {
                continue;
            }
            for (String table : tables) {
                if (namesToken(code, table)) {
                    return table;
                }
            }
        }
        return null;
    }

    private static final Pattern MYSQL_TABLE_DDL_HEAD = Pattern.compile(
            "(?is)\\s*(?:CREATE|ALTER|DROP|RENAME)\\s+(?:[A-Z_]+\\s+)*?TABLES?\\b");

    /** The first of {@code columns} (sorted) that an index names in its key or predicate, or null. */
    static String indexedColumnAmong(IndexDefinition index, Set<String> columns) {
        String raw = index.structure() + (index.predicateSql() == null ? "" : " " + index.predicateSql());
        String text;
        try {
            text = SqlLexer.mask(raw, SqlLexer.Mode.POSTGRES, true, false);
        } catch (IllegalArgumentException untokenizable) {
            text = raw;
        }
        String code = text;
        return columns.stream().sorted().filter(column -> namesToken(code, column)).findFirst().orElse(null);
    }

    private static boolean namesToken(String text, String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_$])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_$])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    /** A created table is reconciled against its column list right away, so both can issue TIMESTAMP DDL. */
    static boolean tableDeclaresTimestamp(SchemaDefinition.TableDef table) {
        if (declaresTimestampColumn(table.createSql())) {
            return true;
        }
        return table.columns() != null && table.columns().stream().anyMatch(column -> column.definition() != null
                && "TIMESTAMP".equals(ColumnDefinitionParser.parse(column.definition()).baseType()));
    }

    /**
     * With explicit_defaults_for_timestamp OFF (the MariaDB default before 10.10), CREATE, ADD, and
     * MODIFY give a TIMESTAMP column an undeclared NOT NULL and DEFAULT/ON UPDATE CURRENT_TIMESTAMP.
     */
    static void requireExplicitTimestampDefaults(boolean explicitDefaultsForTimestamp, String action) {
        if (!explicitDefaultsForTimestamp) {
            throw new IllegalStateException("explicit_defaults_for_timestamp is OFF, so " + action
                    + " would give a TIMESTAMP column an undeclared NOT NULL and DEFAULT/ON UPDATE CURRENT_TIMESTAMP;"
                    + " enable it in the server configuration, or with"
                    + " sessionVariables=explicit_defaults_for_timestamp=1 where the server allows a session value");
        }
    }

    /** MySQL/MariaDB resolve an unqualified table name to a session TEMPORARY table first. */
    private void requireNoTemporaryShadow(Connection conn, DatabaseDialect dialect, String tableName)
            throws SQLException {
        if (dialect.isMySqlFamily()) {
            SchemaSnapshotWriter.requireNoTemporaryShadow(conn, options.schema(), tableName);
        }
    }

    static String mySqlCurrentDatabase(Connection conn) throws SQLException {
        try (var statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    static boolean mySqlExplicitDefaultsForTimestamp(Connection conn) throws SQLException {
        try (var statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SELECT @@explicit_defaults_for_timestamp")) {
            return rows.next() && rows.getInt(1) == 1;
        }
    }

    private static final Map<String, java.math.BigInteger[]> MYSQL_INTEGER_RANGES = Map.of(
            "TINYINT", integerRange(8), "SMALLINT", integerRange(16), "MEDIUMINT", integerRange(24),
            "INTEGER", integerRange(32), "INT", integerRange(32), "BIGINT", integerRange(64));

    private static java.math.BigInteger[] integerRange(int bits) {
        java.math.BigInteger half = java.math.BigInteger.ONE.shiftLeft(bits - 1);
        return new java.math.BigInteger[] {half.negate(), half.subtract(java.math.BigInteger.ONE),
                java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE)};
    }

    /**
     * Defaults MySQL/MariaDB reject at DDL time (error 1064/1067 in strict mode) fail validation
     * instead, before any auto-committed DDL: odd-length X'…', integers outside the type's range,
     * decimals with too many integer digits, and strings longer than the column. A string with a
     * backslash is left to the server: its length depends on the session's NO_BACKSLASH_ESCAPES.
     * MySQL evaluates a parenthesized default as an expression on insert; MariaDB checks it as a literal.
     */
    static void requireMySqlDefaultFits(ColumnSpec spec, String definition, DatabaseDialect dialect, String column) {
        String d = spec.defaultExpr() == null ? null : ColumnDefinitionParser.stripOuterParentheses(spec.defaultExpr());
        if (!dialect.isMySqlFamily() || d == null) {
            return;
        }
        if (d.matches("[xX]'[0-9A-Fa-f]*'") && (d.length() - 3) % 2 != 0) {
            throw new IllegalArgumentException("X'…' default needs an even number of hex digits: " + column);
        }
        if (dialect == DatabaseDialect.MYSQL && !d.equals(spec.defaultExpr().trim())) {
            return;
        }
        String type = spec.baseType();
        String signedType = type.replaceFirst(" UNSIGNED(?: ZEROFILL)?$", "");
        boolean unsigned = !signedType.equals(type);
        signedType = MYSQL_NUMERIC_ALIASES.getOrDefault(signedType, ColumnDefinitionParser.normalizeType(signedType));
        java.math.BigInteger[] range = MYSQL_INTEGER_RANGES.get(signedType);
        boolean quoted = d.startsWith("'");
        if (range != null && dialect == DatabaseDialect.MARIADB && d.matches("[xX]'[0-9A-Fa-f]*'")) {
            throw new IllegalArgumentException("DEFAULT " + d + " is rejected on " + type + " by MariaDB; declare "
                    + "the number: " + column);
        }
        if (range != null || signedType.equals("NUMERIC") || MYSQL_APPROXIMATE_TYPES.contains(signedType)) {
            java.math.BigDecimal value = mySqlNumericValue(d, signedType);
            if (value == null) {
                return;
            }
            // Unquoted E notation rounds half to even; exact literals and quoted strings round half away from zero.
            java.math.RoundingMode rounding = !quoted && d.matches("(?is).*\\d[e][-+]?\\d.*")
                    ? java.math.RoundingMode.HALF_EVEN : java.math.RoundingMode.HALF_UP;
            if ((long) value.precision() - value.scale() > MYSQL_MAX_DEFAULT_DIGITS
                    && !MYSQL_APPROXIMATE_TYPES.contains(signedType)) {
                throw new IllegalArgumentException("DEFAULT " + d + " is out of range for " + type + ": " + column);
            }
            // Integer columns accept a quoted or E-notation negative that rounds to zero; unquoted exact
            // negatives, and any negative on UNSIGNED DECIMAL/FLOAT/DOUBLE, are rejected.
            boolean negativeRejected = unsigned && value.signum() < 0
                    && (range == null || (!quoted && rounding == java.math.RoundingMode.HALF_UP));
            if (negativeRejected) {
                throw new IllegalArgumentException("DEFAULT " + d + " is negative on " + type + ": " + column);
            }
            if (MYSQL_APPROXIMATE_TYPES.contains(signedType)) {
                // FLOAT(p) with p > 24 is DOUBLE; FLOAT4 is FLOAT; REAL is DOUBLE unless REAL_AS_FLOAT is set.
                boolean single = (signedType.equals("FLOAT")
                        && (spec.length() == null || spec.scale() != null || spec.length() <= 24))
                        || (definition != null && MYSQL_FLOAT4_DECLARATION.matcher(definition).lookingAt());
                java.math.BigDecimal max = new java.math.BigDecimal(single ? Float.MAX_VALUE : Double.MAX_VALUE);
                if (value.abs().compareTo(max) > 0) {
                    throw new IllegalArgumentException("DEFAULT " + d + " is out of range for " + type + ": " + column);
                }
                if (spec.length() != null && spec.scale() != null) {
                    requireFitsPrecision(d, value, spec.length(), spec.scale(), rounding, type, column);
                }
                return;
            }
            if (range != null) {
                java.math.BigInteger rounded = roundedMySqlValue(value, 0, rounding).toBigIntegerExact();
                java.math.BigInteger min = unsigned ? java.math.BigInteger.ZERO : range[0];
                java.math.BigInteger max = unsigned ? range[2] : range[1];
                if (rounded.compareTo(min) < 0 || rounded.compareTo(max) > 0) {
                    throw new IllegalArgumentException("DEFAULT " + d + " is out of range for " + type + ": " + column);
                }
            } else {
                // MySQL DECIMAL without precision is DECIMAL(10,0).
                requireFitsPrecision(d, value, spec.length() == null ? 10 : spec.length(),
                        spec.scale() == null ? 0 : spec.scale(), rounding, "DECIMAL", column);
            }
            return;
        }
        if (spec.length() != null && (type.equals("BINARY") || type.equals("VARBINARY"))) {
            int bytes = mySqlBinaryLiteralLength(d);
            if (bytes > spec.length()) {
                throw new IllegalArgumentException("DEFAULT " + d + " is longer than " + type + "(" + spec.length()
                        + "): " + column);
            }
            return;
        }
        if (spec.length() != null && (type.equals("VARCHAR") || type.equals("CHAR") || type.equals("NVARCHAR")
                || type.equals("NCHAR")) && MYSQL_STRING_LITERAL.matcher(d).matches() && d.indexOf('\\') < 0) {
            String text = d.substring(1, d.length() - 1).replace("''", "'");
            // MariaDB rejects excess trailing spaces; MySQL drops them, which could never converge either.
            if (text.codePointCount(0, text.length()) > spec.length()) {
                throw new IllegalArgumentException("DEFAULT " + d + " is longer than " + type + "(" + spec.length()
                        + "): " + column);
            }
        }
    }

    private static void requireFitsPrecision(String d, java.math.BigDecimal value, int precision, int scale,
                                             java.math.RoundingMode rounding, String type, String column) {
        java.math.BigDecimal rounded = roundedMySqlValue(value.abs(), scale, rounding);
        if ((long) rounded.precision() - rounded.scale() > precision - scale) {
            throw new IllegalArgumentException("DEFAULT " + d + " does not fit " + type.replaceFirst("\\(.*$", "")
                    + "(" + precision + "," + scale + "): " + column);
        }
    }

    private static final Map<String, String> MYSQL_NUMERIC_ALIASES = Map.of("BOOLEAN", "TINYINT", "BOOL", "TINYINT",
            "MIDDLEINT", "MEDIUMINT", "INT1", "TINYINT", "INT2", "SMALLINT", "INT3", "MEDIUMINT", "INT4", "INTEGER",
            "INT8", "BIGINT", "DEC", "NUMERIC", "FIXED", "NUMERIC");
    private static final Set<String> MYSQL_APPROXIMATE_TYPES = Set.of("FLOAT", "DOUBLE PRECISION", "REAL");
    private static final Pattern MYSQL_FLOAT4_DECLARATION = Pattern.compile("(?i)\\s*FLOAT4(?!\\w)");
    private static final Pattern MYSQL_HEX_NUMBER = Pattern.compile("0x([0-9A-Fa-f]+)|[xX]'([0-9A-Fa-f]+)'");

    /** Beyond DECIMAL's 65 digits every MySQL numeric type overflows; also bounds the cost of rounding. */
    private static final int MYSQL_MAX_DEFAULT_DIGITS = 80;

    private static java.math.BigDecimal roundedMySqlValue(java.math.BigDecimal value, int scale,
                                                         java.math.RoundingMode rounding) {
        if ((long) value.precision() - value.scale() < -MYSQL_MAX_DEFAULT_DIGITS) {
            return java.math.BigDecimal.ZERO.setScale(scale);
        }
        return value.setScale(scale, rounding);
    }

    /** Stored length in bytes of a binary default literal, or -1 when it depends on the session or is not a literal. */
    static int mySqlBinaryLiteralLength(String d) {
        if (d.matches("[xX]'[0-9A-Fa-f]*'")) {
            return (d.length() - 3) / 2;
        }
        if (d.matches("0x[0-9A-Fa-f]+")) {
            return (d.length() - 1) / 2;
        }
        if (d.matches("-?\\d+")) {
            return new java.math.BigInteger(d).toString().length();
        }
        if (MYSQL_STRING_LITERAL.matcher(d).matches() && d.indexOf('\\') < 0 && d.chars().allMatch(c -> c < 0x80)) {
            return d.length() - 2 - (d.length() - d.replace("''", "'").length());
        }
        return -1;
    }

    /** The numeric value of an integer or decimal default literal, or null when it is not a plain number. */
    private static java.math.BigDecimal mySqlNumericValue(String d, String type) {
        Matcher hex = MYSQL_HEX_NUMBER.matcher(d);
        if (MYSQL_INTEGER_RANGES.containsKey(type) && hex.matches()) {
            return new java.math.BigDecimal(new java.math.BigInteger(hex.group(1) != null ? hex.group(1) : hex.group(2), 16));
        }
        // MySQL reads "- 1" as the negative number.
        String canonical = mySqlComparableDefault(d.replaceFirst("^([-+])\\s+(?=\\d|\\.\\d)", "$1"),
                type.equals("INT") ? "INTEGER" : type);
        try {
            return new java.math.BigDecimal(canonical);
        } catch (NumberFormatException notNumeric) {
            Matcher exponent = MYSQL_E_NUMBER.matcher(canonical);
            if (!exponent.matches()) {
                return null;
            }
            // The exponent is beyond int range: the value overflows every type, or rounds to zero.
            return exponent.group(3).equals("-") || !exponent.group(2).matches(".*[1-9].*")
                    ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(exponent.group(1) + "1E400");
        }
    }

    private static final Pattern MYSQL_E_NUMBER = Pattern.compile(
            "'?\\s*([-+]?)(\\d+\\.?\\d*|\\.\\d+)[eE]([-+]?)\\d+\\s*'?");

    private static final Pattern MYSQL_ONLY_ATTRIBUTE = Pattern.compile(" (?:UNSIGNED|ZEROFILL)(?: |$)");
    private static final Pattern MYSQL_FIXED_DECLARATION = Pattern.compile("(?i)\\s*FIXED\\b");
    private static final Pattern ORACLE_NUMBER_DECLARATION = Pattern.compile("(?i)\\s*NUMBER\\b");

    /**
     * Engine-specific spellings that normalize like portable types (FIXED and NUMBER compare as
     * NUMERIC), so on another engine they would pass comparison and fail only when DDL runs.
     */
    static void requireMySqlOnlyAttributes(ColumnSpec spec, String definition, DatabaseDialect dialect, String column) {
        if (dialect != DatabaseDialect.ORACLE && definition != null
                && ORACLE_NUMBER_DECLARATION.matcher(definition).lookingAt()) {
            throw new IllegalArgumentException("NUMBER is an Oracle type; declare DECIMAL: " + column);
        }
        if (dialect.isMySqlFamily()) {
            return;
        }
        if (MYSQL_ONLY_ATTRIBUTE.matcher(spec.baseType()).find()) {
            throw new IllegalArgumentException("UNSIGNED and ZEROFILL are MySQL/MariaDB attributes: " + column);
        }
        if (definition != null && MYSQL_FIXED_DECLARATION.matcher(definition).lookingAt()) {
            throw new IllegalArgumentException("FIXED is a MySQL/MariaDB type; declare DECIMAL: " + column);
        }
    }

    private static final Pattern MYSQL_COLUMN_CLAUSE = Pattern.compile(
            "(?i)\\b(?:COLLATE|CHARACTER\\s+SET|CHAR\\s+SET|CHARSET|COMMENT|ASCII|UNICODE|BYTE|SIGNED)\\b"
                    + "|\\S\\s+BINARY\\b");

    /**
     * The parser reads charset, collation, and comment clauses as part of the type or default, so
     * the charset checks would miss them and ADD/MODIFY could fail or never converge. A leading
     * {@code BINARY(n)} is the type; {@code BINARY} after a type is the {@code _bin} collation.
     * Parenthesized text is skipped: it holds lengths, ENUM values, and expression defaults such as
     * {@code (CAST(x AS SIGNED))}, where these words are not column attributes.
     */
    static void requireNoMySqlColumnClauses(String definition, DatabaseDialect dialect, String column) {
        if (!dialect.isMySqlFamily()) {
            return;
        }
        String masked = SqlLexer.mask(definition, SqlLexer.Mode.MYSQL, false, false);
        StringBuilder outsideParens = new StringBuilder(masked.length());
        int depth = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == ')' && depth > 0) {
                depth--;
            }
            outsideParens.append(depth == 0 ? c : ' ');
            if (c == '(') {
                depth++;
            }
        }
        if (MYSQL_COLUMN_CLAUSE.matcher(depth == 0 ? outsideParens : masked).find()) {
            throw new IllegalArgumentException("character set, collation, BINARY, SIGNED, and COMMENT attributes"
                    + " are not supported in a column definition; declare them in createSql: " + column);
        }
    }

    /** MySQL accepts ON UPDATE CURRENT_TIMESTAMP only on DATETIME/TIMESTAMP, with the column's precision. */
    static void requireSupportedOnUpdate(ColumnSpec spec, String definition, DatabaseDialect dialect, String column) {
        String onUpdate = ColumnDefinitionParser.onUpdateExpr(definition);
        if (onUpdate == null) {
            return;
        }
        if (!dialect.isMySqlFamily()) {
            throw new IllegalArgumentException("ON UPDATE is supported only on MySQL/MariaDB: " + column);
        }
        if (!spec.baseType().equals("DATETIME") && !spec.baseType().equals("TIMESTAMP")) {
            throw new IllegalArgumentException("ON UPDATE requires a DATETIME or TIMESTAMP column: " + column);
        }
        Matcher now = MYSQL_CURRENT_TIMESTAMP.matcher(
                mySqlComparableDefault(onUpdate.toUpperCase(Locale.ROOT), "TIMESTAMP"));
        int onUpdatePrecision = now.matches() && now.group(1) != null ? Integer.parseInt(now.group(1)) : 0;
        int columnPrecision = spec.length() == null ? 0 : spec.length();
        // MariaDB stores a bare ON UPDATE CURRENT_TIMESTAMP with the column's precision; MySQL rejects it.
        boolean mariaDbBare = dialect == DatabaseDialect.MARIADB && isBareOnUpdate(onUpdate);
        if (onUpdatePrecision != columnPrecision && !mariaDbBare) {
            throw new IllegalArgumentException("ON UPDATE precision " + onUpdatePrecision
                    + " differs from the column precision " + columnPrecision + ": " + column);
        }
    }

    /** The ON UPDATE the server will store for a declaration: MariaDB gives a bare one the column's precision. */
    static String effectiveOnUpdate(String definition, ColumnSpec spec, DatabaseDialect dialect) {
        String onUpdate = ColumnDefinitionParser.onUpdateExpr(definition);
        if (onUpdate == null || dialect != DatabaseDialect.MARIADB || spec.length() == null || spec.length() == 0) {
            return onUpdate;
        }
        Matcher now = MYSQL_CURRENT_TIMESTAMP.matcher(
                mySqlComparableDefault(onUpdate.toUpperCase(Locale.ROOT), "TIMESTAMP"));
        return now.matches() && isBareOnUpdate(onUpdate) ? "CURRENT_TIMESTAMP(" + spec.length() + ")" : onUpdate;
    }

    /** {@code NOW()} and {@code CURRENT_TIMESTAMP} are bare; an explicit {@code (0)} is not (MariaDB rejects it on DATETIME(n)). */
    private static boolean isBareOnUpdate(String onUpdate) {
        return !onUpdate.matches("(?s).*\\(\\s*\\d+\\s*\\).*");
    }

    /**
     * ON UPDATE is not part of the compared column spec, so a difference in either direction is
     * reported on every sync; MODIFY COLUMN would silently add or drop it.
     */
    static String mySqlOnUpdateDrift(String declaredOnUpdate, String liveOnUpdate) {
        String declared = declaredOnUpdate == null ? null
                : mySqlComparableDefault(declaredOnUpdate.toUpperCase(Locale.ROOT), "TIMESTAMP");
        String live = liveOnUpdate == null ? null
                : mySqlComparableDefault(liveOnUpdate.toUpperCase(Locale.ROOT), "TIMESTAMP");
        if (java.util.Objects.equals(declared, live)) {
            return null;
        }
        return "ON UPDATE differs (declared " + (declared == null ? "none" : declared)
                + ", server reports " + (live == null ? "none" : live) + ")";
    }

    /** A national column whose type already matches still reports charset/collation drift. */
    static NonDestructiveAlterPlanner.Plan mySqlNationalDriftPlan(
            String tableName, String columnName, String definition, String driftReason,
            NonDestructiveAlterPlanner.Plan plan) {
        if (driftReason == null) {
            return plan;
        }
        return new NonDestructiveAlterPlanner.Plan(List.of(), List.of(
                "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition
                        + "; -- pending: " + driftReason + "; handle this change in a reviewed change set"));
    }

    /** Live MySQL/MariaDB column attributes that {@code MODIFY COLUMN <definition>} would rewrite. */
    record MySqlColumnFacts(boolean found, String collation, String tableCollation, String characterSet,
                            String extra, String comment, String generationExpression,
                            String charsetDefaultCollation, String columnType) {}

    /**
     * {@code MODIFY COLUMN} replaces the whole column definition: attributes the declaration
     * does not repeat (charset/collation, ON UPDATE, AUTO_INCREMENT, INVISIBLE, COMMENT,
     * generation expression) are silently reset, so such columns are pending instead.
     */
    private static final Pattern MYSQL_ON_UPDATE_TOKEN = Pattern.compile("\\bON\\s+UPDATE\\b");
    private static final Pattern MYSQL_AUTO_INCREMENT_TOKEN = Pattern.compile("\\bAUTO_INCREMENT\\b");
    private static final Pattern MYSQL_TINYINT1_DECLARATION = Pattern.compile(
            "(?i)^\\s*(?:BOOLEAN|BOOL|TINYINT\\s*\\(\\s*1\\s*\\))(?![\\w(])");
    private static final Pattern MYSQL_TINYINT_DECLARATION = Pattern.compile("(?i)^\\s*(?:BOOLEAN|BOOL|TINYINT|INT1)(?!\\w)");

    static String mySqlBlockReason(MySqlColumnFacts facts, String declaredDefinition) {
        if (!facts.found()) {
            return "column was not found in information_schema";
        }
        String declared;
        try {
            // Keywords inside a default literal ('COLLATE', 'COMMENT') are not clauses.
            declared = SqlLexer.mask(declaredDefinition, SqlLexer.Mode.MYSQL, false, false).toUpperCase(Locale.ROOT);
        } catch (IllegalArgumentException untokenizable) {
            return "definition could not be tokenized: " + untokenizable.getMessage();
        }
        String extra = facts.extra() == null ? "" : facts.extra().toLowerCase(Locale.ROOT);
        if (facts.generationExpression() != null && !facts.generationExpression().isBlank()) {
            return "generated column";
        }
        if (extra.contains("on update") && !MYSQL_ON_UPDATE_TOKEN.matcher(declared).find()) {
            return "ON UPDATE attribute would be dropped by MODIFY COLUMN";
        }
        if (extra.contains("auto_increment") && !MYSQL_AUTO_INCREMENT_TOKEN.matcher(declared).find()) {
            return "AUTO_INCREMENT would be dropped by MODIFY COLUMN";
        }
        if (extra.contains("invisible")) {
            return "INVISIBLE attribute would be dropped by MODIFY COLUMN";
        }
        String columnType = facts.columnType() == null ? "" : facts.columnType().toLowerCase(Locale.ROOT);
        if (SchemaSnapshotWriter.MARIADB_COMPRESSED_COMMENT.matcher(columnType).find()) {
            return "COMPRESSED attribute would be dropped by MODIFY COLUMN";
        }
        // ENUM and SET values are quoted literals, not attributes.
        if (columnType.replaceAll("'(?:[^'\\\\]|''|\\\\.)*'", "''").matches("(?s).*\\bzerofill\\b.*")
                && !declared.matches("(?s).*\\bZEROFILL\\b.*")) {
            return "ZEROFILL attribute would be dropped by MODIFY COLUMN";
        }
        Integer zerofillWidth = SchemaSnapshotWriter.mysqlZerofillCustomWidth(columnType);
        if (zerofillWidth != null) {
            return "ZEROFILL display width (" + zerofillWidth + ") would be reset by MODIFY COLUMN";
        }
        // Connector/J reads only TINYINT(1) as BOOLEAN/BIT, so a MODIFY must not add or drop that display width.
        boolean liveTinyint1 = columnType.matches("tinyint\\(1\\)(?:\\s.*)?");
        boolean declaredTinyint1 = MYSQL_TINYINT1_DECLARATION.matcher(declaredDefinition).find();
        String liveAttributes = columnType.replaceFirst("^tinyint(?:\\(\\d+\\))?", "").trim().toUpperCase(Locale.ROOT);
        // Widening to another integer type is not a display-width change.
        // Declarations cannot spell TINYINT(n) UNSIGNED ZEROFILL, so there is nothing to suggest for it.
        boolean zerofill = liveAttributes.contains("ZEROFILL");
        if (liveTinyint1 && !declaredTinyint1 && MYSQL_TINYINT_DECLARATION.matcher(declaredDefinition).find()) {
            return "TINYINT(1) display width would be reset by MODIFY COLUMN" + (zerofill ? "" : "; declare "
                    + (liveAttributes.isEmpty() ? "BOOLEAN or TINYINT(1)" : "TINYINT(1) " + liveAttributes));
        }
        if (!liveTinyint1 && declaredTinyint1 && columnType.startsWith("tinyint")) {
            return "MODIFY COLUMN would change the TINYINT display width to (1), which Connector/J reads as BOOLEAN"
                    + (zerofill ? "" : "; declare TINYINT" + (liveAttributes.isEmpty() ? "" : " " + liveAttributes));
        }
        // Validation rejects COMMENT, COLLATE and CHARACTER SET clauses, so a declaration can never keep them.
        if (facts.comment() != null && !facts.comment().isEmpty()) {
            return "column COMMENT would be dropped by MODIFY COLUMN";
        }
        boolean national = mySqlDeclaresNational(declaredDefinition);
        if (national) {
            return mySqlNationalDrift(facts);
        }
        if (facts.collation() != null && !facts.collation().equals(facts.tableCollation())) {
            return "column collation " + facts.collation() + " differs from the table default and would be reset";
        }
        return null;
    }

    private static final Set<String> MYSQL_CHARACTER_TYPES = Set.of(
            "CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT");
    private static final Set<String> MYSQL_UNICODE_CHARSETS = Set.of("utf8mb4", "utf16", "utf16le", "utf32");

    /** The declared default of a character column when it has non-ASCII characters, otherwise null. */
    static String mySqlNonAsciiCharacterDefault(String declaredDefinition) {
        ColumnSpec spec = ColumnDefinitionParser.parse(declaredDefinition);
        String declaredDefault = spec.defaultExpr();
        return declaredDefault != null && MYSQL_CHARACTER_TYPES.contains(spec.baseType())
                && !declaredDefault.chars().allMatch(c -> c < 0x80) ? declaredDefault : null;
    }

    /**
     * A non-ASCII default the column character set cannot hold is rejected in strict sql_mode
     * and stored with '?' otherwise, so the change would fail mid-sync or never converge.
     * The server converts the text to decide.
     */
    private static String mySqlUnstorableDefault(Connection conn, String declaredDefinition, String characterSet)
            throws SQLException {
        String declaredDefault = mySqlNonAsciiCharacterDefault(declaredDefinition);
        if (declaredDefault == null || characterSet == null) {
            return null;
        }
        String charset = characterSet.toLowerCase(Locale.ROOT);
        if (MYSQL_UNICODE_CHARSETS.contains(charset) || mySqlCharsetStores(conn, declaredDefault, charset)) {
            return null;
        }
        return "DEFAULT " + declaredDefault + " has characters the " + charset + " character set cannot store";
    }

    /** Whether {@code text} survives a round trip through {@code charset}; sent as UTF-8 bytes. */
    static boolean mySqlCharsetStores(Connection conn, String text, String charset) throws SQLException {
        if (!charset.matches("[a-z0-9]+")) {
            return false;
        }
        String sql = "SELECT HEX(CONVERT(CONVERT(CONVERT(? USING utf8mb4) USING " + charset
                + ") USING utf8mb4)) = HEX(CONVERT(? USING utf8mb4))";
        byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var statement = conn.prepareStatement(sql)) {
            statement.setBytes(1, utf8);
            statement.setBytes(2, utf8);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Why ADD COLUMN would store a different default, or null; the column gets the table's character set. */
    private String mySqlUnstorableAddedDefault(Connection conn, String tableName, String definition)
            throws SQLException {
        if (mySqlNonAsciiCharacterDefault(definition) == null) {
            return null;
        }
        String charset = mySqlDeclaresNational(definition) ? "utf8mb3" : mySqlTableCharset(conn, tableName);
        return charset == null ? "the character set of table " + tableName + " could not be determined"
                : mySqlUnstorableDefault(conn, definition, charset);
    }

    /** Character set names have no underscore, and a collation name starts with its character set's name. */
    private String mySqlTableCharset(Connection conn, String tableName) throws SQLException {
        try (var statement = conn.prepareStatement(
                "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            statement.setString(1, options.schema());
            statement.setString(2, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                String collation = rs.next() ? rs.getString(1) : null;
                return collation == null ? null : collation.split("_", 2)[0];
            }
        }
    }

    static boolean mySqlDeclaresNational(String declaredDefinition) {
        return declaredDefinition != null
                && declaredDefinition.matches("(?is)^\\s*(?:NVARCHAR|NCHAR|NATIONAL)\\b.*");
    }

    /**
     * NVARCHAR/NCHAR mean utf8mb3 with its default collation. A live column with another
     * character set or collation has drifted from the declaration, and MODIFY COLUMN would reset it.
     */
    static String mySqlNationalDrift(MySqlColumnFacts facts) {
        if (!facts.found()) {
            return "column was not found in information_schema";
        }
        String charset = facts.characterSet() == null ? "" : facts.characterSet().toLowerCase(Locale.ROOT);
        if (!charset.equals("utf8mb3") && !charset.equals("utf8")) {
            return "NVARCHAR/NCHAR is utf8mb3 but the column character set is "
                    + (charset.isEmpty() ? "unknown" : charset);
        }
        if (facts.charsetDefaultCollation() == null) {
            return "the default collation of " + charset + " could not be determined";
        }
        if (facts.collation() == null || !facts.collation().equals(facts.charsetDefaultCollation())) {
            return "NVARCHAR/NCHAR uses the utf8mb3 default collation but the column collation is "
                    + facts.collation();
        }
        return null;
    }

    private static MySqlColumnFacts mySqlColumnFacts(Connection conn, String schema, String tableName,
                                                     String columnName)
            throws SQLException {
        String sql = """
                SELECT c.COLLATION_NAME, t.TABLE_COLLATION, c.CHARACTER_SET_NAME, c.EXTRA, c.COLUMN_COMMENT,
                       c.GENERATION_EXPRESSION, cs.DEFAULT_COLLATE_NAME, c.COLUMN_TYPE
                FROM information_schema.COLUMNS c
                JOIN information_schema.TABLES t
                  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                LEFT JOIN information_schema.CHARACTER_SETS cs
                  ON cs.CHARACTER_SET_NAME = c.CHARACTER_SET_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? AND c.COLUMN_NAME = ?
                """;
        try (var statement = conn.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new MySqlColumnFacts(false, null, null, null, null, null, null, null, null);
                }
                return new MySqlColumnFacts(true, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
            }
        }
    }

    /**
     * MySQL/MariaDB and PostgreSQL store NVARCHAR/NCHAR as VARCHAR/CHAR (national charset or none);
     * MySQL/MariaDB store BOOLEAN as TINYINT(1).
     */
    static ColumnSpec foldNationalType(ColumnSpec spec, DatabaseDialect dialect) {
        if (!dialect.isMySqlFamily() && dialect != DatabaseDialect.POSTGRESQL) {
            return spec;
        }
        return switch (spec.baseType()) {
            case "NVARCHAR" -> new ColumnSpec("VARCHAR", spec.length(), spec.scale(), spec.notNull(), spec.defaultExpr());
            case "NCHAR" -> new ColumnSpec("CHAR", spec.length(), spec.scale(), spec.notNull(), spec.defaultExpr());
            case "BOOLEAN" -> dialect.isMySqlFamily()
                    ? new ColumnSpec("TINYINT", null, null, spec.notNull(), spec.defaultExpr()) : spec;
            default -> spec;
        };
    }

    private static final Pattern SINGLE_QUOTED_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern IDENTITY_KEYWORD = Pattern.compile(
            "\\b(?:BIGSERIAL|SMALLSERIAL|SERIAL|AUTO_INCREMENT|IDENTITY)\\b", Pattern.CASE_INSENSITIVE);

    /** Identity / serial columns must not get DROP DEFAULT from nextval noise. */
    public static boolean shouldSkipAlter(String definition, LiveColumn live) {
        if (definition == null) {
            return true;
        }
        String code = SINGLE_QUOTED_LITERAL.matcher(definition).replaceAll("''");
        if (IDENTITY_KEYWORD.matcher(code).find()) {
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

    static final int ORACLE_FLOAT_MAX_PRECISION = 126;

    /**
     * Oracle stores INTEGER/INT/SMALLINT as {@code NUMBER(38,0)}; compare those as the declared type.
     * ANSI NUMERIC/DECIMAL without precision already carry (38,0) from {@link #withDefaultNumericPrecision}.
     * A FLOAT without a reported binary precision is {@code FLOAT(126)}.
     */
    static LiveColumn oracleComparableLive(LiveColumn live, ColumnSpec target) {
        boolean integerStorage = "NUMERIC".equals(live.baseType())
                && Integer.valueOf(38).equals(live.length())
                && (live.scale() == null || live.scale() == 0);
        if (integerStorage && ColumnDefinitionParser.integerRank(target.baseType()) > 0) {
            return new LiveColumn(target.baseType(), target.length(), target.scale(), live.notNull(),
                    live.defaultExpr());
        }
        if ("FLOAT".equals(live.baseType()) && live.length() == null) {
            return new LiveColumn("FLOAT", ORACLE_FLOAT_MAX_PRECISION, null, live.notNull(), live.defaultExpr());
        }
        return live;
    }

    /**
     * Oracle stores the float family as FLOAT with a binary precision: FLOAT and DOUBLE PRECISION are
     * {@code FLOAT(126)}, REAL is {@code FLOAT(63)}. The declaration compares as that FLOAT(n).
     */
    static ColumnSpec oracleComparableTarget(ColumnSpec target) {
        Integer binaryPrecision = switch (target.baseType()) {
            case "FLOAT" -> target.length() == null ? ORACLE_FLOAT_MAX_PRECISION : target.length();
            case "DOUBLE PRECISION" -> ORACLE_FLOAT_MAX_PRECISION;
            case "REAL" -> 63;
            default -> null;
        };
        return binaryPrecision == null ? target
                : new ColumnSpec("FLOAT", binaryPrecision, null, target.notNull(), target.defaultExpr());
    }

    /** Oracle rejects FLOAT(n) outside 1..126 at DDL time. */
    static void requireSupportedFloatPrecision(ColumnSpec spec, DatabaseDialect dialect, String column) {
        if (dialect == DatabaseDialect.ORACLE && "FLOAT".equals(spec.baseType()) && spec.length() != null
                && (spec.length() < 1 || spec.length() > ORACLE_FLOAT_MAX_PRECISION)) {
            throw new IllegalArgumentException("oracle FLOAT binary precision is 1.." + ORACLE_FLOAT_MAX_PRECISION
                    + ": " + column + " FLOAT(" + spec.length() + ")");
        }
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
                        ColumnSpec spec = ColumnDefinitionParser.parse(column.definition());
                        requireSupportedFractionalPrecision(spec, dialect, table + "." + name);
                        requireSupportedFloatPrecision(spec, dialect, table + "." + name);
                        requireMySqlOnlyAttributes(spec, column.definition(), dialect, table + "." + name);
                        requireNoMySqlColumnClauses(column.definition(), dialect, table + "." + name);
                        requireSupportedOnUpdate(spec, column.definition(), dialect, table + "." + name);
                        requireMySqlDefaultFits(spec, column.definition(), dialect, table + "." + name);
                        columnSpecs.put(name, spec);
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
                                  DatabaseDialect dialect, boolean applyChanges, Set<String> unaddedColumns)
            throws SQLException {
        Map<String, String> live = new HashMap<>();
        if (dialect.isMySqlFamily()) {
            for (String sql : SchemaSnapshotWriter.readMySqlFamilyIndexes(conn, options.schema(), tableName, dialect)) {
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
                String unadded = liveSql == null ? indexedColumnAmong(target, unaddedColumns) : null;
                if (unadded != null) {
                    pendingSql.add(terminated(dialectCompatibleIndexSql(sql, dialect)) + " -- pending: column "
                            + tableName + "." + unadded + " was not added");
                } else if (liveSql == null) {
                    requireNoTemporaryShadow(conn, dialect, tableName);
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
        String schema = dialect.metadataSchemaPattern(options.schema());
        String table = dialect.metadataObjectName(tableName);
        try (ResultSet rows = meta.getPrimaryKeys(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                schema, table)) {
            while (rows.next()) {
                if (!DatabaseDialect.isRequestedObject(rows, schema, table)) {
                    continue;
                }
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
        String schema = dialect.metadataSchemaPattern(options.schema());
        String table = dialect.metadataObjectName(tableName);
        try (ResultSet rows = meta.getPrimaryKeys(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                schema, table)) {
            while (rows.next()) {
                if (!DatabaseDialect.isRequestedObject(rows, schema, table)) {
                    continue;
                }
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
        // Text such as COMMENT 'primary key (x)' or DEFAULT 'a PRIMARY KEY' is not a key clause.
        List<String> columns = primaryKeyColumnsIn(SqlLexer.mask(createSql, SqlLexer.mode(dialect), true, false), dialect);
        // MySQL "…" is a string unless ANSI_QUOTES is on; both readings must name the same key.
        if (dialect.isMySqlFamily() && !columns.equals(mySqlPrimaryKeyWithStringDoubleQuotes(createSql, dialect))) {
            throw new IllegalArgumentException("PRIMARY KEY depends on whether double-quoted text is an identifier"
                    + " (ANSI_QUOTES); quote strings with single quotes");
        }
        return columns;
    }

    private static List<String> mySqlPrimaryKeyWithStringDoubleQuotes(String createSql, DatabaseDialect dialect) {
        try {
            return primaryKeyColumnsIn(SqlLexer.maskForScope(createSql, SqlLexer.Mode.MYSQL, false), dialect);
        } catch (IllegalArgumentException blankedKey) {
            return null;
        }
    }

    private static List<String> primaryKeyColumnsIn(String code, DatabaseDialect dialect) {
        Matcher matcher = PRIMARY_KEY_COLUMNS.matcher(code);
        if (!matcher.find()) {
            Matcher inline = INLINE_PRIMARY_KEY.matcher(code);
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
        String schema = dialect.metadataSchemaPattern(options.schema());
        try (ResultSet rs = meta.getTables(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (!DatabaseDialect.isRequestedObject(rs, schema, null)) {
                    continue;
                }
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    Map<String, LiveColumn> getLiveColumns(DatabaseMetaData meta, String tableName,
                                           DatabaseDialect dialect) throws SQLException {
        Map<String, LiveColumn> columns = new HashMap<>();
        Set<String> generatedDefaults = dialect == DatabaseDialect.MYSQL
                ? SchemaSnapshotWriter.mysqlGeneratedDefaultColumns(meta.getConnection(), options.schema(), tableName)
                : Set.of();
        Map<String, Integer> datetimePrecisions = dialect.isMySqlFamily()
                ? SchemaSnapshotWriter.mysqlDatetimePrecisions(meta.getConnection(), options.schema(), tableName)
                : Map.of();
        Map<String, String> mysqlDataTypes = dialect.isMySqlFamily()
                ? SchemaSnapshotWriter.mysqlDataTypes(meta.getConnection(), options.schema(), tableName)
                : Map.of();
        Map<String, String> mariaDbDefaults = dialect == DatabaseDialect.MARIADB
                ? SchemaSnapshotWriter.mariaDbColumnDefaults(meta.getConnection(), options.schema(), tableName)
                : Map.of();
        Map<String, String> binaryDefaults = dialect.isMySqlFamily()
                ? SchemaSnapshotWriter.mysqlBinaryDefaults(meta.getConnection(), options.schema(), tableName,
                        dialect == DatabaseDialect.MARIADB)
                : Map.of();
        String schema = dialect.metadataSchemaPattern(options.schema());
        String table = dialect.metadataObjectName(tableName.toLowerCase(Locale.ROOT));
        try (ResultSet rs = meta.getColumns(dialect.metadataCatalog(meta.getConnection(), options.schema()),
                schema, table, "%")) {
            while (rs.next()) {
                // Oracle JDBC exposes COLUMN_DEF as LONG — read it before any other column.
                String colDefault = rs.getString("COLUMN_DEF");
                if (!DatabaseDialect.isRequestedObject(rs, schema, table)) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                if (mariaDbDefaults.containsKey(name)) {
                    colDefault = mariaDbDefaults.get(name);
                }
                String typeName = rs.getString("TYPE_NAME");
                if (dialect.isMySqlFamily()) {
                    typeName = SchemaSnapshotWriter.mysqlZerofill(
                            SchemaSnapshotWriter.mysqlTypeName(typeName, mysqlDataTypes.get(name)), mysqlDataTypes.get(name));
                }
                int size = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                boolean decimalDigitsNull = rs.wasNull();
                boolean notNull = "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                if (reportsNoDefaultAsNullText(dialect) && "NULL".equalsIgnoreCase(colDefault)) {
                    colDefault = null;
                }
                if (dialect == DatabaseDialect.MYSQL) {
                    colDefault = SchemaSnapshotWriter.mysqlLiteralDefault(colDefault, typeName,
                            generatedDefaults.contains(name));
                }
                if (binaryDefaults.containsKey(name)) {
                    colDefault = binaryDefaults.get(name);
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
                } else if (ColumnDefinitionParser.isNumeric(normalized) && size > 0 && size <= 1_000) {
                    length = size;
                    scale = decimalDigitsNull ? null : decimalDigits;
                } else if (dialect == DatabaseDialect.ORACLE && "FLOAT".equals(normalized) && size > 0) {
                    // ojdbc reports the binary precision (DATA_PRECISION) of FLOAT, REAL, and DOUBLE PRECISION.
                    length = size;
                } else if (dialect == DatabaseDialect.POSTGRESQL && "VECTOR".equals(normalized)) {
                    length = readVectorDimension(meta.getConnection(), tableName, name);
                } else if (ColumnDefinitionParser.hasFractionalPrecision(normalized)) {
                    Integer reported = datetimePrecisions.containsKey(name) ? datetimePrecisions.get(name)
                            : decimalDigitsNull ? null : decimalDigits;
                    length = liveFractionalPrecision(typeName, normalized, reported, dialect);
                }
                columns.put(name, new LiveColumn(normalized, length, scale, notNull, colDefault));
            }
        }
        return columns;
    }

    private static final Pattern ORACLE_TIMESTAMP_PRECISION = Pattern.compile("^TIMESTAMP\\s*\\((\\d+)\\)",
            Pattern.CASE_INSENSITIVE);

    /** Fractional-second precision of a live temporal column, or null when the engine has none to compare. */
    static Integer liveFractionalPrecision(String typeName, String normalizedType, Integer decimalDigits,
                                           DatabaseDialect dialect) {
        if (defaultFractionalPrecision(normalizedType, dialect) == null) {
            return null;
        }
        if (dialect == DatabaseDialect.ORACLE) {
            Matcher matcher = ORACLE_TIMESTAMP_PRECISION.matcher(typeName.trim());
            return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
        }
        return decimalDigits;
    }

    /**
     * Precision an engine applies when a temporal type is declared without one; null where the
     * engine has no precision argument for that type (SQL Server legacy DATETIME, Oracle DATE).
     */
    static Integer defaultFractionalPrecision(String normalizedType, DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> switch (normalizedType) {
                case "TIMESTAMP", "TIMESTAMPTZ", "TIME", "TIMETZ" -> 6;
                default -> null;
            };
            case ORACLE -> switch (normalizedType) {
                case "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMPLTZ" -> 6;
                default -> null;
            };
            case MYSQL, MARIADB -> switch (normalizedType) {
                case "TIMESTAMP", "DATETIME", "TIME" -> 0;
                default -> null;
            };
            case SQLSERVER -> switch (normalizedType) {
                case "DATETIME2", "DATETIMEOFFSET", "TIME" -> 7;
                default -> null;
            };
        };
    }

    static int maxFractionalPrecision(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL, MYSQL, MARIADB -> 6;
            case SQLSERVER -> 7;
            case ORACLE -> 9;
        };
    }

    /**
     * Rejects temporal precision the engine cannot store before any DDL runs: PostgreSQL silently
     * caps it (never converges); MySQL, SQL Server, and Oracle reject the statement mid-sync.
     */
    static void requireSupportedFractionalPrecision(ColumnSpec spec, DatabaseDialect dialect, String column) {
        if (spec.length() == null || !ColumnDefinitionParser.hasFractionalPrecision(spec.baseType())) {
            return;
        }
        if (defaultFractionalPrecision(spec.baseType(), dialect) == null) {
            throw new IllegalArgumentException(dialect.id() + " " + spec.baseType()
                    + " does not accept a fractional-second precision: " + column);
        }
        int max = maxFractionalPrecision(dialect);
        if (spec.length() < 0 || spec.length() > max) {
            throw new IllegalArgumentException(dialect.id() + " fractional-second precision is 0.." + max
                    + ": " + column + " " + spec.baseType() + "(" + spec.length() + ")");
        }
    }

    /**
     * Declared exact numeric without precision, as the engine creates it: DECIMAL(10,0) on
     * MySQL/MariaDB, DECIMAL(18,0) on SQL Server, NUMBER(38,0) for Oracle's ANSI spellings. Oracle
     * NUMBER and PostgreSQL NUMERIC stay unbounded, as does any spelling the engine does not accept
     * (validation rejects those). Compared as unbounded, a bare declaration would plan a widening
     * that rounds a wider live column.
     */
    static ColumnSpec withDefaultNumericPrecision(ColumnSpec spec, String definition, DatabaseDialect dialect) {
        if (!ColumnDefinitionParser.isNumeric(spec.baseType()) || spec.length() != null || definition == null
                || !(ANSI_BARE_NUMERIC.matcher(definition).lookingAt()
                || (dialect.isMySqlFamily() && MYSQL_FIXED_DECLARATION.matcher(definition).lookingAt()))) {
            return spec;
        }
        Integer precision = switch (dialect) {
            case MYSQL, MARIADB -> 10;
            case SQLSERVER -> 18;
            case ORACLE -> 38;
            case POSTGRESQL -> null;
        };
        return precision == null ? spec
                : new ColumnSpec(spec.baseType(), precision, 0, spec.notNull(), spec.defaultExpr());
    }

    private static final Pattern ANSI_BARE_NUMERIC = Pattern.compile("(?i)\\s*(?:NUMERIC|DECIMAL|DEC)\\b(?!\\s*\\()");

    /** Declared temporal type with the engine's implicit precision filled in, so both sides compare. */
    static ColumnSpec withDefaultFractionalPrecision(ColumnSpec spec, DatabaseDialect dialect) {
        Integer fallback = defaultFractionalPrecision(spec.baseType(), dialect);
        if (fallback == null) {
            return ColumnDefinitionParser.hasFractionalPrecision(spec.baseType()) && spec.length() != null
                    ? new ColumnSpec(spec.baseType(), null, spec.scale(), spec.notNull(), spec.defaultExpr())
                    : spec;
        }
        return spec.length() != null ? spec
                : new ColumnSpec(spec.baseType(), fallback, spec.scale(), spec.notNull(), spec.defaultExpr());
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
        // A bare temporal or DECIMAL type means the default precision, which the planner already compared.
        boolean defaultPrecision = (ColumnDefinitionParser.hasFractionalPrecision(target.baseType())
                && target.length() != null
                && target.length().equals(defaultFractionalPrecision(target.baseType(), DatabaseDialect.SQLSERVER)))
                || (ColumnDefinitionParser.isNumeric(target.baseType())
                && Integer.valueOf(18).equals(target.length()) && Integer.valueOf(0).equals(target.scale()));
        if (dependents.parameterizedType() && !declaredTypeText.contains("(") && !defaultPrecision) {
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
