// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the real {@link SchemaSynchronizerCli#main} in a child JVM with a controlled environment. */
final class CliProcess {

    record Result(int exitCode, String output) {
        Result assertSuccess() {
            if (exitCode != 0) {
                throw new AssertionError("CLI exited " + exitCode + ":\n" + output);
            }
            return this;
        }
    }

    private CliProcess() {
    }

    /** @param env variables to set; a {@code null} value removes the variable */
    static Result run(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SchemaSynchronizerCli.class.getName());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("SCHEMA_DB_PASSWORD");
        builder.environment().remove("SCHEMA_SYNCHRONIZER_ACTOR");
        env.forEach((key, value) -> {
            if (value == null) {
                builder.environment().remove(key);
            } else {
                builder.environment().put(key, value);
            }
        });
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("CLI child JVM timed out: " + String.join(" ", args));
        }
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
}
