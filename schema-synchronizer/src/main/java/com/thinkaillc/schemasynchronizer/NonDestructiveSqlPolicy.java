// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed policy for versioned custom SQL. */
public final class NonDestructiveSqlPolicy {
    private static final List<Pattern> FORBIDDEN = List.of(
            token("DROP\\s+(TABLE|COLUMN|INDEX|SCHEMA|DATABASE|CONSTRAINT)"),
            token("TRUNCATE"),
            token("DELETE\\s+FROM"),
            token("ALTER\\s+TABLE[\\s\\S]+DROP\\s+"),
            token("ALTER\\s+TABLE[\\s\\S]+ALTER\\s+COLUMN[\\s\\S]+TYPE\\s+")
    );

    private NonDestructiveSqlPolicy() {}

    public static void requireSafe(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("schema change statement is blank");
        }
        String normalized = executableSql(sql).toUpperCase(Locale.ROOT);
        requireSingleStatement(sql, "schema change SQL");
        requireNoForbiddenTokens(sql);
        if (!matchesAllowedStatement(normalized)) {
            throw new IllegalArgumentException("unsupported schema change SQL: " + summarize(sql));
        }
    }

    public static void requireReadOnlyVerification(String sql) {
        String normalized = sql == null ? "" : executableSql(sql).toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?s)^(SELECT|WITH)\\b.*")) {
            throw new IllegalArgumentException("verification SQL must be a SELECT or WITH query");
        }
        requireSingleStatement(sql, "verification SQL");
        requireNoForbiddenTokens(sql);
        if (normalized.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*")) {
            throw new IllegalArgumentException("verification SQL must not contain a data-modifying statement");
        }
    }

    public static void requireCreateTable(String sql) {
        requireSafe(sql);
        requireSingleStatement(sql, "table createSql");
        if (!executableSql(sql).toUpperCase(Locale.ROOT).matches("(?s)^CREATE\\s+TABLE\\b.*")) {
            throw new IllegalArgumentException("table createSql must contain one CREATE TABLE statement");
        }
    }

    public static void requireCreateIndex(String sql) {
        requireCreateIndex(sql, true);
    }

    static void requireCreateIndex(String sql, boolean requireIfNotExists) {
        requireSafe(sql);
        requireSingleStatement(sql, "index definition");
        if (!executableSql(sql).toUpperCase(Locale.ROOT)
                .matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*")) {
            throw new IllegalArgumentException("index definition must contain one CREATE INDEX statement");
        }
        if (requireIfNotExists && !executableSql(sql).toUpperCase(Locale.ROOT)
                .matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\b.*")) {
            throw new IllegalArgumentException("index definition must use IF NOT EXISTS");
        }
        IndexDefinition.parse(sql);
    }

    private static void requireSingleStatement(String sql, String label) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
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
                if (current == '\'' && next == '\'') index++;
                else if (current == '\'') singleQuoted = false;
                continue;
            }
            if (doubleQuoted) {
                if (current == '"' && next == '"') index++;
                else if (current == '"') doubleQuoted = false;
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '$') {
                int end = sql.indexOf('$', index + 1);
                if (end >= 0) {
                    String candidate = sql.substring(index, end + 1);
                    if (candidate.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")) {
                        dollarTag = candidate;
                        index = end;
                    }
                }
            } else if (current == ';' && !stripComments(sql.substring(index + 1)).isBlank()) {
                throw new IllegalArgumentException(label + " must contain exactly one statement");
            }
        }
    }

    private static void requireNoForbiddenTokens(String sql) {
        String normalized = executableSql(sql).toUpperCase(Locale.ROOT);
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(normalized).find()) {
                throw new IllegalArgumentException("destructive or manually-reviewed SQL is not allowed: "
                        + summarize(sql));
            }
        }
        // CREATE FUNCTION / TRIGGER / PROCEDURE bodies may be dollar-quoted or
        // single-quoted; executableSql blanks both. Extract and scan the AS body.
        if (normalized.matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?(FUNCTION|TRIGGER|PROCEDURE)\\b.*")) {
            String bodyScan = routineBodyForScan(sql).toUpperCase(Locale.ROOT);
            for (Pattern pattern : FORBIDDEN) {
                if (pattern.matcher(bodyScan).find()) {
                    throw new IllegalArgumentException("destructive or manually-reviewed SQL is not allowed: "
                            + summarize(sql));
                }
            }
        }
    }

    /**
     * Returns the routine body after {@code AS} for forbidden-token scanning.
     * Dollar-quoted and ordinary / escape / Unicode single-quoted bodies are included.
     */
    static String routineBodyForScan(String sql) {
        Matcher dollar = Pattern.compile("(?is)\\bAS\\s+(\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$)(.*)\\1")
                .matcher(sql);
        if (dollar.find()) {
            return dollar.group(2);
        }
        Matcher quoted = Pattern.compile("(?is)\\bAS\\s+(?:E|U&)?'((?:[^']|'')*)'")
                .matcher(sql);
        if (quoted.find()) {
            return quoted.group(1).replace("''", "'");
        }
        // Fallback: scan with dollar bodies visible and ordinary strings masked.
        return scannableSql(sql);
    }

    private static boolean matchesAllowedStatement(String sql) {
        return sql.matches("(?s)^CREATE\\s+TABLE\\b.*")
                || sql.matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*")
                || sql.matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\b.*")
                || sql.matches("(?s)^CREATE\\s+TRIGGER\\b.*")
                || sql.matches("(?s)^CREATE\\s+EXTENSION\\b.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+ADD\\s+(COLUMN|CONSTRAINT)\\b.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+ADD\\s+\\(.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+ADD\\s+[A-Z_][A-Z0-9_]*\\b.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+ALTER\\s+COLUMN\\b.*\\s+(SET|DROP)\\s+(DEFAULT|NOT\\s+NULL)\\b.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+MODIFY\\s*\\(.*\\b(DEFAULT|NULL|NOT\\s+NULL)\\b.*")
                || sql.matches("(?s)^ALTER\\s+TABLE\\b.*\\s+VALIDATE\\s+CONSTRAINT\\b.*")
                || sql.matches("(?s)^(UPDATE|INSERT\\s+INTO)\\b.*")
                || sql.matches("(?s)^COMMENT\\s+ON\\b.*")
                || sql.matches("(?s)^GRANT\\b.*")
                || sql.matches("(?s)^SELECT\\b.*");
    }

    private static Pattern token(String expression) {
        return Pattern.compile("(?s)\\b" + expression + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
    }

    /**
     * Masks comments and ordinary string/identifier quotes, but leaves dollar-quoted
     * bodies visible so forbidden tokens inside {@code CREATE FUNCTION} / {@code TRIGGER}
     * bodies can still be detected.
     */
    static String scannableSql(String sql) {
        return maskSql(sql, false);
    }

    /** Returns only executable SQL text, masking comments and every quoted form. */
    private static String executableSql(String sql) {
        return maskSql(sql, true);
    }

    private static String maskSql(String sql, boolean maskDollarBodies) {
        char[] result = sql.toCharArray();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                result[index] = ' ';
                if (current == '\n') lineComment = false;
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
                    if (maskDollarBodies) {
                        for (int offset = 0; offset < dollarTag.length(); offset++) {
                            result[index + offset] = ' ';
                        }
                    }
                    index += dollarTag.length() - 1;
                    dollarTag = null;
                } else if (maskDollarBodies) {
                    result[index] = ' ';
                }
                continue;
            }
            if (singleQuoted) {
                result[index] = ' ';
                if (current == '\'' && next == '\'') result[++index] = ' ';
                else if (current == '\'') singleQuoted = false;
                continue;
            }
            if (doubleQuoted) {
                result[index] = ' ';
                if (current == '"' && next == '"') result[++index] = ' ';
                else if (current == '"') doubleQuoted = false;
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
            } else if (current == '"') {
                result[index] = ' ';
                doubleQuoted = true;
            } else if (current == '$') {
                int end = sql.indexOf('$', index + 1);
                if (end >= 0) {
                    String candidate = sql.substring(index, end + 1);
                    if (candidate.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")) {
                        if (maskDollarBodies) {
                            for (int offset = 0; offset < candidate.length(); offset++) {
                                result[index + offset] = ' ';
                            }
                        }
                        dollarTag = candidate;
                        index = end;
                    }
                }
            }
        }
        return new String(result).trim();
    }

    private static String summarize(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
    }
}
