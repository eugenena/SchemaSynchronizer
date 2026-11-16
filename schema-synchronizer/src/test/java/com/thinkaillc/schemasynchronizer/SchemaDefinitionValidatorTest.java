// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
                .isInstanceOf(SchemaDefinitionException.class)
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

    @Test
    void preservationFindingsReadForeignAndKeyAcrossFormatting() {
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql",
                Map.of("items", new SchemaDefinition.TableDef("""
                        CREATE TABLE items (id INT, other_id INT,
                        FOREIGN
                        KEY (other_id) REFERENCES others (id))
                        """, List.of(new SchemaDefinition.ColumnDef("id", "INT")), List.of())),
                List.of());

        assertThat(SchemaDefinitionValidator.preservationFindings(definition))
                .hasSize(1)
                .first().asString().contains("table items");
    }

    @Test
    void verificationSqlMentioningTheTableAsStringCoversIt() {
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql",
                Map.of("items", new SchemaDefinition.TableDef(
                        "CREATE TABLE items (id INT, other_id INT, FOREIGN KEY (other_id) REFERENCES others (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "INT")), List.of())),
                List.of(new SchemaDefinition.ChangeSet("cover", "adopt the fk",
                        List.of("ALTER TABLE items ADD CONSTRAINT items_other_fk FOREIGN KEY (other_id)"
                                + " REFERENCES others (id)"),
                        "SELECT COUNT(*) > 0 FROM information_schema.table_constraints"
                                + " WHERE table_name = 'items'")));

        assertThat(SchemaDefinitionValidator.preservationFindings(definition)).isEmpty();
    }

    @Test
    void longerNamesInVerificationSqlDoNotCoverTheTable() {
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql",
                Map.of("items", new SchemaDefinition.TableDef(
                        "CREATE TABLE items (id INT, other_id INT, FOREIGN KEY (other_id) REFERENCES others (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", "INT")), List.of())),
                List.of(new SchemaDefinition.ChangeSet("cover", "covers a longer-named table",
                        List.of("SELECT 1"),
                        "SELECT COUNT(*) > 0 FROM order_items")));

        assertThat(SchemaDefinitionValidator.preservationFindings(definition))
                .hasSize(1)
                .first().asString().contains("table items");
    }

    @Test
    void absentCollectionsCountAsEmpty() {
        SchemaDefinition withoutChanges = new SchemaDefinition(2, "postgresql", Map.of(), null);
        assertThat(SchemaDefinitionValidator.preservationFindings(withoutChanges)).isEmpty();
        assertThatCode(() -> SchemaDefinitionValidator.compare(withoutChanges, withoutChanges))
                .doesNotThrowAnyException();

        SchemaDefinition withoutTables = new SchemaDefinition(2, "postgresql", null, List.of());
        assertThat(SchemaDefinitionValidator.preservationFindings(withoutTables)).isEmpty();
        assertThat(SchemaDefinitionValidator.compare(withoutTables, withoutTables)).isEmpty();
        assertThat(SchemaFingerprints.definitionFingerprint(withoutTables))
                .isEqualTo(SchemaFingerprints.definitionFingerprint(withoutChanges));

        SchemaDefinition withoutColumnsOrIndexes = new SchemaDefinition(2, "postgresql", Map.of(
                "items", new SchemaDefinition.TableDef("CREATE TABLE items (id INT)", null, null)), List.of());
        assertThat(SchemaFingerprints.definitionFingerprint(withoutColumnsOrIndexes))
                .isEqualTo(SchemaFingerprints.definitionFingerprint(new SchemaDefinition(2, "postgresql",
                        Map.of("items", new SchemaDefinition.TableDef("CREATE TABLE items (id INT)",
                                List.of(), List.of())), List.of())));
        assertThat(SchemaDefinitionValidator.compare(withoutColumnsOrIndexes, withoutColumnsOrIndexes))
                .isEmpty();
    }
}
