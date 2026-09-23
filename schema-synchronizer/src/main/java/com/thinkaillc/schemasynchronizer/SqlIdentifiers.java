// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

final class SqlIdentifiers {
    private SqlIdentifiers() {}

    static String requireIdentifier(String value, String label) {
        return requireIdentifierPreservingCase(value, label).toLowerCase();
    }

    static String requireIdentifierPreservingCase(String value, String label) {
        if (value == null || value.length() > 63 || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return value;
    }

    static String qualified(String schema, String name) {
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(name, "identifier");
    }
}
