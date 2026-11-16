// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaFingerprintsTest {

    private static Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:fp_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static SchemaSynchronizerOptions options(String schema) {
        return new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L,
                false, true, true);
    }

    private static void execute(Connection connection, String... sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String one : sql) {
                statement.execute(one);
            }
        }
    }

    @Test
    void physicalColumnOrderDoesNotChangeTheFingerprint() throws Exception {
        String ordered;
        try (Connection orderedColumns = connection()) {
            execute(orderedColumns, "CREATE TABLE items (a INT, b INT)");
            ordered = SchemaFingerprints.liveSchemaFingerprint(orderedColumns, options("public"));
        }
        try (Connection reversedColumns = connection()) {
            execute(reversedColumns, "CREATE TABLE items (b INT, a INT)");
            assertThat(SchemaFingerprints.liveSchemaFingerprint(reversedColumns, options("public")))
                    .isEqualTo(ordered);
        }
    }

    @Test
    void theConfiguredSchemaIsReadInTheDialectsStoredSpelling() throws Exception {
        String populated;
        try (Connection connection = connection()) {
            execute(connection, "CREATE TABLE items (id INT)");
            populated = SchemaFingerprints.liveSchemaFingerprint(connection, options("public"));
            assertThat(SchemaFingerprints.liveSchemaFingerprint(connection, options("PUBLIC")))
                    .isEqualTo(populated);
        }
        try (Connection empty = connection()) {
            // Proves the folded spelling actually found the schema: an unpopulated one differs.
            assertThat(SchemaFingerprints.liveSchemaFingerprint(empty, options("public")))
                    .isNotEqualTo(populated);
        }
    }

    @Test
    void wildcardSiblingsDoNotChangeTheFingerprintOrTheCounts() throws Exception {
        try (Connection connection = connection()) {
            execute(connection,
                    "CREATE SCHEMA APP_DB",
                    "CREATE TABLE APP_DB.OTHER (id INT PRIMARY KEY)",
                    "CREATE TABLE APP_DB.ITEMS (id INT PRIMARY KEY, other_id INT,"
                            + " FOREIGN KEY (other_id) REFERENCES APP_DB.OTHER (id))");
            String before = SchemaFingerprints.liveSchemaFingerprint(connection, options("app_db"));

            execute(connection,
                    "CREATE SCHEMA APP1DB",
                    "CREATE TABLE APP1DB.OTHER (id INT PRIMARY KEY)",
                    "CREATE TABLE APP1DB.ITEMS (id INT PRIMARY KEY, other_id INT, extra INT,"
                            + " FOREIGN KEY (other_id) REFERENCES APP1DB.OTHER (id))");

            assertThat(SchemaFingerprints.liveSchemaFingerprint(connection, options("app_db")))
                    .isEqualTo(before);
            assertThat(ConstraintInventory.countForeignKeys(connection, "app_db", null)).isEqualTo(1);
            assertThat(ConstraintInventory.countForeignKeys(connection, "app1db", null)).isEqualTo(1);
        }
    }

    @Test
    void foreignKeysParticipateInTheFingerprint() throws Exception {
        String withForeignKey;
        try (Connection connection = connection()) {
            execute(connection,
                    "CREATE TABLE parent (id INT PRIMARY KEY)",
                    "CREATE TABLE child (id INT PRIMARY KEY, parent_id INT,"
                            + " FOREIGN KEY (parent_id) REFERENCES parent (id))");
            withForeignKey = SchemaFingerprints.liveSchemaFingerprint(connection, options("public"));
        }
        try (Connection connection = connection()) {
            execute(connection,
                    "CREATE TABLE parent (id INT PRIMARY KEY)",
                    "CREATE TABLE child (id INT PRIMARY KEY, parent_id INT)");
            assertThat(SchemaFingerprints.liveSchemaFingerprint(connection, options("public")))
                    .isNotEqualTo(withForeignKey);
        }
    }

    @Test
    void primaryKeysAreReportedInKeySequenceOrder() throws Exception {
        try (Connection connection = connection()) {
            execute(connection, "CREATE TABLE composite (a INT, b INT, c INT,"
                    + " PRIMARY KEY (c, a, b))");
            assertThat(ConstraintInventory.primaryKeys(connection, "public", "composite"))
                    .containsExactly("C", "A", "B");
        }
    }

    @Test
    void namesFoldWithAFixedLocale() {
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql", Map.of(
                "ITEMS", new SchemaDefinition.TableDef("CREATE TABLE ITEMS (ID INT)",
                        List.of(new SchemaDefinition.ColumnDef("ID", "INT")), List.of())), List.of());
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            String english = SchemaFingerprints.definitionFingerprint(definition);
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(SchemaFingerprints.definitionFingerprint(definition)).isEqualTo(english);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void stringContentsAreNotNormalizedAway() {
        assertThat(SchemaFingerprints.definitionFingerprint(definitionWithDefault("DEFAULT 'a  b'")))
                .isNotEqualTo(SchemaFingerprints.definitionFingerprint(definitionWithDefault("DEFAULT 'a b'")));
        // Formatting outside the string does not participate.
        assertThat(SchemaFingerprints.definitionFingerprint(definitionWithDefault("DEFAULT   'a b'")))
                .isEqualTo(SchemaFingerprints.definitionFingerprint(definitionWithDefault("DEFAULT 'a b'")));
    }

    private static SchemaDefinition definitionWithDefault(String defaultClause) {
        return new SchemaDefinition(2, "postgresql", Map.of("items", new SchemaDefinition.TableDef(
                "CREATE TABLE items (id INT, note VARCHAR(50) " + defaultClause + ")",
                List.of(new SchemaDefinition.ColumnDef("note", "VARCHAR(50) " + defaultClause)),
                List.of())), List.of());
    }
}