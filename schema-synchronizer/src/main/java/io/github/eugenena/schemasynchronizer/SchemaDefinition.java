// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package io.github.eugenena.schemasynchronizer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Target schema state loaded from {@code schema-definition.json}.
 *
 * <p>SchemaSynchronizer applies non-destructive diffs automatically; destructive
 * changes are logged as pending manual SQL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaDefinition(Integer formatVersion, String dialect,
                               Map<String, TableDef> tables, List<ChangeSet> changes) {

    public static final int CURRENT_FORMAT_VERSION = 2;

    public SchemaDefinition(Map<String, TableDef> tables, List<ChangeSet> changes) {
        this(null, null, tables, changes);
    }

    public SchemaDefinition(Map<String, TableDef> tables) {
        this(null, null, tables, List.of());
    }

    public DatabaseDialect declaredDialect() {
        return DatabaseDialect.parse(dialect);
    }

    public int effectiveFormatVersion() {
        return formatVersion == null ? 1 : formatVersion;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableDef(String createSql, List<ColumnDef> columns, List<String> indexes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnDef(String name, String definition) {}

    /**
     * Ordered, immutable schema change for state that cannot be reconstructed from JDBC
     * column metadata (backfills, constraints, functions, triggers, extensions, and grants).
     * Statements are safety-checked and the complete change is checksummed before execution.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeSet(String id, String description, List<String> statements, String verificationSql,
                            Phase phase) {
        public enum Phase {
            BEFORE_SCHEMA,
            AFTER_SCHEMA
        }

        public ChangeSet(String id, String description, List<String> statements, String verificationSql) {
            this(id, description, statements, verificationSql, Phase.BEFORE_SCHEMA);
        }

        public ChangeSet(String id, String description, List<String> statements) {
            this(id, description, statements, null, Phase.BEFORE_SCHEMA);
        }

        public Phase effectivePhase() {
            return phase == null ? Phase.BEFORE_SCHEMA : phase;
        }
    }
}
