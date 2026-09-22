package com.thinkai.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    record Result(int applied, List<String> plannedSql) {}

    Result apply(Connection conn, List<SchemaDefinition.ChangeSet> changes, SchemaApplierOptions options)
            throws Exception {
        List<SchemaDefinition.ChangeSet> safeChanges = validate(changes);
        if (safeChanges.isEmpty()) {
            return new Result(0, List.of());
        }

        String history = SqlIdentifiers.qualified(options.schema(), options.historyTable());
        Map<String, String> applied = historyExists(conn, options) ? readHistory(conn, history) : Map.of();
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
            if (!isVerified(conn, change)) {
                planned.addAll(change.statements());
            }
        }
        if (options.dryRun() || unrecordedCount == 0) {
            return new Result(0, List.copyOf(planned));
        }

        createHistory(conn, history);
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
            for (String sql : change.statements()) {
                execute(conn, sql);
            }
            long elapsed = Duration.between(started, Instant.now()).toMillis();
            if (change.verificationSql() != null && !isVerified(conn, change)) {
                throw new IllegalStateException("verification failed after schema change '" + change.id() + "'");
            }
            insertHistory(conn, history, change, checksum, elapsed);
            appliedCount++;
        }
        return new Result(appliedCount, List.copyOf(planned));
    }

    List<SchemaDefinition.ChangeSet> validate(List<SchemaDefinition.ChangeSet> changes) {
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
            change.statements().forEach(NonDestructiveSqlPolicy::requireSafe);
            if (change.verificationSql() != null) {
                NonDestructiveSqlPolicy.requireReadOnlyVerification(change.verificationSql());
            }
        }
        return List.copyOf(changes);
    }

    void validateHistory(Connection conn, List<SchemaDefinition.ChangeSet> changes, SchemaApplierOptions options)
            throws Exception {
        if (!historyExists(conn, options)) {
            return;
        }
        String history = SqlIdentifiers.qualified(options.schema(), options.historyTable());
        Map<String, String> applied = readHistory(conn, history);
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
    }

    private boolean historyExists(Connection conn, SchemaApplierOptions options) throws SQLException {
        try (ResultSet tables = conn.getMetaData().getTables(
                null, options.schema(), options.historyTable(), new String[]{"TABLE"})) {
            return tables.next();
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

    private void createHistory(Connection conn, String history) throws SQLException {
        execute(conn, "CREATE TABLE IF NOT EXISTS " + history + " ("
                + "installed_rank BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "change_id VARCHAR(200) NOT NULL UNIQUE, "
                + "checksum CHAR(64) NOT NULL, "
                + "description VARCHAR(500), "
                + "applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "execution_ms BIGINT NOT NULL)");
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
                        + " (change_id, checksum, description, execution_ms) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, change.id());
            statement.setString(2, checksum);
            statement.setString(3, change.description());
            statement.setLong(4, elapsed);
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
}
