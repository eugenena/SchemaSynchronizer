package com.thinkai.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Target schema state loaded from {@code schema-definition.json}.
 *
 * <p>SchemaApplier applies non-destructive diffs automatically; destructive
 * changes are logged as pending manual SQL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaDefinition(Map<String, TableDef> tables, List<ChangeSet> changes) {

    public SchemaDefinition(Map<String, TableDef> tables) {
        this(tables, List.of());
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
