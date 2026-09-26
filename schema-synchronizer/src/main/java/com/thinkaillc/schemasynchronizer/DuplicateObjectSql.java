// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Classifies JDBC errors that mean a DDL object already exists.
 *
 * <p>Used when applying multi-statement change sets against databases that already
 * contain a subset of the change (hand-authored definitions, Flyway cutovers). Data
 * uniqueness violations are never treated as duplicates to skip. Only the primary
 * {@link SQLException} is inspected — chained {@code nextException} values are ignored
 * so a hard failure is not masked by a later duplicate-object detail.
 */
final class DuplicateObjectSql {
    private DuplicateObjectSql() {
    }

    static boolean isAlreadyExists(SQLException exception) {
        if (exception == null) {
            return false;
        }
        return matches(exception);
    }

    private static boolean matches(SQLException exception) {
        String state = exception.getSQLState();
        int code = exception.getErrorCode();
        // Data uniqueness — never skip (fail closed even when the message says "already exists")
        if ("23505".equals(state) || code == 1062) {
            return false;
        }
        if (state != null) {
            // PostgreSQL: duplicate_object, duplicate_table, duplicate_column
            if ("42710".equals(state) || "42P07".equals(state) || "42701".equals(state)) {
                return true;
            }
            // SQL/MySQL family sometimes surface 42S01 (base table/view already exists)
            if ("42S01".equals(state) || "42S21".equals(state)) {
                return true;
            }
        }
        // MySQL / MariaDB vendor codes for existing DDL objects (not 1062 duplicate row)
        if (code == 1050 || code == 1060 || code == 1061 || code == 1068 || code == 1826) {
            return true;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("duplicate entry") || lower.contains("unique constraint")
                || lower.contains("unique_violation") || lower.contains("duplicate key")) {
            return false;
        }
        return lower.contains("already exists");
    }
}
