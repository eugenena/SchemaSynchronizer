// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SchemaSynchronizerCliTest {

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "help"})
    void helpReturnsSuccessWithoutExecutingACommand(String flag) {
        Streams streams = new Streams();

        int status = runRejectingAll(streams, flag);

        assertThat(status).isZero();
        assertThat(streams.stdout()).contains("Usage:", "serialize", "sync", "dry-run", "validate",
                "SCHEMA_DB_PASSWORD");
        assertThat(streams.stderr()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--version", "-V", "version"})
    void versionReturnsSuccessWithoutExecutingACommand(String flag) {
        Streams streams = new Streams();

        int status = runRejectingAll(streams, flag);

        assertThat(status).isZero();
        assertThat(streams.stdout()).startsWith("SchemaSynchronizer ").doesNotContain("Usage:");
        assertThat(streams.stderr()).isEmpty();
    }

    @Test
    void zeroArgumentsReturnUsageError() {
        Streams streams = new Streams();

        int status = runRejectingAll(streams);

        assertThat(status).isEqualTo(2);
        assertThat(streams.stderr()).contains("A command is required.", "Usage:");
        assertThat(streams.stdout()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "SYNC", "--sync", "dryrun"})
    void unknownCommandReturnsUsageError(String command) {
        Streams streams = new Streams();

        int status = runRejectingAll(streams, command);

        assertThat(status).isEqualTo(2);
        assertThat(streams.stderr()).contains("Unknown command: " + command, "Usage:");
    }

    @Test
    void serializeForwardsOnlyCommandArguments() {
        Recorder recorder = new Recorder();

        int status = recorder.run("serialize", "jdbc:postgresql://localhost/db", "user", "-", "public",
                "schema.json");

        assertThat(status).isZero();
        assertThat(recorder.calls).containsExactly("serialize");
        assertThat(recorder.lastArgs.get()).containsExactly(
                "jdbc:postgresql://localhost/db", "user", "-", "public", "schema.json");
    }

    @Test
    void syncDispatchesOnlyToSynchronizer() {
        Recorder recorder = new Recorder();

        int status = recorder.run("sync", "jdbc:mysql://localhost/db", "user", "-", "schema.json", "db");

        assertThat(status).isZero();
        assertThat(recorder.calls).containsExactly("sync");
        assertThat(recorder.lastArgs.get()).containsExactly(
                "jdbc:mysql://localhost/db", "user", "-", "schema.json", "db");
    }

    @Test
    void dryRunDispatchesOnlyToDryRunNeverToSync() {
        Recorder recorder = new Recorder();

        int status = recorder.run("dry-run", "jdbc:postgresql://localhost/db", "user", "-", "schema.json",
                "public");

        assertThat(status).isZero();
        assertThat(recorder.calls).containsExactly("dry-run");
        assertThat(recorder.lastArgs.get()).containsExactly(
                "jdbc:postgresql://localhost/db", "user", "-", "schema.json", "public");
    }

    @Test
    void validateDispatchesOnlyToValidate() {
        Recorder recorder = new Recorder();

        int status = recorder.run("validate", "schema.json", "public");

        assertThat(status).isZero();
        assertThat(recorder.calls).containsExactly("validate");
        assertThat(recorder.lastArgs.get()).containsExactly("schema.json", "public");
    }

    @Test
    void invalidCommandArgumentsReturnUsageErrorWithoutExecuting() {
        Streams streams = new Streams();

        int status = runRejectingAll(streams, "sync", "jdbc:postgresql://localhost/db");

        assertThat(status).isEqualTo(2);
        assertThat(streams.stderr()).contains("Invalid arguments for command: sync", "Usage:");
    }

    @Test
    void commandFailureReturnsOperationalErrorWithoutStackTrace() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(
                new String[]{"sync", "jdbc:postgresql://localhost/db", "user", "-", "schema.json"},
                streams.out(), streams.err(),
                args -> { }, args -> { throw new IllegalStateException("database unavailable"); },
                args -> { }, args -> { });

        assertThat(status).isEqualTo(1);
        assertThat(streams.stderr()).contains("SchemaSynchronizer failed: database unavailable")
                .doesNotContain("IllegalStateException");
    }

    @Test
    void commandFailureWithoutMessagePrintsExceptionType() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(
                new String[]{"validate", "schema.json"}, streams.out(), streams.err(),
                args -> { }, args -> { }, args -> { }, args -> { throw new IllegalStateException(); });

        assertThat(status).isEqualTo(1);
        assertThat(streams.stderr()).contains("SchemaSynchronizer failed: IllegalStateException");
    }

    @Test
    void commandFailureMessageIsRedactedBeforePrinting() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(
                new String[]{"sync", "jdbc:postgresql://localhost/db?password=hunter2", "user", "-", "s.json"},
                streams.out(), streams.err(),
                args -> { }, args -> {
                    throw new IllegalStateException("No suitable driver found for " + args[0]);
                }, args -> { }, args -> { });

        assertThat(status).isEqualTo(1);
        assertThat(streams.stderr())
                .contains("No suitable driver found for jdbc:postgresql://localhost/db?password=****")
                .doesNotContain("hunter2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db:5432/app?user=app&password=hunter2&ssl=true",
            "jdbc:postgresql://db:5432/app?PASSWORD=hunter2",
            "jdbc:sqlserver://db:1433;databaseName=app;password=hunter2;encrypt=true",
            "jdbc:sqlserver://db:1433;Password={hun;ter2};encrypt=true",
            "jdbc:mysql://db/app?pwd=hunter2",
            "jdbc:mysql://db/app?trustCertificateKeyStorePassword=hunter2",
            "jdbc:mariadb://db/app?user=a&password=hunter2",
            "jdbc:oracle:thin:scott/hunter2@//db:1521/XEPDB1",
            "jdbc:mysql://scott:hunter2@db:3306/app",
            "connect failed: password = 'hunter2'",
            "Password=hunter2",
            "client_secret=hunter2 rejected",
            "jdbc:sqlserver://db:1433;password={hun}}ter2};encrypt=true",
            "jdbc:sqlserver://db:1433;password=hun ter2;encrypt=true",
            "jdbc:mysql://db/app?password1=hunter2&password2=hunter2",
            "jdbc:sqlserver://db:1433;accessToken=hunter2",
            "jdbc:mysql://db/app?password=hun,ter2",
            "jdbc:mysql://db/app?password=hun)ter2",
            "jdbc:mysql://scott:hun/ter2@db/app",
            "jdbc:mysql://scott:hun@ter2@db/app",
            "jdbc:oracle:thin:scott/\"hun@ter2\"@//db:1521/XEPDB1",
            "jdbc:oracle:thin:scott/hun@ter2@//db:1521/XEPDB1",
            "[password=hunter2]",
            "{password=hunter2}",
            "password=hunter2",
            "No suitable driver found for jdbc:x://db/app?user=a&password=hunter2",
            "DB_PASS=hunter2",
            "{\"password\":\"hunter2\"}",
            "{\"password\": hunter2}",
            "user=a|password=hunter2",
            "jdbc:mysql://:hunter2@db/app",
            "jdbc:oracle:thin:\"SCOTT\"/hunter2@//db:1521/XEPDB1",
            "password='hun''ter2'",
    })
    void redactionMasksEverySecretSpelling(String message) {
        String redacted = SchemaSynchronizerCli.redactSecrets(message);

        assertThat(redacted).doesNotContain("ter2").contains("****");
    }

    @Test
    void redactionOfHugeAdversarialMessagesIsBoundedAndNeverThrows() {
        String braced = ";password={" + "a".repeat(1_000_000) + "}";
        String unterminated = "password={" + "b".repeat(200_000);
        String userinfo = "//a:".repeat(50_000);
        String oracle = "jdbc:oracle:thin:a/".repeat(10_000);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String message : List.of(braced, unterminated, userinfo, oracle)) {
                String redacted = SchemaSynchronizerCli.redactSecrets(message);
                assertThat(redacted.length()).isLessThan(20_000);
            }
        });
        assertThat(SchemaSynchronizerCli.redactSecrets(braced)).startsWith(";password=****");
    }

    @Test
    void redactionMasksWholeEscapedOrQuotedValues() {
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:sqlserver://db:1433;password={hun}}ter2};encrypt=true"))
                .isEqualTo("jdbc:sqlserver://db:1433;password=****;encrypt=true");
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:oracle:thin:scott/\"hun@ter2\"@//db:1521/XEPDB1"))
                .isEqualTo("jdbc:oracle:thin:scott/****@//db:1521/XEPDB1");
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:mysql://scott:hun@ter2@db/app"))
                .isEqualTo("jdbc:mysql://scott:****@db/app");
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:mysql://db/app?password1=a&user=b&password2=c"))
                .isEqualTo("jdbc:mysql://db/app?password1=****&user=b&password2=****");
    }

    @Test
    void redactionKeepsNonSecretParametersAndProse() {
        assertThat(SchemaSynchronizerCli.redactSecrets(
                "jdbc:postgresql://db:5432/app?user=app&password=hunter2&ssl=true"))
                .isEqualTo("jdbc:postgresql://db:5432/app?user=app&password=****&ssl=true");
        assertThat(SchemaSynchronizerCli.redactSecrets(
                "jdbc:sqlserver://db:1433;databaseName=app;password=hunter2;encrypt=true"))
                .isEqualTo("jdbc:sqlserver://db:1433;databaseName=app;password=****;encrypt=true");
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:oracle:thin:scott/hunter2@//db:1521/XEPDB1"))
                .isEqualTo("jdbc:oracle:thin:scott/****@//db:1521/XEPDB1");
        assertThat(SchemaSynchronizerCli.redactSecrets("jdbc:mysql://scott:hunter2@db:3306/app"))
                .isEqualTo("jdbc:mysql://scott:****@db:3306/app");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FATAL: password authentication failed for user \"app\"",
            "Pass the database password via SCHEMA_DB_PASSWORD and use '-' as the password argument",
            "jdbc:postgresql://db:5432/app?user=app&ssl=true",
            "jdbc:oracle:thin:@//db:1521/XEPDB1",
            "jdbc:oracle:thin:@db:1521/XEPDB1",
            "jdbc:sqlserver://db:1433;databaseName=app;encrypt=true",
            "Could not acquire schema synchronization lock within 30s",
            "cwd=/tmp/app",
            "secretary=bob",
            "pwd_hint=blue",
            "tokenizer=sql",
            "The SELECT permission was denied on the object 'Payroll', database 'app', schema 'dbo'.",
            "Login failed for user 'app_sync'. ClientConnectionId:e69dd54b-578a-435d-83c9-3f4032f5a9e1",
            "Access denied for user 'app'@'10.0.0.1' (using password: YES)",
            "ORA-01017: invalid username/password; logon denied",
            "jdbc:postgresql://db.example.com:5432/app?user=a@b.com",
            "jdbc:sqlserver://srv.database.windows.net:1433;user=app@srv;encrypt=true",
    })
    void redactionLeavesMessagesWithoutSecretsUnchanged(String message) {
        assertThat(SchemaSynchronizerCli.redactSecrets(message)).isEqualTo(message);
    }

    private static int runRejectingAll(Streams streams, String... args) {
        return SchemaSynchronizerCli.run(args, streams.out(), streams.err(),
                ignored -> { throw new AssertionError("serializer called"); },
                ignored -> { throw new AssertionError("synchronizer called"); },
                ignored -> { throw new AssertionError("dry-run called"); },
                ignored -> { throw new AssertionError("validate called"); });
    }

    private static final class Recorder {
        private final List<String> calls = new ArrayList<>();
        private final AtomicReference<String[]> lastArgs = new AtomicReference<>();
        private final Streams streams = new Streams();

        int run(String... args) {
            return SchemaSynchronizerCli.run(args, streams.out(), streams.err(),
                    record("serialize"), record("sync"), record("dry-run"), record("validate"));
        }

        private SchemaSynchronizerCli.CliCommand record(String name) {
            return args -> {
                calls.add(name);
                lastArgs.set(args);
            };
        }
    }

    private static final class Streams {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        PrintStream out() {
            return new PrintStream(stdout, true, StandardCharsets.UTF_8);
        }

        PrintStream err() {
            return new PrintStream(stderr, true, StandardCharsets.UTF_8);
        }

        String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }
    }
}
