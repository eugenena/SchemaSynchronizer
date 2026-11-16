// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDefinitionProfilesCliTest {

    private static final String COVERED_TABLE = """
            {
              "formatVersion": 2,
              "dialect": "postgresql",
              "tables": {
                "items": {
                  "createSql": "CREATE TABLE items (id INT, other_id INT, FOREIGN KEY (other_id) REFERENCES others (id))",
                  "columns": [{"name": "id", "definition": "INT"}, {"name": "other_id", "definition": "INT"}]
                }
              },
              "changes": [
                {
                  "id": "cover-items-fk",
                  "statements": ["ALTER TABLE items ADD CONSTRAINT items_other_fk FOREIGN KEY (other_id) REFERENCES others (id)"],
                  "verificationSql": "SELECT COUNT(*) > 0 FROM information_schema.table_constraints WHERE table_name = 'items'"
                }
              ]
            }
            """;

    /** The same shape, but the change set verifies a different table: items stays uncovered. */
    private static final String UNCOVERED_TABLE = COVERED_TABLE.replace("table_name = 'items'",
            "table_name = 'audit_log'");

    @TempDir
    Path tempDirectory;

    @Test
    void compareReportsPreservationFindingsForBothProfiles() throws Exception {
        Path covered = write("covered.json", COVERED_TABLE);
        Path uncovered = write("uncovered.json", UNCOVERED_TABLE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int status = SchemaDefinitionProfilesCli.run(
                new String[]{"compare", covered.toString(), uncovered.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), nullPrintStream());

        // The profiles agree structurally, so the status is success; the uncovered foreign key
        // of the second profile is still reported and labeled with its file.
        assertThat(status).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("preservation: " + uncovered + ": table items")
                .doesNotContain("preservation: " + covered + ":");
    }

    @Test
    void compareLabelsFindingsWhenBothProfilesAreUncovered() throws Exception {
        Path left = write("left.json", UNCOVERED_TABLE);
        Path right = write("right.json", UNCOVERED_TABLE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int status = SchemaDefinitionProfilesCli.run(
                new String[]{"compare", left.toString(), right.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), nullPrintStream());

        assertThat(status).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("preservation: " + left + ": table items")
                .contains("preservation: " + right + ": table items");
    }

    private Path write(String name, String json) throws Exception {
        Path file = tempDirectory.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    private static PrintStream nullPrintStream() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}