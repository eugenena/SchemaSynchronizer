// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChangeSetExecutor {
    private static final Logger log = LoggerFactory.getLogger(ChangeSetExecutor.class);

    record Result(int applied, List<String> plannedSql) {}

    Result apply(Connection conn, List<SchemaDefinition.ChangeSet> changes, SchemaSynchronizerOptions options,
                 DatabaseDialect dialect)
            throws Exception {
        List<SchemaDefinition.ChangeSet> safeChanges = validateStructure(changes);
        if (safeChanges.isEmpty()) {
            return new Result(0, List.of());
        }

        String history = dialect.qualifyHistoryTable(options.schema(), options.historyTable());
        Map<String, String> applied = historyExists(conn, options, dialect) ? readHistory(conn, history) : Map.of();
        List<String> planned = new ArrayList<>();
        int appliedCount = 0;
        int unrecordedCount = 0;

        for (SchemaDefinition.ChangeSet change : safeChanges) {
            String checksum = checksum(change);
            String previous = applied.get(change.id());
            if (previous != null) {
                if (!previous.equals(checksum)) {
                    throw new IllegalStateException("checksum mismatch for applied schema change '"
                            + change.id() + "': committed changes are immutable");
                }
                continue;
            }
            unrecordedCount++;
            requirePolicy(change, options, dialect);
            // Dry-run must not execute verificationSql (UDFs / admin SELECT side effects).
            if (options.dryRun()) {
                planned.addAll(change.statements());
            } else if (!isVerified(conn, change)) {
                planned.addAll(change.statements());
            }
        }
        if (options.dryRun() || unrecordedCount == 0) {
            return new Result(0, List.copyOf(planned));
        }

        createHistory(conn, history, dialect);
        applied = readHistory(conn, history);
        for (SchemaDefinition.ChangeSet change : safeChanges) {
            String checksum = checksum(change);
            String previous = applied.get(change.id());
            if (previous != null) {
                if (!previous.equals(checksum)) {
                    throw new IllegalStateException("checksum mismatch for applied schema change '"
                            + change.id() + "': committed changes are immutable");
                }
                continue;
            }
            if (isVerified(conn, change)) {
                insertHistory(conn, history, change, checksum, 0);
                appliedCount++;
                continue;
            }
            Instant started = Instant.now();
            int skippedDuplicates = 0;
            for (String sql : change.statements()) {
                if (executeAllowingAlreadyExists(conn, dialect.executableSql(sql), change.id(), dialect)) {
                    skippedDuplicates++;
                }
            }
            long elapsed = Duration.between(started, Instant.now()).toMillis();
            if (skippedDuplicates > 0
                    && (change.verificationSql() == null || change.verificationSql().isBlank())) {
                throw new IllegalStateException("schema change '" + change.id()
                        + "' skipped already-present statement(s) but has no verificationSql; "
                        + "add a verification query so partial adoption cannot ledger an incomplete effect");
            }
            if (change.verificationSql() != null && !isVerified(conn, change)) {
                throw new IllegalStateException("verification failed after schema change '" + change.id() + "'");
            }
            if (skippedDuplicates > 0) {
                log.info("[SchemaSynchronizer] Change '{}' adopted {} already-present statement(s) and "
                                + "applied the remainder",
                        change.id(), skippedDuplicates);
            }
            insertHistory(conn, history, change, checksum, elapsed);
            appliedCount++;
        }
        return new Result(appliedCount, List.copyOf(planned));
    }

    /**
     * Offline validation without history: structure, then the SQL policy for every change set.
     * A sync applies the policy only to change sets not yet recorded (see {@link #validateHistory}).
     */
    List<SchemaDefinition.ChangeSet> validate(List<SchemaDefinition.ChangeSet> changes,
                                              SchemaSynchronizerOptions options, DatabaseDialect dialect) {
        List<SchemaDefinition.ChangeSet> valid = validateStructure(changes);
        valid.forEach(change -> requirePolicy(change, options, dialect));
        return valid;
    }

    /** Ids, lengths, duplicates, and non-empty statement lists; reads no SQL. */
    List<SchemaDefinition.ChangeSet> validateStructure(List<SchemaDefinition.ChangeSet> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        for (SchemaDefinition.ChangeSet change : changes) {
            if (change == null || change.id() == null || !change.id().matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("schema change id must be nonblank and filesystem-safe");
            }
            if (change.id().length() > 200) {
                throw new IllegalArgumentException("schema change id exceeds 200 characters: " + change.id());
            }
            if (change.description() != null && change.description().length() > 500) {
                throw new IllegalArgumentException("schema change description exceeds 500 characters: "
                        + change.id());
            }
            if (!ids.add(change.id())) {
                throw new IllegalArgumentException("duplicate schema change id: " + change.id());
            }
            if (change.statements() == null || change.statements().isEmpty()) {
                throw new IllegalArgumentException("schema change has no statements: " + change.id());
            }
        }
        return List.copyOf(changes);
    }

    /** The non-destructive, schema-scope, and read-only verification checks for one change set. */
    static void requirePolicy(SchemaDefinition.ChangeSet change, SchemaSynchronizerOptions options,
                              DatabaseDialect dialect) {
        change.statements().forEach(statement -> {
            NonDestructiveSqlPolicy.requireSafe(statement, dialect);
            ChangeSetSchemaScope.requireScoped(statement, options.schema(), dialect);
        });
        if (change.verificationSql() != null) {
            NonDestructiveSqlPolicy.requireReadOnlyVerification(change.verificationSql(), dialect);
            ChangeSetSchemaScope.requireNoSessionNamespaceChange(change.verificationSql(), dialect);
        }
    }

    /**
     * Checks recorded checksums, then the SQL policy for every change set missing from history
     * (a recorded change set never runs again), then the implicit-DDL recovery rules, which may
     * run verification SQL and so must follow the policy check.
     */
    void validateHistory(Connection conn, List<SchemaDefinition.ChangeSet> changes, SchemaSynchronizerOptions options,
                         DatabaseDialect dialect)
            throws Exception {
        String history = dialect.qualifyHistoryTable(options.schema(), options.historyTable());
        Map<String, String> applied = historyExists(conn, options, dialect) ? readHistory(conn, history) : Map.of();
        Map<String, String> expected = new HashMap<>();
        for (SchemaDefinition.ChangeSet change : changes) {
            expected.put(change.id(), checksum(change));
        }
        for (Map.Entry<String, String> entry : applied.entrySet()) {
            String expectedChecksum = expected.get(entry.getKey());
            if (expectedChecksum == null) {
                throw new IllegalStateException("applied schema change is missing from the immutable ledger: "
                        + entry.getKey());
            }
            if (!expectedChecksum.equals(entry.getValue())) {
                throw new IllegalStateException("checksum mismatch for applied schema change '"
                        + entry.getKey() + "': committed changes are immutable");
            }
        }
        for (SchemaDefinition.ChangeSet change : changes) {
            if (!applied.containsKey(change.id())) {
                requirePolicy(change, options, dialect);
            }
        }
        if (dialect.ddlMayCommitImplicitly()) {
            for (SchemaDefinition.ChangeSet change : changes) {
                if (applied.containsKey(change.id())) {
                    continue;
                }
                if (change.verificationSql() == null || change.verificationSql().isBlank()) {
                    throw new IllegalArgumentException("unapplied " + dialect.id()
                            + " schema change requires verificationSql: "
                            + change.id());
                }
                // Dry-run executes nothing, so it neither runs verificationSql nor needs recoverable commits.
                if (change.statements().size() != 1 && !options.dryRun() && !isVerified(conn, change)) {
                    throw new IllegalArgumentException("unapplied " + dialect.id()
                            + " schema change must contain exactly one "
                            + "statement so implicit DDL commits are recoverable: " + change.id());
                }
            }
        }
    }

    /** Change sets {@link #apply} would execute: missing from the history table and not yet verified. */
    List<SchemaDefinition.ChangeSet> unappliedUnverified(Connection conn, List<SchemaDefinition.ChangeSet> changes,
                                                         SchemaSynchronizerOptions options, DatabaseDialect dialect)
            throws SQLException {
        if (changes.isEmpty()) {
            return List.of();
        }
        String history = dialect.qualifyHistoryTable(options.schema(), options.historyTable());
        Map<String, String> applied = historyExists(conn, options, dialect) ? readHistory(conn, history) : Map.of();
        List<SchemaDefinition.ChangeSet> pending = new ArrayList<>();
        for (SchemaDefinition.ChangeSet change : changes) {
            // Dry-run must not execute verificationSql.
            if (!applied.containsKey(change.id()) && (options.dryRun() || !isVerified(conn, change))) {
                pending.add(change);
            }
        }
        return pending;
    }

    private boolean historyExists(Connection conn, SchemaSynchronizerOptions options, DatabaseDialect dialect)
            throws SQLException {
        String catalog = dialect.metadataCatalog(conn, options.schema());
        String schema = dialect.metadataSchemaPattern(options.schema());
        String table = dialect.metadataObjectName(options.historyTable());
        try (ResultSet tables = conn.getMetaData().getTables(catalog, schema, table, new String[]{"TABLE"})) {
            while (tables.next()) {
                if (DatabaseDialect.isRequestedObject(tables, schema, table)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Map<String, String> readHistory(Connection conn, String history) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (var statement = conn.createStatement();
             var rows = statement.executeQuery("SELECT change_id, checksum FROM " + history)) {
            while (rows.next()) {
                String id = rows.getString(1);
                if (result.put(id, rows.getString(2)) != null) {
                    throw new IllegalStateException("duplicate schema history entry: " + id);
                }
            }
        }
        return result;
    }

    private void createHistory(Connection conn, String history, DatabaseDialect dialect) throws SQLException {
        execute(conn, DialectSupport.createHistoryDdl(dialect, history));
        try {
            execute(conn, DialectSupport.addAppliedByColumnDdl(dialect, history));
        } catch (SQLException error) {
            if (!DialectSupport.isDuplicateColumn(dialect, error)) {
                throw error;
            }
        }
    }

    private boolean isVerified(Connection conn, SchemaDefinition.ChangeSet change) throws SQLException {
        if (change.verificationSql() == null || change.verificationSql().isBlank()) {
            return false;
        }
        try (var statement = conn.createStatement(); var rows = statement.executeQuery(change.verificationSql())) {
            if (!rows.next()) {
                throw new IllegalArgumentException("verification query returned no row for schema change: "
                        + change.id());
            }
            boolean verified = rows.getBoolean(1);
            if (rows.wasNull()) {
                throw new IllegalArgumentException("verification query returned NULL for schema change: "
                        + change.id());
            }
            if (rows.next()) {
                throw new IllegalArgumentException("verification query must return exactly one row for schema change: "
                        + change.id());
            }
            return verified;
        }
    }

    private void insertHistory(Connection conn, String history, SchemaDefinition.ChangeSet change,
                               String checksum, long elapsed) throws SQLException {
        try (var statement = conn.prepareStatement(
                "INSERT INTO " + history
                        + " (change_id, checksum, description, applied_by, execution_ms) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, change.id());
            statement.setString(2, checksum);
            statement.setString(3, change.description());
            statement.setString(4, CliCredentials.historyActor());
            statement.setLong(5, elapsed);
            statement.executeUpdate();
        }
    }

    static String checksum(SchemaDefinition.ChangeSet change) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(change.id().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String sql : change.statements()) {
            digest.update(sql.replace("\r\n", "\n").trim().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        if (change.verificationSql() != null) {
            digest.update(change.verificationSql().replace("\r\n", "\n").trim()
                    .getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
        digest.update(change.effectivePhase().name().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private void execute(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Executes one change-set statement. Returns {@code true} when the statement was
     * skipped because the object already exists (adoption / partial prior apply).
     *
     * <p>PostgreSQL marks the whole transaction aborted after a failed statement, so
     * each attempt uses a savepoint when auto-commit is off.
     */
    private boolean executeAllowingAlreadyExists(Connection conn, String sql, String changeId,
                                                 DatabaseDialect dialect) throws SQLException {
        Savepoint savepoint = null;
        // Implicit-commit DDL destroys savepoints (MySQL error 1305; ojdbc cannot release them),
        // and those engines do not abort the transaction on a failed statement.
        boolean usedSavepoint = !conn.getAutoCommit() && !dialect.ddlMayCommitImplicitly();
        try {
            if (usedSavepoint) {
                savepoint = conn.setSavepoint("schema_sync_stmt");
            }
            execute(conn, sql);
        } catch (SQLException exception) {
            if (usedSavepoint && savepoint != null) {
                try {
                    conn.rollback(savepoint);
                } catch (SQLException rollbackFailure) {
                    exception.setNextException(rollbackFailure);
                }
            }
            if (DuplicateObjectSql.isAlreadyExists(exception)) {
                log.debug("[SchemaSynchronizer] Skipping already-present statement in '{}': {}",
                        changeId, summarize(sql));
                return true;
            }
            throw exception;
        }
        // Outside the try: a release failure must not roll back a statement that succeeded.
        DialectSupport.releaseSavepoint(conn, dialect, savepoint);
        return false;
    }

    private static String summarize(String sql) {
        String trimmed = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "...";
    }
}
