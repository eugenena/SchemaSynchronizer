// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

final class SqlIdentifiers {
    /** PostgreSQL / portable default. */
    static final int DEFAULT_MAX_LENGTH = 63;
    /** SQL Server and Oracle 12.2+ unquoted identifier limit. */
    static final int EXTENDED_MAX_LENGTH = 128;

    private SqlIdentifiers() {}

    static String requireIdentifier(String value, String label) {
        return requireIdentifierPreservingCase(value, label, DEFAULT_MAX_LENGTH).toLowerCase();
    }

    static String requireIdentifier(String value, String label, int maxLength) {
        return requireIdentifierPreservingCase(value, label, maxLength).toLowerCase();
    }

    static String requireIdentifierPreservingCase(String value, String label) {
        return requireIdentifierPreservingCase(value, label, DEFAULT_MAX_LENGTH);
    }

    static String requireIdentifierPreservingCase(String value, String label, int maxLength) {
        if (value == null || value.length() > maxLength || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return value;
    }

    static String qualified(String schema, String name) {
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(name, "identifier");
    }

    static String qualified(String schema, String name, int maxLength) {
        return requireIdentifier(schema, "schema", maxLength) + "."
                + requireIdentifier(name, "identifier", maxLength);
    }
}
