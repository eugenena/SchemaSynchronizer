// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SchemaSnapshotWriter-style {@code definition} strings into {@link ColumnSpec}.
 */
public final class ColumnDefinitionParser {

    /** Sentinel length for SQL Server {@code (MAX)} / unbounded portable forms. */
    public static final int MAX_LENGTH = -1;

    private static final Pattern DEFAULT = Pattern.compile(
            "\\s+DEFAULT\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_NULL = Pattern.compile(
            "\\s+NOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_INCREMENT = Pattern.compile(
            "\\s+AUTO_INCREMENT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY = Pattern.compile(
            "\\s+(?:GENERATED\\s+(?:BY\\s+DEFAULT|ALWAYS)\\s+AS\\s+IDENTITY(?:\\s*\\([^)]*\\))?|"
                    + "IDENTITY(?:\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\))?)",
            Pattern.CASE_INSENSITIVE);
    /** ojdbc reports {@code TIMESTAMP(6) WITH TIME ZONE} as TYPE_NAME. */
    private static final Pattern TIMESTAMP_TZ = Pattern.compile(
            "^(TIMESTAMP(?:\\(\\d+\\))?\\s+WITH\\s+(?:LOCAL\\s+)?TIME\\s+ZONE)\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_LEN = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_\\s]*?)(?:\\((MAX|\\d+)(?:\\s+(?:CHAR|BYTE))?(?:\\s*,\\s*(\\d+))?\\))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_CAST = Pattern.compile("::[A-Za-z][A-Za-z0-9_\\s]*$");
    private static final Pattern ON_UPDATE = Pattern.compile("\\s+ON\\s+UPDATE\\s+", Pattern.CASE_INSENSITIVE);

    private ColumnDefinitionParser() {}

    public static ColumnSpec parse(String definition) {
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("column definition is blank");
        }
        if (definition.contains(";") || definition.contains("--") || definition.contains("/*")) {
            throw new IllegalArgumentException("column definition contains SQL statement or comment syntax");
        }
        String rest = definition.trim();
        rest = AUTO_INCREMENT.matcher(rest).replaceFirst("").trim();
        rest = IDENTITY.matcher(rest).replaceFirst("").trim();
        // Greedy DEFAULT …$ would swallow a trailing constraint NOT NULL
        // ("TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL"). Peel that constraint only when
        // it sits outside single-quoted literals so defaults like 'is NOT NULL' stay intact.
        boolean notNull = false;
        String defaultExpr = null;
        Matcher defM = DEFAULT.matcher(rest);
        if (defM.find()) {
            defaultExpr = defM.group(1).trim();
            rest = rest.substring(0, defM.start()).trim();
            int peel = indexOfTrailingConstraintNotNull(defaultExpr);
            if (peel >= 0) {
                notNull = true;
                defaultExpr = defaultExpr.substring(0, peel).trim();
            }
            // MySQL "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP": ON UPDATE is not part of the default.
            Matcher onUpdate = ON_UPDATE.matcher(defaultExpr);
            if (onUpdate.find() && !isInsideSingleQuotes(defaultExpr, onUpdate.start())) {
                defaultExpr = defaultExpr.substring(0, onUpdate.start()).trim();
            }
        }
        Matcher nn = NOT_NULL.matcher(rest);
        if (nn.find()) {
            notNull = true;
            rest = (rest.substring(0, nn.start()) + rest.substring(nn.end())).trim();
        }
        Matcher tz = TIMESTAMP_TZ.matcher(rest);
        if (tz.matches()) {
            String leftover = tz.group(2) == null ? "" : tz.group(2).trim();
            if (!leftover.isEmpty()) {
                throw new IllegalArgumentException("unparseable column type: " + definition);
            }
            return new ColumnSpec("TIMESTAMPTZ", null, null, notNull, defaultExpr);
        }
        Matcher tm = TYPE_LEN.matcher(rest);
        if (!tm.matches()) {
            throw new IllegalArgumentException("unparseable column type: " + definition);
        }
        String rawType = tm.group(1).trim();
        Integer length = null;
        Integer scale = null;
        if (tm.group(2) != null) {
            if ("MAX".equalsIgnoreCase(tm.group(2))) {
                length = MAX_LENGTH;
            } else {
                length = Integer.parseInt(tm.group(2));
            }
        }
        if (tm.group(3) != null) {
            if (length != null && length == MAX_LENGTH) {
                throw new IllegalArgumentException("MAX types cannot have a scale: " + definition);
            }
            scale = Integer.parseInt(tm.group(3));
        }
        String normalized = normalizeType(rawType);
        if (length != null && length == MAX_LENGTH && !isVariableLength(normalized)) {
            throw new IllegalArgumentException("MAX length is only valid for VARCHAR/NVARCHAR/VARBINARY: "
                    + definition);
        }
        if (length == null && isFixedLength(normalized)) {
            length = 1; // CHAR, NCHAR, and BINARY without a length mean length 1 on every engine.
        }
        if (scale != null && !"NUMERIC".equals(normalized)) {
            throw new IllegalArgumentException("scale is supported only for NUMERIC: " + definition);
        }
        if (scale != null && scale > length) {
            throw new IllegalArgumentException("NUMERIC scale exceeds precision: " + definition);
        }
        return new ColumnSpec(normalized, length, scale, notNull, defaultExpr);
    }

