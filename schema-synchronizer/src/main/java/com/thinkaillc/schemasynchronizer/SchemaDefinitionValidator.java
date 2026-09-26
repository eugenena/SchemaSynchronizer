// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

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
     */
    public static void validateFile(Path definitionFile) throws Exception {
        validateFile(definitionFile, "public");
    }

    /**
     * Validates a definition file using {@code schema} for createSql / index target checks.
     */
    public static void validateFile(Path definitionFile, String schema) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SchemaDefinition definition = mapper.readValue(Files.readString(definitionFile), SchemaDefinition.class);
        validate(definition, schema);
    }

    /**
     * Validates an in-memory definition. Throws {@link IllegalArgumentException} or
     * {@link IllegalStateException} when the definition is not ready to sync.
     */
    public static void validate(SchemaDefinition definition, String schema) {
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
        SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(
                schema, "schema_synchronizer_history", 7_249_031_147L, false, true, true);
        SchemaSynchronizer.validateDeclarative(definition, dialect, options);
        new ChangeSetExecutor().validate(definition.changes(), options);
    }
}
