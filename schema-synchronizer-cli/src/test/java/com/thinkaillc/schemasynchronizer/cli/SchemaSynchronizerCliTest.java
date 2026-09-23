// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSynchronizerCliTest {

    @Test
    void helpReturnsSuccessWithoutExecutingACommand() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(new String[]{"--help"}, streams.out(), streams.err(),
                args -> { throw new AssertionError("serializer called"); },
                args -> { throw new AssertionError("synchronizer called"); });

        assertThat(status).isZero();
        assertThat(streams.stdout()).contains("Usage:", "serialize", "sync", "SCHEMA_DB_PASSWORD");
        assertThat(streams.stderr()).isEmpty();
    }

    @Test
    void serializeForwardsOnlyCommandArguments() {
        Streams streams = new Streams();
        AtomicReference<String[]> received = new AtomicReference<>();

        int status = SchemaSynchronizerCli.run(
                new String[]{"serialize", "jdbc:postgresql://localhost/db", "user", "-", "public", "schema.json"},
                streams.out(), streams.err(), received::set,
                args -> { throw new AssertionError("synchronizer called"); });

        assertThat(status).isZero();
        assertThat(received.get()).containsExactly(
                "jdbc:postgresql://localhost/db", "user", "-", "public", "schema.json");
    }

    @Test
    void syncForwardsOnlyCommandArguments() {
        Streams streams = new Streams();
        AtomicReference<String[]> received = new AtomicReference<>();

        int status = SchemaSynchronizerCli.run(
                new String[]{"sync", "jdbc:mysql://localhost/db", "user", "-", "schema.json", "db"},
                streams.out(), streams.err(),
                args -> { throw new AssertionError("serializer called"); }, received::set);

        assertThat(status).isZero();
        assertThat(received.get()).containsExactly(
                "jdbc:mysql://localhost/db", "user", "-", "schema.json", "db");
    }

    @Test
    void invalidCommandReturnsUsageError() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(new String[]{"unknown"}, streams.out(), streams.err(),
                args -> { }, args -> { });

        assertThat(status).isEqualTo(2);
        assertThat(streams.stderr()).contains("Unknown command: unknown", "Usage:");
    }

    @Test
    void invalidCommandArgumentsReturnUsageErrorWithoutExecuting() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(new String[]{"sync", "jdbc:postgresql://localhost/db"},
                streams.out(), streams.err(),
                args -> { throw new AssertionError("serializer called"); },
                args -> { throw new AssertionError("synchronizer called"); });

        assertThat(status).isEqualTo(2);
        assertThat(streams.stderr()).contains("Invalid arguments for command: sync", "Usage:");
    }

    @Test
    void commandFailureReturnsOperationalErrorWithoutStackTrace() {
        Streams streams = new Streams();

        int status = SchemaSynchronizerCli.run(
                new String[]{"sync", "jdbc:postgresql://localhost/db", "user", "-", "schema.json"},
                streams.out(), streams.err(),
                args -> { }, args -> { throw new IllegalStateException("database unavailable"); });

        assertThat(status).isEqualTo(1);
        assertThat(streams.stderr()).contains("SchemaSynchronizer failed: database unavailable")
                .doesNotContain("IllegalStateException");
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
