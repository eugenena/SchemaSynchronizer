// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;

/**
 * Maps internal failures onto the public exception hierarchy at API boundaries. Internal code
 * signals a rejected definition, policy, or environment precondition with
 * {@link IllegalArgumentException} / {@link IllegalStateException}, and a database failure with
 * {@link SQLException}; callers only ever see {@link SchemaSynchronizationException} subclasses.
 */
final class SchemaExceptions {

    private SchemaExceptions() {}

    /**
     * The public exception for {@code failure}. Failures suppressed on it (rollback, lock release)
     * are also suppressed on the returned wrapper, so callers see them without unwrapping.
     */
    static SchemaSynchronizationException translate(Throwable failure) {
        if (failure instanceof SchemaSynchronizationException known) {
            return known;
        }
        SchemaSynchronizationException wrapper = wrap(failure);
        for (Throwable suppressed : failure.getSuppressed()) {
            suppress(wrapper, suppressed);
        }
        return wrapper;
    }

    private static SchemaSynchronizationException wrap(Throwable failure) {
        if (failure instanceof SQLException sql) {
            return new SchemaDatabaseException(messageOf(sql), sql);
        }
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return new SchemaDefinitionException(messageOf(failure), failure);
        }
        if (failure instanceof IOException || failure instanceof UncheckedIOException) {
            return new SchemaSynchronizationException("I/O failure: " + messageOf(failure), failure);
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new SchemaSynchronizationException("interrupted", failure);
        }
        return new SchemaSynchronizationException(messageOf(failure), failure);
    }

    /**
     * {@link Throwable#addSuppressed} that tolerates the same instance failing twice (a driver
     * rethrowing a cached exception, or two lock parts sharing one failure), which would otherwise
     * throw {@link IllegalArgumentException} and replace the real failure.
     */
    static void suppress(Throwable primary, Throwable secondary) {
        if (secondary == null || secondary == primary || reaches(secondary, primary)) {
            return;
        }
        for (Throwable already : primary.getSuppressed()) {
            if (already == secondary) {
                return;
            }
        }
        primary.addSuppressed(secondary);
    }

    /** Whether {@code target} is {@code from} or sits in its cause/suppressed graph (which would form a cycle). */
    private static boolean reaches(Throwable from, Throwable target) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            Throwable next = pending.pop();
            if (next == target) {
                return true;
            }
            if (!seen.add(next)) {
                continue;
            }
            if (next.getCause() != null) {
                pending.push(next.getCause());
            }
            for (Throwable suppressed : next.getSuppressed()) {
                pending.push(suppressed);
            }
        }
        return false;
    }

    /** A reading failure of a definition file is a definition problem, not a transient I/O error. */
    static SchemaDefinitionException definitionUnreadable(String what, IOException failure) {
        return new SchemaDefinitionException("cannot read " + what + ": " + messageOf(failure), failure);
    }

    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
