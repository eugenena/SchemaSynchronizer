package com.thinkai.schema;

import java.util.List;
import java.util.Locale;
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
        String normalized = stripComments(sql).toUpperCase(Locale.ROOT);
        requireSingleStatement(sql, "schema change SQL");
        requireNoForbiddenTokens(sql);
        if (!startsWithAllowedVerb(normalized)) {
            throw new IllegalArgumentException("unsupported schema change SQL: " + summarize(sql));
        }
    }

    public static void requireReadOnlyVerification(String sql) {
        String normalized = sql == null ? "" : stripComments(sql).toUpperCase(Locale.ROOT);
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
        if (!stripComments(sql).toUpperCase(Locale.ROOT).matches("(?s)^CREATE\\s+TABLE\\b.*")) {
            throw new IllegalArgumentException("table createSql must contain one CREATE TABLE statement");
        }
    }

    public static void requireCreateIndex(String sql) {
        requireSafe(sql);
        requireSingleStatement(sql, "index definition");
        if (!stripComments(sql).toUpperCase(Locale.ROOT)
                .matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*")) {
            throw new IllegalArgumentException("index definition must contain one CREATE INDEX statement");
        }
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
        String normalized = stripComments(sql).toUpperCase(Locale.ROOT);
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(normalized).find()) {
                throw new IllegalArgumentException("destructive or manually-reviewed SQL is not allowed: "
                        + summarize(sql));
            }
        }
    }

    private static boolean startsWithAllowedVerb(String sql) {
        return sql.matches("(?s)^(CREATE|ALTER|UPDATE|INSERT|COMMENT|GRANT|DO|SELECT)\\b.*");
    }

    private static Pattern token(String expression) {
        return Pattern.compile("(?s)\\b" + expression + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
    }

    private static String summarize(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
    }
}
