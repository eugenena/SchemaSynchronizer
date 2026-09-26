// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaDefinitionValidatorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsHandAuthoredMinimalDefinition() {
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql", Map.of(
                "customers", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS customers (id BIGINT PRIMARY KEY NOT NULL)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")),
                        List.of()
                )
        ), List.of());

        assertThatCode(() -> SchemaDefinitionValidator.validate(definition, "public"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFormatVersion2WithoutDialect() {
        SchemaDefinition definition = new SchemaDefinition(2, null, Map.of(), List.of());

        assertThatThrownBy(() -> SchemaDefinitionValidator.validate(definition, "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require a dialect");
    }

    @Test
    void validateFileReadsJson() throws Exception {
        Path file = tempDirectory.resolve("schema-definition.json");
        Files.writeString(file, """
                {
                  "formatVersion": 2,
                  "dialect": "postgresql",
                  "tables": {},
                  "changes": []
                }
                """);

        assertThatCode(() -> SchemaDefinitionValidator.validateFile(file))
                .doesNotThrowAnyException();
    }
}
