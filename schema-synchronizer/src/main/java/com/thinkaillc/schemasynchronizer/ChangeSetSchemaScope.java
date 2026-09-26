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
 */
final class ChangeSetSchemaScope {
    private static final String IDENT =
            "(?:\"([^\"]+)\"|\\[([^\\]]+)\\]|`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))";

    /** Any schema.object form that survives string/comment masking. */
    private static final Pattern QUALIFIED = Pattern.compile(IDENT + "\\." + IDENT);

    private static final Pattern IN_SCHEMA = Pattern.compile(
            "(?i)\\bIN\\s+SCHEMA\\s+" + IDENT);

    private static final Pattern EXTENSION_OR_COMMENT_SCHEMA = Pattern.compile(
            "(?i)\\b(?:CREATE\\s+EXTENSION\\b[^;]*?\\bSCHEMA"
                    + "|COMMENT\\s+ON\\s+SCHEMA"
                    + "|GRANT\\b[^;]*?\\bON\\s+SCHEMA"
                    + "|COMMENT\\s+ON\\s+DATABASE)\\s+"
                    + IDENT);

    /** Session namespace mutators that would defeat schema binding for unqualified DDL. */
    private static final Pattern SESSION_NAMESPACE = Pattern.compile(
            "(?is)\\b(?:SET\\s+(?:LOCAL\\s+|SESSION\\s+)?search_path\\b"
                    + "|set_config\\s*\\("
                    + "|pg_catalog\\.set_config\\s*\\("
                    + "|ALTER\\s+SESSION\\s+SET\\s+CURRENT_SCHEMA\\b"
                    + "|\\bUSE\\s+[A-Za-z_\"`\\[])");

    private ChangeSetSchemaScope() {
    }

    static void requireScoped(String sql, String configuredNamespace) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String allowed = configuredNamespace.toLowerCase(Locale.ROOT);
        // Session mutators are checked on comment-stripped SQL so string contents of
        // set_config('search_path', …) remain visible.
        if (SESSION_NAMESPACE.matcher(stripComments(sql)).find()) {
            throw new IllegalArgumentException(
                    "schema change SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        // Mask only comments and ordinary string literals so "schema"."table",
        // [schema].[table], and `schema`.`table` remain visible for binding checks.
        String scannable = maskStringsAndComments(sql);
        rejectForeignSchema(QUALIFIED.matcher(scannable), allowed, configuredNamespace, sql,
                "targets namespace");
        rejectForeignSchema(IN_SCHEMA.matcher(scannable), allowed, configuredNamespace, sql,
                "uses IN SCHEMA");
        rejectForeignSchema(EXTENSION_OR_COMMENT_SCHEMA.matcher(scannable), allowed, configuredNamespace, sql,
                "references schema");
        // Single-quoted / E'…' / U&'…' routine bodies are masked above; scan the AS body
        // the same way NonDestructiveSqlPolicy does for DROP tokens.
        if (scannable.toUpperCase(Locale.ROOT)
                .matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?(FUNCTION|TRIGGER|PROCEDURE)\\b.*")) {
            String body = NonDestructiveSqlPolicy.routineBodyForScan(sql);
            rejectForeignSchema(QUALIFIED.matcher(body), allowed, configuredNamespace, sql,
                    "targets namespace inside routine body");
            rejectForeignSchema(IN_SCHEMA.matcher(body), allowed, configuredNamespace, sql,
                    "uses IN SCHEMA inside routine body");
            rejectForeignSchema(EXTENSION_OR_COMMENT_SCHEMA.matcher(body), allowed, configuredNamespace, sql,
                    "references schema inside routine body");
            if (SESSION_NAMESPACE.matcher(body).find()) {
                throw new IllegalArgumentException(
                        "schema change SQL must not alter session namespace inside routine body: "
                                + summarize(sql));
            }
        }
    }

    private static void rejectForeignSchema(Matcher matcher, String allowed, String configuredNamespace,
                                            String sql, String verb) {
        while (matcher.find()) {
            String schema = firstNonNull(matcher, 1, 2, 3, 4).toLowerCase(Locale.ROOT);
            if (isSystemCatalog(schema)) {
                continue;
            }
            if (!schema.equals(allowed)) {
                throw new IllegalArgumentException(
                        "schema change SQL " + verb + " '" + firstNonNull(matcher, 1, 2, 3, 4)
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
    }

    private static String firstNonNull(Matcher matcher, int... groups) {
        for (int group : groups) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("qualified-name match missing schema capture");
    }

    private static boolean isSystemCatalog(String schema) {
        return "pg_catalog".equals(schema)
                || "information_schema".equals(schema)
                || "sys".equals(schema);
    }

    /** Masks comments and single-quoted literals; leaves identifiers and dollar bodies. */
    private static String maskStringsAndComments(String sql) {
        char[] result = sql.toCharArray();
        boolean singleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                result[index] = ' ';
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                result[index] = ' ';
                if (current == '*' && next == '/') {
                    result[++index] = ' ';
                    blockComment = false;
                }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, index)) {
                    index += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (singleQuoted) {
                result[index] = ' ';
                if (current == '\'' && next == '\'') {
                    result[++index] = ' ';
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                result[index] = result[++index] = ' ';
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result[index] = result[++index] = ' ';
                blockComment = true;
            } else if (current == '\'') {
                result[index] = ' ';
                singleQuoted = true;
            } else if (current == '$') {
                int end = sql.indexOf('$', index + 1);
                if (end >= 0) {
                    String candidate = sql.substring(index, end + 1);
                    if (candidate.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")) {
                        dollarTag = candidate;
                        index = end;
                    }
                }
            }
        }
        return new String(result);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
    }

    private static String summarize(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
    }
}
