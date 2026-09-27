// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Target schema state loaded from {@code schema-definition.json}.
 *
 * <p>SchemaSynchronizer applies non-destructive diffs automatically; destructive
 * changes are logged as pending manual SQL.
 *
 * <p>Collections are copied into unmodifiable ones, keeping declaration order (tables are created
 * in that order). A null collection stays null, and null entries are kept so that validation
 * reports them by name.
 *
 * @param formatVersion definition format; null means 1
 * @param dialect       target dialect name (see {@link DatabaseDialect#parse}); null means PostgreSQL
 * @param tables        declared tables by name, in creation order; may be null
 * @param changes       ordered change sets; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaDefinition(Integer formatVersion, String dialect,
                               Map<String, TableDef> tables, List<ChangeSet> changes) {

    /** The newest {@link #formatVersion()} this release reads and writes. */
    public static final int CURRENT_FORMAT_VERSION = 2;

    /** Copies {@code tables} and {@code changes}. */
    public SchemaDefinition {
        tables = tables == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        changes = copy(changes);
    }

    /** A PostgreSQL definition in format 1. */
    public SchemaDefinition(Map<String, TableDef> tables, List<ChangeSet> changes) {
        this(null, null, tables, changes);
    }

    /** A PostgreSQL definition in format 1 without change sets. */
    public SchemaDefinition(Map<String, TableDef> tables) {
        this(null, null, tables, List.of());
    }

    /**
     * The dialect this definition targets.
     *
     * @throws SchemaDefinitionException when {@link #dialect()} names no supported dialect
     */
    public DatabaseDialect declaredDialect() {
        return DatabaseDialect.parse(dialect);
    }

    /** {@link #formatVersion()}, or 1 when it is absent. */
    public int effectiveFormatVersion() {
        return formatVersion == null ? 1 : formatVersion;
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * One declared table.
     *
     * @param createSql {@code CREATE TABLE} for the table (its target must be the table's name, bare or
     *                  quoted in the dialect's style in its folded spelling); null means the table is
     *                  never created, only reconciled
     * @param columns   columns to add or reconcile; may be null
     * @param indexes   {@code CREATE [UNIQUE] INDEX} statements; may be null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableDef(String createSql, List<ColumnDef> columns, List<String> indexes) {
        /** Copies {@code columns} and {@code indexes}. */
        public TableDef {
            columns = copy(columns);
            indexes = copy(indexes);
        }
    }

    /**
     * One declared column.
     *
     * @param name       column name, {@code [A-Za-z_][A-Za-z0-9_]*}, folded like an unquoted identifier
     * @param definition type and attributes, e.g. {@code VARCHAR(50) NOT NULL DEFAULT 'x'}; null means
     *                   the column is expected but never added or altered
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnDef(String name, String definition) {}

    /**
     * Ordered, immutable schema change for state that cannot be reconstructed from JDBC
     * column metadata (backfills, constraints, functions, triggers, comments, and grants).
     * Statements are safety-checked and the complete change is checksummed before execution.
     *
     * @param id              unique, filesystem-safe id recorded in the history table
     * @param description     optional description, at most 500 characters
     * @param statements      statements executed in order
     * @param verificationSql optional read-only query returning one boolean row: true when the change
     *                        is already in effect (required on MySQL, MariaDB, and Oracle)
     * @param phase           when the change runs relative to the declarative tables; null means
     *                        {@link Phase#BEFORE_SCHEMA}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeSet(String id, String description, List<String> statements, String verificationSql,
                            Phase phase) {
        /** When a change set runs relative to the declarative table reconciliation. */
        public enum Phase {
            /** Before tables, columns, and indexes are reconciled. */
            BEFORE_SCHEMA,
            /** After tables, columns, and indexes are reconciled. */
            AFTER_SCHEMA
        }

        /** Copies {@code statements}. */
        public ChangeSet {
            statements = copy(statements);
        }

        /** A {@link Phase#BEFORE_SCHEMA} change set. */
        public ChangeSet(String id, String description, List<String> statements, String verificationSql) {
            this(id, description, statements, verificationSql, Phase.BEFORE_SCHEMA);
        }

        /** A {@link Phase#BEFORE_SCHEMA} change set without verification SQL. */
        public ChangeSet(String id, String description, List<String> statements) {
            this(id, description, statements, null, Phase.BEFORE_SCHEMA);
        }

        /** {@link #phase()}, or {@link Phase#BEFORE_SCHEMA} when it is absent. */
        public Phase effectivePhase() {
            return phase == null ? Phase.BEFORE_SCHEMA : phase;
        }
    }
}
