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
    /** {@code TIMESTAMP(6) WITH TIME ZONE} (ojdbc TYPE_NAME) and PostgreSQL {@code TIME(3) WITHOUT TIME ZONE}. */
    private static final Pattern ZONED_TEMPORAL = Pattern.compile(
            "^(TIMESTAMP|TIME)(?:\\s*\\((\\d+)\\))?\\s+(WITH|WITHOUT)\\s+(LOCAL\\s+)?TIME\\s+ZONE\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_LEN = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_\\s]*?)(?:\\((MAX|\\d+)(?:\\s+(?:CHAR|BYTE))?(?:\\s*,\\s*(\\d+))?\\))?$",
            Pattern.CASE_INSENSITIVE);
    /**
     * MySQL numeric width or precision before UNSIGNED: {@code TINYINT(1) UNSIGNED}, {@code DECIMAL(5,2) UNSIGNED}.
     * ZEROFILL is accepted only on exact numerics, whose precision is compared; an integer display width
     * with ZEROFILL is not.
     */
    private static final Pattern INTEGER_WIDTH_ATTRIBUTES = Pattern.compile(
            "^(?:(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|DECIMAL|NUMERIC|DEC|FIXED|FLOAT|DOUBLE|REAL)"
                    + "\\s*\\(\\s*(\\d+\\s*(?:,\\s*\\d+\\s*)?)\\)\\s+(UNSIGNED)"
                    + "|(DECIMAL|NUMERIC|DEC|FIXED)\\s*\\(\\s*(\\d+\\s*(?:,\\s*\\d+\\s*)?)\\)"
                    + "\\s+(?:UNSIGNED\\s+ZEROFILL|ZEROFILL(?:\\s+UNSIGNED)?))$",
            Pattern.CASE_INSENSITIVE);
    /** MySQL numeric attributes in any order; ZEROFILL implies UNSIGNED. */
    private static final Pattern UNSIGNED_SUFFIX = Pattern.compile("^(.+?)((?: (?:UNSIGNED|ZEROFILL))+)$");
    private static final Pattern ATTRIBUTE_BEFORE_LENGTH = Pattern.compile("(?i).*\\s(?:UNSIGNED|ZEROFILL)$");
    /** PostgreSQL casts, including quoted type names ({@code '101'::"bit"}). */
    private static final Pattern PG_CAST = Pattern.compile("::(?:\"[^\"]+\"|[A-Za-z][A-Za-z0-9_\\s]*)$");
    private static final Pattern ON_UPDATE = Pattern.compile("\\s+ON\\s+UPDATE\\s+", Pattern.CASE_INSENSITIVE);
    /**
     * MySQL {@code ON UPDATE CURRENT_TIMESTAMP[(n)]} and its synonyms, as a whole clause: followed by
     * the end, NULL/NOT NULL, or DEFAULT. Any other ON UPDATE is left in place and rejected.
     */
    private static final Pattern ON_UPDATE_CLAUSE = Pattern.compile(
            "\\s+ON\\s+UPDATE\\s+((?:CURRENT_TIMESTAMP|LOCALTIMESTAMP|LOCALTIME)(?:\\s*\\(\\s*\\d*\\s*\\))?"
                    + "|NOW\\s*\\(\\s*\\d*\\s*\\))(?=\\s*$|\\s+(?:NOT\\s+NULL|NULL|DEFAULT)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_NULL = Pattern.compile("\\s+NULL$", Pattern.CASE_INSENSITIVE);
    /** Oracle requires DEFAULT before inline constraints: {@code DEFAULT 'x' NULL}; not {@code x IS NULL}. */
    private static final Pattern TRAILING_NULL = Pattern.compile("(?<!\\bIS)\\s+NULL\\s*$", Pattern.CASE_INSENSITIVE);

    private ColumnDefinitionParser() {}

    private static String removeOutsideQuotes(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!isInsideSingleQuotes(text, matcher.start())) {
                return (text.substring(0, matcher.start()) + text.substring(matcher.end())).trim();
            }
        }
        return text;
    }

    /** The {@code ON UPDATE} expression of a MySQL column definition, or null. */
    public static String onUpdateExpr(String definition) {
        if (definition == null) {
            return null;
        }
        Matcher matcher = ON_UPDATE_CLAUSE.matcher(definition);
        while (matcher.find()) {
            if (isOutsideQuotesInEveryMode(definition, matcher.start())) {
                return matcher.group(1).replaceAll("\\s+", "");
            }
        }
        return null;
    }

    public static ColumnSpec parse(String definition) {
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("column definition is blank");
        }
        if (definition.contains(";") || definition.contains("--") || definition.contains("/*")) {
            throw new IllegalArgumentException("column definition contains SQL statement or comment syntax");
        }
        String rest = definition.trim();
        rest = removeOutsideQuotes(AUTO_INCREMENT, rest);
        rest = removeOutsideQuotes(IDENTITY, rest);
        Matcher onUpdateClause = ON_UPDATE_CLAUSE.matcher(rest);
        while (onUpdateClause.find()) {
            if (isOutsideQuotesInEveryMode(rest, onUpdateClause.start())) {
                rest = (rest.substring(0, onUpdateClause.start()) + rest.substring(onUpdateClause.end())).trim();
                break;
            }
        }
        Matcher unsupportedOnUpdate = ON_UPDATE.matcher(rest);
        while (unsupportedOnUpdate.find()) {
            if (!isInsideSingleQuotes(rest, unsupportedOnUpdate.start(), false)
                    || !isInsideSingleQuotes(rest, unsupportedOnUpdate.start(), true)) {
                throw new IllegalArgumentException("unsupported or ambiguous ON UPDATE clause (a backslash before a"
                        + " quote reads differently with and without NO_BACKSLASH_ESCAPES): " + definition);
            }
        }
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
            } else {
                Matcher trailingNull = TRAILING_NULL.matcher(defaultExpr);
                if (trailingNull.find() && !isInsideSingleQuotes(defaultExpr, trailingNull.start())) {
                    defaultExpr = defaultExpr.substring(0, trailingNull.start()).trim();
                }
            }
        }
        Matcher nn = NOT_NULL.matcher(rest);
        if (nn.find()) {
            notNull = true;
            rest = (rest.substring(0, nn.start()) + rest.substring(nn.end())).trim();
        } else {
            rest = EXPLICIT_NULL.matcher(rest).replaceFirst("").trim();
        }
        Matcher tz = ZONED_TEMPORAL.matcher(rest);
        if (tz.matches()) {
            String leftover = tz.group(5) == null ? "" : tz.group(5).trim();
            boolean timestamp = tz.group(1).equalsIgnoreCase("TIMESTAMP");
            boolean with = tz.group(3).equalsIgnoreCase("WITH");
            boolean local = tz.group(4) != null;
            if (!leftover.isEmpty() || (local && (!with || !timestamp))) {
                throw new IllegalArgumentException("unparseable column type: " + definition);
            }
            String type = timestamp
                    ? (!with ? "TIMESTAMP" : local ? "TIMESTAMPLTZ" : "TIMESTAMPTZ")
                    : (with ? "TIMETZ" : "TIME");
            Integer precision = tz.group(2) == null ? null : Integer.parseInt(tz.group(2));
            return new ColumnSpec(type, precision, null, notNull, defaultExpr);
        }
        Matcher widthAttributes = INTEGER_WIDTH_ATTRIBUTES.matcher(rest);
        boolean widthRewritten = widthAttributes.matches();
        if (widthRewritten) {
            boolean zerofill = widthAttributes.group(1) == null;
            rest = (zerofill ? widthAttributes.group(4) + " UNSIGNED ZEROFILL(" : widthAttributes.group(1) + " UNSIGNED(")
                    + widthAttributes.group(zerofill ? 5 : 2).replaceAll("\\s+", "") + ")";
        }
        Matcher tm = TYPE_LEN.matcher(rest);
        // MySQL rejects an attribute before the length: INT UNSIGNED(10).
        if (!tm.matches() || (!widthRewritten && tm.group(2) != null
                && ATTRIBUTE_BEFORE_LENGTH.matcher(tm.group(1).trim()).matches())) {
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
        if (length != null && length == MAX_LENGTH && (!isVariableLength(normalized) || "VARBIT".equals(normalized))) {
            throw new IllegalArgumentException("MAX length is only valid for VARCHAR/NVARCHAR/VARBINARY: "
                    + definition);
        }
        if (length == null && isFixedLength(normalized)) {
            length = 1; // CHAR, NCHAR, and BINARY without a length mean length 1 on every engine.
        }
        if (scale != null && !isNumeric(normalized)) {
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
        // PostgreSQL stores B'101' as '101'::"bit" and X'A1' as '10100001'::"bit".
        if (d.matches("B'[01]*'")) {
            d = d.substring(1);
        } else if (d.matches("X'[0-9A-Fa-f]*'")) {
            StringBuilder bits = new StringBuilder("'");
            for (char digit : d.substring(2, d.length() - 1).toCharArray()) {
                String binary = Integer.toBinaryString(Character.digit(digit, 16));
                bits.append("0".repeat(4 - binary.length())).append(binary);
            }
            d = bits.append("'").toString();
        }
        // SQL Server CURRENT_TIMESTAMP is GETDATE(); PostgreSQL/MySQL CURRENT_TIMESTAMP is NOW().
        if (d.equals("CURRENT_TIMESTAMP") || d.equals("CURRENT_TIMESTAMP()") || d.equals("GETDATE()")
                || d.equals("NOW()")) {
            return "CURRENT_TIMESTAMP";
        }
        // MySQL reports DEFAULT (CURRENT_DATE) as curdate().
        if (d.equals("CURRENT_DATE") || d.equals("CURRENT_DATE()") || d.equals("CURDATE()")) {
            return "CURRENT_DATE";
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
        Matcher unsigned = UNSIGNED_SUFFIX.matcher(t);
        if (unsigned.matches()) {
            return normalizeType(unsigned.group(1))
                    + (unsigned.group(2).contains("ZEROFILL") ? " UNSIGNED ZEROFILL" : " UNSIGNED");
        }
        return switch (t) {
            case "NVARCHAR", "NVARCHAR2", "NATIONAL CHARACTER VARYING", "NATIONAL CHAR VARYING",
                 "NATIONAL VARCHAR", "NCHAR VARYING", "NCHAR VARCHAR" -> "NVARCHAR";
            case "CHARACTER VARYING", "VARCHAR", "VARCHAR2" -> "VARCHAR";
            case "CHARACTER", "CHAR", "BPCHAR" -> "CHAR";
            case "NCHAR", "NATIONAL CHARACTER", "NATIONAL CHAR" -> "NCHAR";
            case "INT", "INT4", "INTEGER" -> "INTEGER";
            case "INT8", "BIGINT" -> "BIGINT";
            case "INT2", "SMALLINT" -> "SMALLINT";
            // BIT(n) is a bit field on MySQL/PostgreSQL, not BOOLEAN (MySQL BOOLEAN is TINYINT(1)).
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "BIT VARYING", "VARBIT" -> "VARBIT";
            // DEC (every engine) and FIXED (MySQL/MariaDB) are stored as DECIMAL.
            case "DECIMAL", "NUMERIC", "NUMBER", "DEC", "FIXED" -> "NUMERIC";
            case "FLOAT4", "REAL" -> "REAL";
            case "FLOAT8", "DOUBLE PRECISION", "DOUBLE" -> "DOUBLE PRECISION";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> "TIMESTAMPTZ";
            // Oracle LOCAL TIME ZONE normalizes to the session zone and drops the offset.
            case "TIMESTAMP WITH LOCAL TIME ZONE" -> "TIMESTAMPLTZ";
            case "TIME WITH TIME ZONE", "TIMETZ" -> "TIMETZ";
            case "TIME WITHOUT TIME ZONE", "TIME" -> "TIME";
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
                || "VARBINARY".equals(normalizedType) || "VARBIT".equals(normalizedType);
    }

    /** Exact numerics whose precision and scale are read and compared, including MySQL {@code UNSIGNED}. */
    public static boolean isNumeric(String normalizedType) {
        return "NUMERIC".equals(normalizedType) || "NUMERIC UNSIGNED".equals(normalizedType)
                || "NUMERIC UNSIGNED ZEROFILL".equals(normalizedType);
    }

    /** Fixed-length types whose declared length is significant. */
    public static boolean isFixedLength(String normalizedType) {
        return "CHAR".equals(normalizedType) || "NCHAR".equals(normalizedType) || "BINARY".equals(normalizedType)
                || "BIT".equals(normalizedType);
    }

    /** Types whose live length is read and compared. */
    public static boolean hasLength(String normalizedType) {
        return isVariableLength(normalizedType) || isFixedLength(normalizedType);
    }

    /** Temporal types whose length field holds fractional-second precision. */
    public static boolean hasFractionalPrecision(String normalizedType) {
        return switch (normalizedType) {
            case "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMPLTZ", "TIME", "TIMETZ", "DATETIME", "DATETIME2",
                 "DATETIMEOFFSET" -> true;
            default -> false;
        };
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
        return isInsideSingleQuotes(text, index, false);
    }

    /**
     * Outside quotes both when a backslash escapes the next character (MySQL default) and when it
     * does not (NO_BACKSLASH_ESCAPES, other engines): {@code 'a\' ON UPDATE …'} is either reading.
     */
    static boolean isOutsideQuotesInEveryMode(String text, int index) {
        return !isInsideSingleQuotes(text, index, false) && !isInsideSingleQuotes(text, index, true);
    }

    private static boolean isInsideSingleQuotes(String text, int index, boolean backslashEscapes) {
        boolean inQuote = false;
        for (int i = 0; i < index; i++) {
            if (inQuote && backslashEscapes && text.charAt(i) == '\\') {
                i++;
                continue;
            }
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
