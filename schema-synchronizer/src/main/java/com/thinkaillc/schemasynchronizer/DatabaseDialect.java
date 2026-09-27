// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Database family of a serialized schema and its synchronization target. A definition names its
 * dialect in the {@code dialect} field, and it is applied only to a database of that dialect.
 */
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

    /** The lower-case name used in the definition's {@code dialect} field. */
    public String id() {
        return id;
    }

    boolean isMySqlFamily() {
        return this == MARIADB || this == MYSQL;
    }

    /** PostgreSQL and SQL Server resolve unqualified names within a schema namespace. */
    boolean usesSchemaNamespace() {
        return this == POSTGRESQL || this == SQLSERVER;
    }

    /** MySQL/MariaDB treat the database/catalog as the managed namespace. */
    boolean usesCatalogNamespace() {
        return isMySqlFamily();
    }

    /** Oracle maps the managed namespace to a user/schema. */
    boolean usesUserSchemaNamespace() {
        return this == ORACLE;
    }

    /**
     * Engines where DDL may commit outside the JDBC transaction (retry requires
     * verificationSql and single-statement change sets when unverified).
     */
    boolean ddlMayCommitImplicitly() {
        return isMySqlFamily() || this == ORACLE;
    }

    boolean supportsTransactionalDryRun() {
        return this == POSTGRESQL || this == SQLSERVER;
    }

    /** Unquoted identifier length limit for this dialect. */
    int maxIdentifierLength() {
        if (this == POSTGRESQL) {
            return SqlIdentifiers.DEFAULT_MAX_LENGTH;
        }
        return isMySqlFamily() ? SqlIdentifiers.MYSQL_MAX_LENGTH : SqlIdentifiers.EXTENDED_MAX_LENGTH;
    }

    /**
     * The dialect of a live database, from its product name (and version, which tells MariaDB
     * apart when Connector/J reports it as MySQL).
     *
     * @throws SchemaSynchronizationException when the product is not one of the supported databases
     * @throws SQLException when the driver cannot report the product
     */
    public static DatabaseDialect detect(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName();
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (normalized.contains("mariadb")) {
            return MARIADB;
        }
        if (normalized.contains("mysql")) {
            // MySQL Connector/J reports "MySQL" for MariaDB servers; the version is e.g. "10.11.9-MariaDB".
            String version = metadata.getDatabaseProductVersion();
            return version != null && version.toLowerCase(Locale.ROOT).contains("mariadb") ? MARIADB : MYSQL;
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
        throw new SchemaSynchronizationException("Unsupported database: " + product);
    }

    /**
     * The dialect named by a definition's {@code dialect} field: an id ({@code postgresql},
     * {@code mysql}, {@code mariadb}, {@code sqlserver}, {@code oracle}) or {@code mssql} /
     * {@code sql-server}, in any case. Null or blank means PostgreSQL, the 1.x default.
     *
     * @throws SchemaDefinitionException for any other value
     */
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
        throw new SchemaDefinitionException("Unsupported schema dialect: " + value);
    }

    /**
     * JDBC catalog argument for {@link DatabaseMetaData} lookups.
     * PostgreSQL: {@code null}. MySQL family: the configured database, which the caller has
     * verified equals {@code DATABASE()} (Connector/J caches the URL database across {@code USE}).
     * SQL Server: connected database. Oracle: {@code null} (schema pattern selects the user).
     */
    String metadataCatalog(Connection connection, String configuredNamespace) throws SQLException {
        if (usesCatalogNamespace()) {
            return configuredNamespace;
        }
        if (this == SQLSERVER) {
            return connection.getCatalog();
        }
        return null;
    }

    /**
     * JDBC schema argument for metadata lookups; filter the rows through
     * {@link #isRequestedObject}, since drivers treat it as a pattern. The MySQL family passes the database
     * too: Connector/J {@code databaseTerm=SCHEMA} and MariaDB {@code useCatalogTerm=Schema}
     * ignore the catalog argument and would otherwise list every database.
     */
    String metadataSchemaPattern(String configuredNamespace) {
        if (usesCatalogNamespace()) {
            return configuredNamespace;
        }
        if (configuredNamespace != null && (usesSchemaNamespace() || usesUserSchemaNamespace())) {
            return SqlIdentifiers.storedNamespace(this, configuredNamespace);
        }
        return null;
    }

    /**
     * Whether a {@link DatabaseMetaData} row ({@code getTables}, {@code getColumns},
     * {@code getPrimaryKeys}) names the requested schema and table rather than a sibling. Drivers
     * match these arguments with LIKE, so {@code app_db} also returns {@code app1db} (Oracle does so
     * for the {@code getPrimaryKeys} schema too). Escaping is not an option: under
     * {@code NO_BACKSLASH_ESCAPES} Connector/J finds nothing for an escaped name. Arguments are
     * passed unescaped and every row is filtered; a {@code null} request matches any row. The
     * schema is {@code TABLE_SCHEM}, or {@code TABLE_CAT} when a MySQL-family driver reports none.
     */
    static boolean isRequestedObject(ResultSet row, String schema, String table) throws SQLException {
        if (schema != null) {
            String reported = row.getString("TABLE_SCHEM");
            if (!matchesLiterally(schema, reported != null ? reported : row.getString("TABLE_CAT"))) {
                return false;
            }
        }
        return table == null || matchesLiterally(table, row.getString("TABLE_NAME"));
    }

    /**
     * Whether {@code reported}, returned by a LIKE match against {@code requested}, is not a
     * sibling that matched a {@code _} or {@code %} wildcard. Other characters are left to the
     * server's comparison, which decides case sensitivity.
     */
    static boolean matchesLiterally(String requested, String reported) {
        if (reported == null || reported.length() != requested.length()) {
            return false;
        }
        for (int i = 0; i < requested.length(); i++) {
            char c = requested.charAt(i);
            if ((c == '_' || c == '%') && reported.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    /**
     * Statement text as sent over JDBC. Oracle rejects a trailing {@code ;} on SQL statements
     * (ORA-00933/00911) but requires it at the end of PL/SQL blocks and stored-code DDL.
     */
    String executableSql(String sql) {
        if (this != ORACLE || sql == null) {
            return sql;
        }
        String masked;
        try {
            masked = SqlLexer.mask(sql, SqlLexer.Mode.ORACLE, true, true);
        } catch (IllegalArgumentException unlexable) {
            return sql;
        }
        // Offsets match: comments and literals are blanked, so a trailing comment is whitespace here.
        String code = masked.stripTrailing();
        if (!code.endsWith(";") || ORACLE_PLSQL.matcher(masked).lookingAt()) {
            return sql;
        }
        return sql.substring(0, code.length() - 1).strip();
    }

    private static final java.util.regex.Pattern ORACLE_PLSQL = java.util.regex.Pattern.compile(
            "(?is)\\s*(?:BEGIN\\b|DECLARE\\b|CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
                    + "(?:(?:NON)?EDITIONABLE\\s+)?(?:FUNCTION|PROCEDURE|TRIGGER|PACKAGE|TYPE)\\b)");

    /** Engines that accept {@code CREATE INDEX IF NOT EXISTS}. */
    boolean supportsCreateIndexIfNotExists() {
        return this == POSTGRESQL || this == MARIADB;
    }

    /** Engines that accept {@code CREATE TABLE IF NOT EXISTS}. */
    boolean supportsCreateTableIfNotExists() {
        return this == POSTGRESQL || isMySqlFamily();
    }

    /** Engines that accept {@code ADD COLUMN IF NOT EXISTS}. */
    boolean supportsAddColumnIfNotExists() {
        return this == POSTGRESQL || this == MARIADB;
    }

    /**
     * Identifier form expected by JDBC {@link DatabaseMetaData} lookups.
     * Oracle folds unquoted identifiers to uppercase.
     */
    String metadataObjectName(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (this == ORACLE) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        return identifier;
    }


    /**
     * The history table as emitted SQL, quoted: schema-qualified except on the MySQL family, whose
     * namespace is the verified current database.
     */
    String qualifyHistoryTable(String configuredNamespace, String historyTable) {
        int max = maxIdentifierLength();
        String table = SqlIdentifiers.quote(this,
                SqlIdentifiers.requireIdentifier(historyTable, "history table", max));
        if (usesCatalogNamespace()) {
            return table;
        }
        return SqlIdentifiers.quoteNamespace(this,
                SqlIdentifiers.requireIdentifierPreservingCase(configuredNamespace, "schema", max)) + "." + table;
    }
}
