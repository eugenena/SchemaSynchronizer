// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects change-set SQL that targets a schema/catalog other than the configured
 * namespace. Declarative {@code createSql} already enforces this; change sets did not.
 *
 * <p>Any {@code schema.object} reference (bare or quoted/bracket/backtick) outside
 * string literals must use the configured namespace or a system catalog. Prefer
 * unqualified column names in {@code SET} clauses ({@code SET note = …} rather than
 * {@code SET items.note = …}) so table-qualified columns are not mistaken for
 * cross-schema targets.
 *
 * <p>This is a guardrail against accidental cross-namespace change sets, not a security
 * boundary against hostile SQL authors.
 */
final class ChangeSetSchemaScope {
    private static final String IDENT_CHAR = "\\p{L}\\p{N}_$#@";

    /** Quoted forms, or a bare word that is not purely numeric and starts at a token boundary. */
    private static final String IDENT =
            "(?:\"([^\"]+)\"|\\[([^\\]]+)\\]|`([^`]+)`"
                    + "|(?<![" + IDENT_CHAR + "])((?=\\p{N}*[\\p{L}_$#@])[" + IDENT_CHAR + "]+))";

    /** Any schema.object form that survives string/comment masking (whitespace around {@code .} allowed). */
    private static final Pattern QUALIFIED = Pattern.compile(IDENT + "\\s*\\.\\s*" + IDENT);

    /** SQL Server {@code db..object} (default schema in another database). */
    private static final Pattern DOUBLE_DOT = Pattern.compile(IDENT + "\\s*\\.\\s*\\.");

    private static final Pattern IN_SCHEMA = Pattern.compile(
            "(?i)\\bIN\\s+SCHEMA\\s+" + IDENT);

    private static final Pattern EXTENSION_OR_COMMENT_SCHEMA = Pattern.compile(
            "(?i)\\b(?:CREATE\\s+EXTENSION\\b[^;]*?\\bSCHEMA\\s+"
                    + "|COMMENT\\s+ON\\s+SCHEMA\\s+"
                    + "|GRANT\\b[^;]*?\\bON\\s+SCHEMA(?:\\s*::\\s*|\\s+)"
                    + "|COMMENT\\s+ON\\s+DATABASE\\s+)"
                    + IDENT);

    /**
     * Every {@code ON [TABLE] db.*} / {@code ON *.*} occurrence (matched independently so a
     * later in-scope grant cannot hide an earlier foreign one).
     */
    private static final Pattern GRANT_ON_DB_STAR = Pattern.compile(
            "(?i)\\bON(?=[\\s*\"`\\[])\\s*(?:TABLE\\s+)?(?:"
                    + "(\\*)\\s*\\.\\s*\\*"
                    + "|" + IDENT + "\\s*\\.\\s*\\*)");

    /** {@code ON DATABASE x} (PostgreSQL) and {@code ON DATABASE::x} (SQL Server). */
    private static final Pattern GRANT_ON_DATABASE = Pattern.compile("(?i)\\bON\\s+DATABASE\\b");

    private static final Pattern GRANT_KEYWORD = Pattern.compile("(?i)\\bGRANT\\b");

    /** Unescaped MySQL grant wildcard characters in a database name. */
    private static final Pattern MYSQL_GRANT_WILDCARD = Pattern.compile("(?<!\\\\)[_%]");

    /** Session namespace mutators that would defeat schema binding for unqualified DDL. */
    private static final Pattern SESSION_NAMESPACE = Pattern.compile(
            "(?is)(?:\\bSET\\s+(?:LOCAL\\s+|SESSION\\s+)?search_path\\b"
                    + "|\\bset_config\"?\\s*\\("
                    + "|\\bALTER\\s+SESSION\\s+SET\\s+CURRENT_SCHEMA\\b"
                    + "|\\bUSE\\s+[A-Za-z_\"`\\[])");

    private static final Pattern ORACLE_DB_LINK = Pattern.compile("[\\p{L}\\p{N}_$#\"]\\s*@\\s*[\\p{L}\"]");

    /** {@code db.schema.object}: PostgreSQL and SQL Server cross-database or foreign-catalog names. */
    private static final Pattern THREE_PART = Pattern.compile(
            IDENT + "\\s*\\.\\s*" + IDENT + "\\s*\\.\\s*" + IDENT);

    /** Keywords that make the following qualified name a write or DDL target. */
    private static final Pattern WRITE_TARGET_BEFORE = Pattern.compile(
            "(?is)\\b(?:UPDATE|INTO|TABLE|ON|REFERENCES|FUNCTION|PROCEDURE|VIEW|SEQUENCE|COLUMN|INDEX|TRIGGER)"
                    + "(?:\\s+IF(?:\\s+NOT)?\\s+EXISTS|\\s+ONLY|\\s+TOP\\s*\\(\\s*\\d+\\s*\\)(?:\\s+PERCENT)?)*"
                    + "\\s*$");

