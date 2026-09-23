// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/** Database family of a serialized schema and its synchronization target. */
public enum DatabaseDialect {
    POSTGRESQL("postgresql"),
    MARIADB("mariadb"),
    MYSQL("mysql");

    private final String id;

    DatabaseDialect(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static DatabaseDialect detect(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName();
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (normalized.contains("mariadb")) {
            return MARIADB;
        }
        if (normalized.contains("mysql")) {
            return MYSQL;
        }
        if (normalized.contains("postgresql")) {
            return POSTGRESQL;
        }
        throw new IllegalStateException("Unsupported database: " + product);
    }

    public static DatabaseDialect parse(String value) {
        if (value == null || value.isBlank()) {
            return POSTGRESQL;
        }
        for (DatabaseDialect dialect : values()) {
            if (dialect.id.equalsIgnoreCase(value)) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("Unsupported schema dialect: " + value);
    }
}
