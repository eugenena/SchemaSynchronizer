// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public exception boundary: every internal failure maps onto one documented type. */
class SchemaExceptionsTest {

    @Test
    void translateMapsEachInternalFailureKind() {
        SQLException sql = new SQLException("deadlock", "40P01", 1205);
        assertThat(SchemaExceptions.translate(sql))
                .isInstanceOf(SchemaDatabaseException.class)
                .hasMessage("deadlock")
                .hasCause(sql);
        SchemaDatabaseException database = (SchemaDatabaseException) SchemaExceptions.translate(sql);
        assertThat(database.getSQLState()).isEqualTo("40P01");
        assertThat(database.getErrorCode()).isEqualTo(1205);

        assertThat(SchemaExceptions.translate(new IllegalArgumentException("bad name")))
                .isInstanceOf(SchemaDefinitionException.class).hasMessage("bad name");
        assertThat(SchemaExceptions.translate(new IllegalStateException("wrong schema")))
                .isInstanceOf(SchemaDefinitionException.class).hasMessage("wrong schema");
        assertThat(SchemaExceptions.translate(new IOException("disk")))
                .isExactlyInstanceOf(SchemaSynchronizationException.class).hasMessage("I/O failure: disk");
        assertThat(SchemaExceptions.translate(new NullPointerException()))
                .isExactlyInstanceOf(SchemaSynchronizationException.class).hasMessage("NullPointerException");

        SchemaLockUnavailableException lock = new SchemaLockUnavailableException("busy");
        assertThat(SchemaExceptions.translate(lock)).isSameAs(lock);
    }

    @Test
    void translateRestoresTheInterruptFlag() {
        Thread.interrupted();
        try {
            assertThat(SchemaExceptions.translate(new InterruptedException("stop"))).hasMessage("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void translateLiftsSuppressedCleanupFailuresOntoTheWrapper() {
        SQLException primary = new SQLException("commit failed");
        SQLException release = new SQLException("release failed");
        primary.addSuppressed(release);

        assertThat(SchemaExceptions.translate(primary).getSuppressed()).containsExactly(release);
    }

    @Test
    void suppressIgnoresSelfNullAndRepeats() {
        SQLException primary = new SQLException("primary");
        SQLException other = new SQLException("other");

        SchemaExceptions.suppress(primary, primary);
        SchemaExceptions.suppress(primary, null);
        SchemaExceptions.suppress(primary, other);
        SchemaExceptions.suppress(primary, other);

        assertThat(primary.getSuppressed()).containsExactly(other);
    }

    @Test
    void releaseEachSurvivesTheSameFailureInstanceFromEveryPart() {
        SQLException shared = new SQLException("connection closed");
        List<String> attempted = new ArrayList<>();

        assertThatThrownBy(() -> DialectSupport.releaseEach("a:b:c", part -> {
            attempted.add(part);
            throw shared;
        })).isSameAs(shared);
        assertThat(attempted).containsExactly("a", "b", "c");
        assertThat(shared.getSuppressed()).isEmpty();
    }

    @Test
    void releaseEachKeepsTheFirstFailureAndSuppressesDistinctLaterOnes() {
        SQLException first = new SQLException("a failed");
        IllegalStateException second = new IllegalStateException("b failed");

        assertThatThrownBy(() -> DialectSupport.releaseEach("a:b:c", part -> {
            switch (part) {
                case "a" -> throw first;
                case "b" -> throw second;
                default -> { }
            }
        })).isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second);
    }

    @Test
    void acquireAllRollbackSurvivesTheSameFailureOnAcquireAndRelease() {
        SQLException shared = new SQLException("connection closed");

        assertThatThrownBy(() -> DialectSupport.acquireAll(List.of("a", "b"),
                part -> {
                    if (part.equals("b")) {
                        throw shared;
                    }
                    return true;
                },
                part -> {
                    throw shared;
                })).isSameAs(shared);
        assertThat(shared.getSuppressed()).isEmpty();
    }
}