    /**
     * Comparable form of a DEFAULT expression: PostgreSQL casts removed, redundant outer
     * parentheses removed (SQL Server reports {@code 0} as {@code ((0))}), and text outside
     * string literals upper-cased so {@code getdate()} equals {@code GETDATE()}.
     */
    public static String normalizeDefault(String defaultExpr) {
        if (defaultExpr == null) {
            return null;
        }
        String d = defaultExpr.trim();
        Matcher cast = PG_CAST.matcher(d);
        if (cast.find()) {
            d = d.substring(0, cast.start()).trim();
        }
        d = stripOuterParentheses(d);
        d = upperOutsideLiterals(d);
        if (d.equals("NULL")) {
            return null;
        }
        // SQL Server CURRENT_TIMESTAMP is GETDATE(); PostgreSQL/MySQL CURRENT_TIMESTAMP is NOW().
        if (d.equals("CURRENT_TIMESTAMP") || d.equals("CURRENT_TIMESTAMP()") || d.equals("GETDATE()")
                || d.equals("NOW()")) {
            return "CURRENT_TIMESTAMP";
        }
        return d;
    }

    static String stripOuterParentheses(String expression) {
        String result = expression.trim();
        while (result.length() >= 2 && result.charAt(0) == '(' && closingParenthesis(result) == result.length() - 1) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    /** Index of the parenthesis closing the one at 0, ignoring single-quoted literals; -1 if unbalanced. */
    private static int closingParenthesis(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String upperOutsideLiterals(String text) {
        StringBuilder result = new StringBuilder(text.length());
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            result.append(quoted || current == '\'' ? current : Character.toUpperCase(current));
        }
        return result.toString();
    }

    public static String normalizeType(String typeName) {
        if (typeName == null) {
            return "";
        }
        String t = typeName.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")
                // SQL Server reports identity columns as e.g. "bigint identity".
                .replaceFirst(" IDENTITY$", "")
                // Oracle reports TIMESTAMP precision in TYPE_NAME: TIMESTAMP(6) [WITH [LOCAL] TIME ZONE].
                .replaceFirst("^TIMESTAMP\\s*\\(\\d+\\)", "TIMESTAMP");
        return switch (t) {
            case "NVARCHAR", "NVARCHAR2", "NATIONAL CHARACTER VARYING" -> "NVARCHAR";
            case "CHARACTER VARYING", "VARCHAR", "VARCHAR2" -> "VARCHAR";
            case "CHARACTER", "CHAR", "BPCHAR" -> "CHAR";
            case "NCHAR", "NATIONAL CHARACTER", "NATIONAL CHAR" -> "NCHAR";
            case "INT", "INT4", "INTEGER" -> "INTEGER";
            case "INT8", "BIGINT" -> "BIGINT";
            case "INT2", "SMALLINT" -> "SMALLINT";
            case "BOOL", "BOOLEAN", "BIT" -> "BOOLEAN";
            case "DECIMAL", "NUMERIC", "NUMBER" -> "NUMERIC";
            case "FLOAT4", "REAL" -> "REAL";
            case "FLOAT8", "DOUBLE PRECISION", "DOUBLE" -> "DOUBLE PRECISION";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ",
                 "TIMESTAMP WITH LOCAL TIME ZONE" -> "TIMESTAMPTZ";
            case "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP" -> "TIMESTAMP";
            // Oracle RAW is variable-length binary; BINARY(n) is fixed-length and pads.
            case "VARBINARY", "RAW" -> "VARBINARY";
            case "BINARY" -> "BINARY";
            case "BIGSERIAL" -> "BIGINT"; // compare as bigint for widen checks
            case "SERIAL" -> "INTEGER";
            default -> t;
        };
    }

    /** Types whose length may be {@code (MAX)} and that widen by length. */
    public static boolean isVariableLength(String normalizedType) {
        return "VARCHAR".equals(normalizedType) || "NVARCHAR".equals(normalizedType)
                || "VARBINARY".equals(normalizedType);
    }

    /** Fixed-length types whose declared length is significant. */
    public static boolean isFixedLength(String normalizedType) {
        return "CHAR".equals(normalizedType) || "NCHAR".equals(normalizedType) || "BINARY".equals(normalizedType);
    }

    /** Types whose live length is read and compared. */
    public static boolean hasLength(String normalizedType) {
        return isVariableLength(normalizedType) || isFixedLength(normalizedType);
    }

    /** Integer family rank for widening (higher = wider). -1 if not integer. */
    public static int integerRank(String normalizedType) {
        return switch (normalizedType) {
            case "SMALLINT" -> 1;
            case "INTEGER" -> 2;
            case "BIGINT" -> 3;
            default -> -1;
        };
    }

    /** Float family rank. -1 if not float. */
    public static int floatRank(String normalizedType) {
        return switch (normalizedType) {
            case "REAL" -> 1;
            case "DOUBLE PRECISION" -> 2;
            default -> -1;
        };
    }

    /** Effective length for widen/narrow compares; {@link #MAX_LENGTH} is unbounded. */
    public static int effectiveLength(Integer length) {
        if (length == null || length == MAX_LENGTH) {
            return Integer.MAX_VALUE;
        }
        return length;
    }

    /**
     * Index of a trailing {@code NOT NULL} constraint in a DEFAULT expression, or {@code -1}.
     * Ignores {@code NOT NULL} inside single-quoted literals ({@code ''}-escaped).
     */
    static int indexOfTrailingConstraintNotNull(String defaultExpr) {
        if (defaultExpr == null || defaultExpr.isEmpty()) {
            return -1;
        }
        Matcher trailing = Pattern.compile("\\s+NOT\\s+NULL\\s*$", Pattern.CASE_INSENSITIVE)
                .matcher(defaultExpr);
        if (!trailing.find()) {
            return -1;
        }
        return isInsideSingleQuotes(defaultExpr, trailing.start()) ? -1 : trailing.start();
    }

    /** True when {@code index} falls inside a single-quoted SQL literal. */
    static boolean isInsideSingleQuotes(String text, int index) {
        boolean inQuote = false;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) != '\'') {
                continue;
            }
            if (inQuote && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            inQuote = !inQuote;
        }
        return inQuote;
    }
}
