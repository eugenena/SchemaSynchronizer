// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Offline validation for hand-authored {@code schema-definition.json} files.
 *
 * <p>Does not connect to a database. Use CLI {@code dry-run} against a disposable
 * target to preview live convergence.
 */
public final class SchemaDefinitionValidator {

    private SchemaDefinitionValidator() {
    }

    /**
     * Validates a definition file against structural, dialect, SQL-safety, and
     * change-set rules using schema {@code public}.
     *
     * @throws SchemaDefinitionException when the file cannot be read or parsed, or is not ready to sync
     */
    public static void validateFile(Path definitionFile) {
        validateFile(definitionFile, "public");
    }

    /**
     * Validates a definition file using {@code schema} for createSql / index target checks.
     *
     * @throws SchemaDefinitionException when the file cannot be read or parsed, or is not ready to sync
     */
    public static void validateFile(Path definitionFile, String schema) {
        if (definitionFile == null) {
            throw new SchemaDefinitionException("definition file is required");
        }
        SchemaDefinition definition;
        try {
            definition = new ObjectMapper().readValue(Files.readString(definitionFile), SchemaDefinition.class);
        } catch (java.io.IOException failure) {
            throw SchemaExceptions.definitionUnreadable(definitionFile.toString(), failure);
        }
        validate(definition, schema);
    }

    /**
     * Validates an in-memory definition.
     *
     * @throws SchemaDefinitionException when the definition is not ready to sync
     */
    public static void validate(SchemaDefinition definition, String schema) {
        try {
            validateUnchecked(definition, schema);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw SchemaExceptions.translate(failure);
        }
    }

    private static void validateUnchecked(SchemaDefinition definition, String schema) {
        if (schema == null) {
            throw new IllegalArgumentException("schema is required");
        }
        if (definition == null) {
            throw new IllegalArgumentException("schema definition is null");
        }
        if (definition.effectiveFormatVersion() > SchemaDefinition.CURRENT_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported schema format version: "
                    + definition.effectiveFormatVersion());
        }
        DatabaseDialect dialect = definition.declaredDialect();
        if (definition.formatVersion() != null && definition.formatVersion() >= 2
                && (definition.dialect() == null || definition.dialect().isBlank())) {
            throw new IllegalArgumentException("formatVersion 2 definitions require a dialect");
        }
        int maxId = dialect.maxIdentifierLength();
        if (schema.length() > maxId) {
            throw new IllegalArgumentException(dialect.id() + " schema identifiers must be at most "
                    + maxId + " characters: " + schema);
        }
        if ("public".equalsIgnoreCase(schema)
                && (dialect == DatabaseDialect.SQLSERVER
                || dialect == DatabaseDialect.ORACLE
                || dialect.isMySqlFamily())) {
            throw new IllegalArgumentException(dialect.id()
                    + " definitions must not use schema public; pass dbo / catalog / connected user");
        }
        SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(
                schema, "schema_synchronizer_history", 7_249_031_147L, false, true, true);
        SchemaSynchronizer.validateDeclarative(definition, dialect, options);
        new ChangeSetExecutor().validate(definition.changes(), options, dialect);
    }

    /**
     * Owner-visible findings for FK preservation coverage (2.3.0+). Every inline FOREIGN KEY
     * declared in a table createSql should be claimed by a change set whose verificationSql
     * mentions the same table, so a legacy database can adopt the constraint through
     * verification instead of re-executing DDL that may already exist. Returns one human
     * readable finding per uncovered table; empty when coverage is complete.
     */
    public static java.util.List<String> preservationFindings(SchemaDefinition definition) {
        java.util.List<String> findings = new java.util.ArrayList<>();
        if (definition == null) {
            return findings;
        }
        String marker = "FOREIGN KEY";
        java.util.Set<String> verifiedTables = new java.util.HashSet<>();
        for (SchemaDefinition.ChangeSet change : definition.changes()) {
            if (change.verificationSql() != null && !change.verificationSql().isBlank()) {
                String haystack = change.verificationSql().toLowerCase(Locale.ROOT);
                definition.tables().keySet().forEach(table -> {
                    if (haystack.contains(table.toLowerCase(Locale.ROOT))) {
                        verifiedTables.add(table.toLowerCase(Locale.ROOT));
                    }
                });
            }
        }
        for (var entry : definition.tables().entrySet()) {
            String createSql = entry.getValue().createSql();
            if (createSql != null && createSql.toUpperCase(Locale.ROOT).contains(marker)
                    && !verifiedTables.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                findings.add("table " + entry.getKey()
                        + " declares a FOREIGN KEY in createSql but no change set with"
                        + " verificationSql covers it; adoption cannot verify the constraint"
                        + " without re-executing DDL");
            }
        }
        return findings;
    }

    /**
     * Compares two profiles of the same schema (for example PostgreSQL and H2 definitions)
     * structurally: tables missing on either side and columns missing per table. Type spelling
     * differences between dialects are expected and not reported. Returns human readable
     * differences; empty means the profiles agree.
     */
    public static java.util.List<String> compare(SchemaDefinition left, SchemaDefinition right) {
        java.util.List<String> differences = new java.util.ArrayList<>();
        if (left == null || right == null) {
            differences.add("both definitions are required to compare");
            return differences;
        }
        java.util.Set<String> leftTables = lowerKeys(left);
        java.util.Set<String> rightTables = lowerKeys(right);
        for (String table : leftTables) {
            if (!rightTables.contains(table)) {
                differences.add("table only in the first profile: " + table);
            }
        }
        for (String table : rightTables) {
            if (!leftTables.contains(table)) {
                differences.add("table only in the second profile: " + table);
            }
        }
        for (String table : leftTables) {
            if (!rightTables.contains(table)) {
                continue;
            }
            java.util.Set<String> leftColumns = columnNames(left, table);
            java.util.Set<String> rightColumns = columnNames(right, table);
            for (String column : leftColumns) {
                if (!rightColumns.contains(column)) {
                    differences.add("column only in the first profile: " + table + "." + column);
                }
            }
            for (String column : rightColumns) {
                if (!leftColumns.contains(column)) {
                    differences.add("column only in the second profile: " + table + "." + column);
                }
            }
        }
        return differences;
    }

    private static java.util.Set<String> lowerKeys(SchemaDefinition definition) {
        java.util.Set<String> names = new java.util.HashSet<>();
        definition.tables().keySet().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        return names;
    }

    private static java.util.Set<String> columnNames(SchemaDefinition definition, String table) {
        java.util.Set<String> names = new java.util.HashSet<>();
        definition.tables().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(table))
                .findFirst()
                .ifPresent(entry -> entry.getValue().columns().forEach(column ->
                        names.add(column.name().toLowerCase(Locale.ROOT))));
        return names;
    }
}
