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
     *
     * <p>Keywords and table references are read as SQL tokens, so a line break inside
     * {@code FOREIGN KEY} is still found and a mention of a longer name ({@code order_items})
     * does not cover {@code items}. Absent {@code tables} or {@code changes} count as empty,
     * matching synchronization.
     */
    public static java.util.List<String> preservationFindings(SchemaDefinition definition) {
        java.util.List<String> findings = new java.util.ArrayList<>();
        if (definition == null) {
            return findings;
        }
        SqlLexer.Mode mode = lexerMode(definition);
        java.util.Set<String> verifiedTables = new java.util.HashSet<>();
        for (SchemaDefinition.ChangeSet change : nullToEmpty(definition.changes())) {
            if (change == null || change.verificationSql() == null || change.verificationSql().isBlank()) {
                continue;
            }
            for (String table : nullToEmpty(definition.tables()).keySet()) {
                if (referencesTable(change.verificationSql(), table, mode)) {
                    verifiedTables.add(table.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (var entry : nullToEmpty(definition.tables()).entrySet()) {
            String createSql = entry.getValue().createSql();
            if (createSql != null && declaresForeignKey(createSql, mode)
                    && !verifiedTables.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                findings.add("table " + entry.getKey()
                        + " declares a FOREIGN KEY in createSql but no change set with"
                        + " verificationSql covers it; adoption cannot verify the constraint"
                        + " without re-executing DDL");
            }
        }
        return findings;
    }

    private static SqlLexer.Mode lexerMode(SchemaDefinition definition) {
        try {
            return definition.declaredDialect().lexerMode();
        } catch (RuntimeException unknownDialect) {
            // The dialect itself is rejected by validation; profile reporting stays usable.
            return SqlLexer.Mode.POSTGRES;
        }
    }

    /** Absent collections are empty, consistently with synchronization. */
    private static <T> java.util.List<T> nullToEmpty(java.util.List<T> list) {
        return list == null ? java.util.List.of() : list;
    }

    private static java.util.Map<String, SchemaDefinition.TableDef> nullToEmpty(
            java.util.Map<String, SchemaDefinition.TableDef> tables) {
        return tables == null ? java.util.Map.of() : tables;
    }

    /**
     * Whether {@code sql} references {@code table} as a whole SQL token: a bare or quoted
     * identifier, or a string literal naming it (PostgreSQL catalog queries pass the table
     * name as text). A longer name ({@code order_items}) is one token and never covers
     * {@code items}.
     */
    private static boolean referencesTable(String sql, String table, SqlLexer.Mode mode) {
        String folded = table.toLowerCase(Locale.ROOT);
        try {
            for (SqlTokenizer.Token token : SqlTokenizer.tokenize(sql, mode)) {
                if (token.type() == SqlTokenizer.Type.WORD
                        && token.text().toLowerCase(Locale.ROOT).equals(folded)) {
                    return true;
                }
                if ((token.type() == SqlTokenizer.Type.QUOTED || token.type() == SqlTokenizer.Type.STRING)
                        && token.value() != null
                        && token.value().toLowerCase(Locale.ROOT).equals(folded)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException unlexable) {
            // The SQL cannot be tokenized (it would also fail synchronization); keep the
            // legacy whole-text match so offline reporting still works.
            return sql.toLowerCase(Locale.ROOT).contains(folded);
        }
    }

    /** Whether {@code createSql} declares FOREIGN KEY as two adjacent keyword tokens. */
    private static boolean declaresForeignKey(String createSql, SqlLexer.Mode mode) {
        try {
            java.util.List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(createSql, mode);
            for (int index = 0; index + 1 < tokens.size(); index++) {
                if (tokens.get(index).keyword("FOREIGN") && tokens.get(index + 1).keyword("KEY")) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException unlexable) {
            // Unlexable SQL keeps a whitespace-tolerant whole-text match.
            return createSql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").contains("FOREIGN KEY");
        }
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
        java.util.Map<String, SchemaDefinition.TableDef> tables = definition.tables();
        if (tables != null) {
            tables.keySet().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
        }
        return names;
    }

    private static java.util.Set<String> columnNames(SchemaDefinition definition, String table) {
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Map<String, SchemaDefinition.TableDef> tables = definition.tables();
        if (tables == null) {
            return names;
        }
        tables.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(table))
                .findFirst()
                .ifPresent(entry -> {
                    java.util.List<SchemaDefinition.ColumnDef> columns = entry.getValue().columns();
                    if (columns == null) {
                        return;
                    }
                    columns.forEach(column -> {
                        if (column != null && column.name() != null) {
                            names.add(column.name().toLowerCase(Locale.ROOT));
                        }
                    });
                });
        return names;
    }
}
