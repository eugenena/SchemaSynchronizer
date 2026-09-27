// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real CLI entry points in a child JVM on the test classpath, so tests control the
 * environment ({@code SCHEMA_DB_PASSWORD}, {@code SCHEMA_SYNCHRONIZER_ACTOR}) that the
 * entry points read and that a running JVM cannot change.
 */
final class ChildJvm {

    record Result(int exitCode, String output) {
        Result assertSuccess() {
            if (exitCode != 0) {
                throw new AssertionError("child JVM exited " + exitCode + ":\n" + output);
            }
            return this;
        }
    }

    private ChildJvm() {
    }

    /**
     * @param env        variables to set; a {@code null} value removes the variable
     * @param jvmOptions extra {@code -D...} options for the child
     * @param args       {@link Launcher} arguments: entry point name, then its argv
     */
    static Result run(Map<String, String> env, List<String> jvmOptions, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Launcher.class.getName());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove(CliCredentials.PASSWORD_ENV);
        builder.environment().remove(CliCredentials.ACTOR_ENV);
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
            throw new AssertionError("child JVM timed out: " + command);
        }
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    /** Dispatches to one real entry point; exits 1 with the exception on failure. */
    static final class Launcher {
        static final String ACTOR_MARKER = "ACTOR=";
        static final String PASSWORD_MARKER = "PASSWORD=";

        public static void main(String[] args) {
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            try {
                switch (args[0]) {
                    case "sync" -> SchemaSynchronizer.main(rest);
                    case "dry-run" -> SchemaSynchronizer.dryRunMain(rest);
                    case "validate" -> SchemaSynchronizer.validateMain(rest);
                    case "serialize" -> SchemaSerializer.main(rest);
                    case "snapshot" -> SchemaSnapshotWriter.main(rest);
                    case "actor" -> System.out.println(ACTOR_MARKER + CliCredentials.historyActor() + "|");
                    case "password" -> System.out.println(PASSWORD_MARKER
                            + CliCredentials.requirePasswordFromEnv(rest[0]) + "|");
                    default -> throw new IllegalArgumentException("unknown entry point " + args[0]);
                }
            } catch (Throwable failure) {
                System.out.println("FAILED " + failure.getClass().getName() + ": " + failure.getMessage());
                failure.printStackTrace(System.out);
                System.out.flush();
                System.exit(1);
            }
            System.out.flush();
            System.exit(0);
        }
    }
}
