// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/** Database family of a serialized schema and its synchronization target. */
public enum DatabaseDialect {
    POSTGRESQL("postgresql"),
    MARIADB("mariadb"),
    MYSQL("mysql"),
    SQLSERVER("sqlserver"),
    ORACLE("oracle");

    private final String id;

    DatabaseDialect(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isMySqlFamily() {
        return this == MARIADB || this == MYSQL;
    }

    /** PostgreSQL and SQL Server resolve unqualified names within a schema namespace. */
    public boolean usesSchemaNamespace() {
        return this == POSTGRESQL || this == SQLSERVER;
    }

    /** MySQL/MariaDB treat the database/catalog as the managed namespace. */
    public boolean usesCatalogNamespace() {
        return isMySqlFamily();
    }

    /** Oracle maps the managed namespace to a user/schema. */
    public boolean usesUserSchemaNamespace() {
        return this == ORACLE;
    }

    /**
     * Engines where DDL may commit outside the JDBC transaction (retry requires
     * verificationSql and single-statement change sets when unverified).
     */
    public boolean ddlMayCommitImplicitly() {
        return isMySqlFamily() || this == ORACLE;
    }

    public boolean supportsTransactionalDryRun() {
        return this == POSTGRESQL || this == SQLSERVER;
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
        if (normalized.contains("microsoft sql server") || normalized.contains("sql server")) {
            return SQLSERVER;
        }
        if (normalized.contains("oracle")) {
            return ORACLE;
        }
        throw new IllegalStateException("Unsupported database: " + product);
    }

    public static DatabaseDialect parse(String value) {
        if (value == null || value.isBlank()) {
            return POSTGRESQL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("mssql".equals(normalized) || "sqlserver".equals(normalized) || "sql-server".equals(normalized)) {
            return SQLSERVER;
        }
        for (DatabaseDialect dialect : values()) {
            if (dialect.id.equalsIgnoreCase(normalized)) {
                return dialect;
            }
        }
        throw new IllegalArgumentException("Unsupported schema dialect: " + value);
    }

    /**
     * JDBC catalog argument for {@link DatabaseMetaData} lookups.
     * PostgreSQL: {@code null}. MySQL family: connected catalog. SQL Server: connected
     * database. Oracle: {@code null} (schema pattern selects the user).
     */
    public String metadataCatalog(Connection connection, String configuredNamespace) throws SQLException {
        if (usesCatalogNamespace()) {
            String catalog = connection.getCatalog();
            return catalog != null ? catalog : configuredNamespace;
        }
        if (this == SQLSERVER) {
            return connection.getCatalog();
        }
        return null;
    }

    /** JDBC schema-pattern argument for metadata lookups. */
    public String metadataSchemaPattern(String configuredNamespace) {
        if (usesSchemaNamespace() || usesUserSchemaNamespace()) {
            return metadataObjectName(configuredNamespace);
        }
        return null;
    }

    /** Engines that accept {@code CREATE INDEX IF NOT EXISTS}. */
    public boolean supportsCreateIndexIfNotExists() {
        return this == POSTGRESQL || this == MARIADB;
    }

    /** Engines that accept {@code CREATE TABLE IF NOT EXISTS}. */
    public boolean supportsCreateTableIfNotExists() {
        return this == POSTGRESQL || isMySqlFamily();
    }

    /** Engines that accept {@code ADD COLUMN IF NOT EXISTS}. */
    public boolean supportsAddColumnIfNotExists() {
        return this == POSTGRESQL || this == MARIADB;
    }

    /**
     * Identifier form expected by JDBC {@link DatabaseMetaData} lookups.
     * Oracle folds unquoted identifiers to uppercase.
     */
    public String metadataObjectName(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (this == ORACLE) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        return identifier;
    }


    public String qualifyHistoryTable(String configuredNamespace, String historyTable) {
        if (usesCatalogNamespace()) {
            return SqlIdentifiers.requireIdentifier(historyTable, "history table");
        }
        return SqlIdentifiers.qualified(configuredNamespace, historyTable);
    }
}
