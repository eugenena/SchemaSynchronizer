// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import com.thinkaillc.schemasynchronizer.SchemaSerializer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizer;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Self-contained command-line dispatcher for schema serialization and synchronization. */
public final class SchemaSynchronizerCli {

    private static final String MASK = "****";
    /** Longer messages are cut before redaction, bounding the backtracking of the patterns below. */
    private static final int MAX_MESSAGE_LENGTH = 16_384;
    private static final String SECRET_KEY = "(?:^|[?&;:|\\s(,\"'\\[{])[a-z0-9_.-]*"
            + "(?:password|passwd|passphrase|pass|pwd|secret|token|credential)s?\\d*";
    private static final String QUOTED_VALUE =
            "\\{[^}]*+(?:}}[^}]*+)*+}|\"[^\"]*+\"|'[^']*+(?:''[^']*+)*+'";
    /** {@code key=value}: an unquoted value runs to the next parameter separator or line end. */
    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(" + SECRET_KEY + "=)(" + QUOTED_VALUE + "|[^&;\\r\\n]*+)");
    /** {@code key = value} in prose and JSON {@code "key": value}: an unquoted value ends at punctuation. */
    private static final Pattern SPACED_SECRET_PARAMETER = Pattern.compile(
            "(?i)(" + SECRET_KEY + "(?:\\s+=\\s*|=\\s+|\"\\s*:\\s*))(" + QUOTED_VALUE + "|[^&;\\s)\"',}]*+)");
    private static final Pattern ORACLE_INLINE_CREDENTIALS = Pattern.compile(
            "(?i)(jdbc:oracle:[a-z0-9]+:(?:\"[^\"]*+\"|[^/@\\s\"]++)/)(?:\"[^\"]*+\"|\\S*)@");
    private static final Pattern URL_USERINFO_PASSWORD = Pattern.compile(
            "(//[^/:@\\s]*+:)[^\\s?#;]*@");

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
                    + (message == null || message.isBlank()
                    ? exception.getClass().getSimpleName()
                    : redactSecrets(message)));
            return 1;
        }
    }

    /**
     * Masks credentials that drivers and callers echo back in error messages: {@code key=value}
     * parameters whose key ends in password/pass/pwd/secret/token/credential, optionally with a
     * numeric suffix (URL query, SQL Server {@code ;k=v} and brace-quoted values, JSON), Oracle
     * {@code user/password@} and URL {@code //user:password@} userinfo up to the last {@code @}
     * before any {@code ?}, {@code #}, or {@code ;}.
     * Errs toward masking too much: an unquoted value is masked to the next {@code &}, {@code ;},
     * or line end.
     */
    static String redactSecrets(String message) {
        String bounded = message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) + "… (truncated)"
                : message;
        String redacted = SECRET_PARAMETER.matcher(bounded).replaceAll("$1" + MASK);
        redacted = SPACED_SECRET_PARAMETER.matcher(redacted).replaceAll("$1" + MASK);
        redacted = ORACLE_INLINE_CREDENTIALS.matcher(redacted).replaceAll("$1" + MASK + "@");
        return URL_USERINFO_PASSWORD.matcher(redacted).replaceAll("$1" + MASK + "@");
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
        stream.println("    <jdbc-url> <user> - <schema> <output-path>");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar sync \\");
        stream.println("    <jdbc-url> <user> - <schema-file> [schema] [history-table]");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar dry-run \\");
        stream.println("    <jdbc-url> <user> - <schema-file> [schema] [history-table]");
        stream.println("  java -jar schema-synchronizer-cli-<version>-standalone.jar validate \\");
        stream.println("    <schema-file> [schema]");
        stream.println();
        stream.println("Password argument must be '-'; set SCHEMA_DB_PASSWORD in the environment.");
        stream.println("Optional SCHEMA_SYNCHRONIZER_ACTOR is recorded in schema history.");
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
