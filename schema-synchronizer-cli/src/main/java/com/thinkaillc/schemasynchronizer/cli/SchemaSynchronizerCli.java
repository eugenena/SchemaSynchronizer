// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import com.thinkaillc.schemasynchronizer.SchemaSerializer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizer;

import java.io.PrintStream;
import java.util.Arrays;

/** Self-contained command-line dispatcher for schema serialization and synchronization. */
public final class SchemaSynchronizerCli {

    private SchemaSynchronizerCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err,
                SchemaSerializer::main,
                SchemaSynchronizer::main,
                SchemaSynchronizer::dryRunMain,
                SchemaSynchronizer::validateMain);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err,
                   CliCommand serializer, CliCommand synchronizer) {
        return run(args, out, err, serializer, synchronizer, synchronizer, argsIgnored -> {
            throw new IllegalStateException("validate command is unavailable in this test harness");
        });
    }

    static int run(String[] args, PrintStream out, PrintStream err,
                   CliCommand serializer, CliCommand synchronizer,
                   CliCommand dryRun, CliCommand validate) {
        if (args.length == 0) {
            err.println("A command is required.");
            printUsage(err);
            return 2;
        }

        String command = args[0];
        if ("--help".equals(command) || "-h".equals(command) || "help".equals(command)) {
            printUsage(out);
            return 0;
        }
        if ("--version".equals(command) || "-V".equals(command) || "version".equals(command)) {
            out.println("SchemaSynchronizer " + implementationVersion());
            return 0;
        }

        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (command) {
                case "serialize" -> commandArgs.length == 5
                        ? execute(serializer, commandArgs)
                        : invalidArguments(command, err);
                case "sync" -> commandArgs.length >= 4 && commandArgs.length <= 6
                        ? execute(synchronizer, commandArgs)
                        : invalidArguments(command, err);
                case "dry-run" -> commandArgs.length >= 4 && commandArgs.length <= 6
                        ? execute(dryRun, commandArgs)
                        : invalidArguments(command, err);
                case "validate" -> commandArgs.length >= 1 && commandArgs.length <= 2
                        ? execute(validate, commandArgs)
                        : invalidArguments(command, err);
                default -> {
                    err.println("Unknown command: " + command);
                    printUsage(err);
                    yield 2;
                }
            };
        } catch (Exception exception) {
            String message = exception.getMessage();
            err.println("SchemaSynchronizer failed: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
            return 1;
        }
    }

    private static int execute(CliCommand command, String[] args) throws Exception {
        command.execute(args);
        return 0;
    }

    private static int invalidArguments(String command, PrintStream err) {
        err.println("Invalid arguments for command: " + command);
        printUsage(err);
        return 2;
    }

    private static String implementationVersion() {
        String version = SchemaSynchronizerCli.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("SchemaSynchronizer " + implementationVersion());
        stream.println();
        stream.println("Usage:");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar serialize \\");
        stream.println("    <jdbc-url> <user> <password-or--> <schema> <output-path>");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar sync \\");
        stream.println("    <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar dry-run \\");
        stream.println("    <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar validate \\");
        stream.println("    <schema-file> [schema]");
        stream.println();
        stream.println("Use '-' for <password-or--> to read SCHEMA_DB_PASSWORD.");
        stream.println("Commands: serialize, sync, dry-run, validate, help, version");
        stream.println();
        stream.println("Hand-authoring: edit schema-definition.json, run validate, then dry-run/sync.");
        stream.println("Serialize is optional bootstrap from a known-good live database.");
    }

    @FunctionalInterface
    interface CliCommand {
        void execute(String[] args) throws Exception;
    }
}
