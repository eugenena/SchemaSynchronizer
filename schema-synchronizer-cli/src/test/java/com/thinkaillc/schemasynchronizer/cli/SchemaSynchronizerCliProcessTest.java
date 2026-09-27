// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real {@code main} in a child JVM: exit codes and error output that need no database. */
class SchemaSynchronizerCliProcessTest {

    @TempDir
    Path workDir;

    @Test
    void versionExitsZero() throws Exception {
        CliProcess.Result result = CliProcess.run(Map.of(), "--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).startsWith("SchemaSynchronizer ");
    }

    @Test
    void zeroArgumentsExitTwo() throws Exception {
        CliProcess.Result result = CliProcess.run(Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("A command is required.", "Usage:");
    }

    @Test
    void unknownCommandExitsTwo() throws Exception {
        CliProcess.Result result = CliProcess.run(Map.of(), "frobnicate");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("Unknown command: frobnicate", "Usage:");
    }

    @Test
    void missingPasswordEnvironmentFailsWithoutConnecting() throws Exception {
        Path output = workDir.resolve("unused.json");
        Map<String, String> env = new HashMap<>();
        env.put("SCHEMA_DB_PASSWORD", null);

        CliProcess.Result result = CliProcess.run(env,
                "serialize", "jdbc:postgresql://127.0.0.1:1/app", "app", "-", "app", output.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("SCHEMA_DB_PASSWORD must be set");
        assertThat(output).doesNotExist();
    }

    @Test
    void literalPasswordArgumentIsRejectedAndNotEchoed() throws Exception {
        CliProcess.Result result = CliProcess.run(Map.of("SCHEMA_DB_PASSWORD", "x"),
                "sync", "jdbc:postgresql://127.0.0.1:1/app", "app", "Hunter2Literal",
                workDir.resolve("x.json").toString(), "app");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("literal passwords on the command line are not allowed")
                .doesNotContain("Hunter2Literal");
    }

    @Test
    void driverErrorEchoingTheUrlIsRedacted() throws Exception {
        Path schemaFile = workDir.resolve("schema.json");
        Files.writeString(schemaFile, "{\"tables\": {}, \"changes\": []}", StandardCharsets.UTF_8);

        CliProcess.Result result = CliProcess.run(Map.of("SCHEMA_DB_PASSWORD", "x"),
                "sync", "jdbc:nosuchdriver://db/app?user=a&password=Leak3dSecret", "a", "-",
                schemaFile.toString(), "app");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("SchemaSynchronizer failed:", "password=****")
                .doesNotContain("Leak3dSecret");
    }
}