    /** {@code EXECUTE FUNCTION pg_catalog.f()} in a trigger calls a function; it does not define one. */
    private static final Pattern TRIGGER_CALL_BEFORE =
            Pattern.compile("(?is)\\bEXECUTE\\s+(?:FUNCTION|PROCEDURE)\\s*$");

    /** {@code COMMENT ON COLUMN [schema.]table.column}: the leading part is a table, not a schema. */
    private static final Pattern COMMENT_ON_COLUMN = Pattern.compile(
            "(?i)^(\\s*COMMENT\\s+ON\\s+COLUMN\\s+)" + IDENT + "\\s*\\.\\s*" + IDENT
                    + "(?:\\s*\\.\\s*" + IDENT + ")?");

    private ChangeSetSchemaScope() {
    }

    /**
     * Namespace comparison follows the dialect's identifier folding: PostgreSQL folds
     * unquoted names to lower case, Oracle to upper case, and quoted names are exact.
     * MySQL/MariaDB catalog and SQL Server schema case sensitivity depends on server
     * settings / collation, so those compare exactly (fail closed).
     */
    static void requireScoped(String sql, String configuredNamespace, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        Namespace allowed = new Namespace(canonical(configuredNamespace, false, dialect), dialect);
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        // Comments and literals masked with the dialect's own lexing rules; quoted
        // identifiers and dollar bodies stay visible for binding checks.
        String scannable = SqlLexer.maskForScope(sql, mode, false);
        if (SESSION_NAMESPACE.matcher(scannable).find()) {
            throw new IllegalArgumentException(
                    "schema change SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        boolean routine = scannable.toUpperCase(Locale.ROOT)
                .matches("(?s)^\\s*CREATE\\s+(OR\\s+REPLACE\\s+)?(FUNCTION|TRIGGER|PROCEDURE)\\b.*");
        scannable = checkCommentOnColumn(scannable, allowed, configuredNamespace, sql);
        scan(scannable, allowed, configuredNamespace, sql, "", routine);
        if (routine) {
            for (String literal : SqlLexer.literals(sql, mode)) {
                String body = SqlLexer.bodyAsCode(literal, mode, true);
                scan(body, allowed, configuredNamespace, sql, " inside routine body", true);
                if (SESSION_NAMESPACE.matcher(body).find()) {
                    throw new IllegalArgumentException(
                            "schema change SQL must not alter session namespace inside routine body: "
                                    + summarize(sql));
                }
            }
        }
    }

    /**
     * Validates the column name of {@code COMMENT ON COLUMN} (three parts: the first must be the
     * configured namespace) and blanks it so {@code table.column} is not read as {@code schema.object}.
     */
    private static String checkCommentOnColumn(String scannable, Namespace allowed, String configuredNamespace,
                                               String sql) {
        Matcher comment = COMMENT_ON_COLUMN.matcher(scannable);
        if (!comment.find()) {
            return scannable;
        }
        boolean threePart = optionalCapture(comment, 10, 11, 12, 13) != null;
        if (threePart) {
            String schema = firstNonNull(comment, 2, 3, 4, 5);
            if (!allowed.matches(schema, comment.group(5) == null)) {
                throw new IllegalArgumentException("schema change SQL targets namespace '" + schema
                        + "' but synchronizer is configured for '" + configuredNamespace + "': " + summarize(sql));
            }
        }
        int start = comment.end(1);
        return scannable.substring(0, start) + " ".repeat(comment.end() - start) + scannable.substring(comment.end());
    }

    /** Oracle database links ({@code table@link}) reach outside the connected database. */
    static void requireNoDatabaseLink(String scannable, DatabaseDialect dialect, String sql) {
        if (dialect == DatabaseDialect.ORACLE && ORACLE_DB_LINK.matcher(scannable).find()) {
            throw new IllegalArgumentException(
                    "schema change SQL must not reference an Oracle database link (object@link): "
                            + summarize(sql));
        }
    }

    /**
     * Read-only verification queries routinely use {@code alias.column}, which is lexically
     * identical to {@code schema.table}, so only session-namespace mutators are rejected there.
     */
    static void requireNoSessionNamespaceChange(String sql, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String scannable = SqlLexer.maskForScope(sql, SqlLexer.mode(dialect), false);
        if (SESSION_NAMESPACE.matcher(scannable).find()) {
            throw new IllegalArgumentException(
                    "schema verification SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        requireNoDatabaseLink(scannable, dialect, sql);
    }

    /** True when an unquoted {@code reference} names the configured namespace under the dialect's folding. */
    static boolean sameNamespace(String configuredNamespace, String reference, DatabaseDialect dialect) {
        return canonical(configuredNamespace, false, dialect).equals(canonical(reference, false, dialect));
    }

    static String canonical(String identifier, boolean quoted, DatabaseDialect dialect) {
        if (quoted) {
            return identifier;
        }
        return switch (dialect) {
            case POSTGRESQL -> identifier.toLowerCase(Locale.ROOT);
            case ORACLE -> identifier.toUpperCase(Locale.ROOT);
            case MYSQL, MARIADB, SQLSERVER -> identifier;
        };
    }

    private record Namespace(String canonical, DatabaseDialect dialect) {
        boolean matches(String raw, boolean quoted) {
            return canonical.equals(ChangeSetSchemaScope.canonical(raw, quoted, dialect));
        }
    }

    private static void scan(String text, Namespace allowed, String configuredNamespace, String sql, String where,
                             boolean routine) {
        Matcher doubleDot = DOUBLE_DOT.matcher(text);
        if (doubleDot.find()) {
            throw new IllegalArgumentException("schema change SQL must not use database..object references"
                    + where + ": " + summarize(sql));
        }
        DatabaseDialect dialect = allowed.dialect();
        if ((dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.SQLSERVER)
                && THREE_PART.matcher(text).find()) {
            throw new IllegalArgumentException("schema change SQL must not use database.schema.object references"
                    + where + ": " + summarize(sql));
        }
        requireNoDatabaseLink(text, dialect, sql);
        rejectUnsafeGrants(text, allowed, configuredNamespace, sql);
        rejectForeignSchema(text, QUALIFIED, allowed, configuredNamespace, sql,
                "targets namespace" + where, routine, true);
        rejectForeignSchema(text, IN_SCHEMA, allowed, configuredNamespace, sql,
                "uses IN SCHEMA" + where, false, false);
        rejectForeignSchema(text, EXTENSION_OR_COMMENT_SCHEMA, allowed, configuredNamespace, sql,
                "references schema" + where, false, false);
    }

    private static void rejectUnsafeGrants(String scannable, Namespace allowed, String configuredNamespace,
                                           String sql) {
        Matcher star = GRANT_ON_DB_STAR.matcher(scannable);
        while (star.find()) {
            if (!grantKeywordInCurrentStatement(scannable, star.start())) {
                continue;
            }
            if (star.group(1) != null) {
                throw new IllegalArgumentException(
                        "schema change SQL must not GRANT on *.* (cross-database privileges): "
                                + summarize(sql));
            }
            String db = optionalCapture(star, 2, 3, 4, 5);
            boolean quoted = star.group(5) == null;
            if (db != null && allowed.dialect().isMySqlFamily()) {
                if (MYSQL_GRANT_WILDCARD.matcher(db).find()) {
                    throw new IllegalArgumentException("schema change SQL GRANT database name '" + db
                            + "' contains an unescaped MySQL wildcard (_ or %): " + summarize(sql));
                }
                db = db.replace("\\_", "_").replace("\\%", "%");
            }
            if (db == null || !allowed.matches(db, quoted)) {
                throw new IllegalArgumentException(
                        "schema change SQL GRANT targets database '" + (db == null ? "?" : db)
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
        Matcher database = GRANT_ON_DATABASE.matcher(scannable);
        while (database.find()) {
            if (grantKeywordInCurrentStatement(scannable, database.start())) {
                throw new IllegalArgumentException(
                        "schema change SQL must not GRANT database-level privileges "
                                + "(exceeds the configured namespace binding): " + summarize(sql));
            }
        }
    }

    /** True when {@code GRANT} appears in the same semicolon-delimited statement as {@code offset}. */
    private static boolean grantKeywordInCurrentStatement(String scannable, int offset) {
        int statementStart = scannable.lastIndexOf(';', Math.max(0, offset - 1)) + 1;
        return GRANT_KEYWORD.matcher(scannable.substring(statementStart, offset)).find();
    }

    private static void rejectForeignSchema(String text, Pattern pattern, Namespace allowed,
                                            String configuredNamespace, String sql, String verb,
                                            boolean triggerRecords, boolean catalogReadable) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String schema = firstNonNull(matcher, 1, 2, 3, 4);
            String lower = schema.toLowerCase(Locale.ROOT);
            if (catalogReadable && isSystemCatalog(lower, allowed.dialect())) {
                String before = text.substring(0, matcher.start());
                if (WRITE_TARGET_BEFORE.matcher(before).find() && !TRIGGER_CALL_BEFORE.matcher(before).find()) {
                    throw new IllegalArgumentException("schema change SQL must not write to or define objects in "
                            + "system catalog '" + schema + "': " + summarize(sql));
                }
                continue;
            }
            if (triggerRecords && matcher.group(4) != null && ("new".equals(lower) || "old".equals(lower))) {
                continue;
            }
            if (!allowed.matches(schema, matcher.group(4) == null)) {
                throw new IllegalArgumentException(
                        "schema change SQL " + verb + " '" + schema
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
    }

    private static String optionalCapture(Matcher matcher, int... groups) {
        for (int group : groups) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonNull(Matcher matcher, int... groups) {
        String value = optionalCapture(matcher, groups);
        if (value == null) {
            throw new IllegalStateException("qualified-name match missing schema capture");
        }
        return value;
    }

    private static boolean isSystemCatalog(String schema, DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "pg_catalog".equals(schema) || "information_schema".equals(schema);
            case SQLSERVER -> "sys".equals(schema) || "information_schema".equals(schema);
            case MYSQL, MARIADB -> "information_schema".equals(schema);
            case ORACLE -> false;
        };
    }

    private static String summarize(String sql) {
        return SqlLexer.summarize(sql);
    }
}
