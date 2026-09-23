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

    private static final Pattern DEFAULT = Pattern.compile(
            "\\s+DEFAULT\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_NULL = Pattern.compile(
            "\\s+NOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_INCREMENT = Pattern.compile(
            "\\s+AUTO_INCREMENT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_LEN = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_\\s]*?)(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_CAST = Pattern.compile("::[A-Za-z][A-Za-z0-9_\\s]*$");

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
        String defaultExpr = null;
        Matcher defM = DEFAULT.matcher(rest);
        if (defM.find()) {
            defaultExpr = defM.group(1).trim();
            rest = rest.substring(0, defM.start()).trim();
        }
        boolean notNull = false;
        Matcher nn = NOT_NULL.matcher(rest);
        if (nn.find()) {
            notNull = true;
            rest = rest.substring(0, nn.start()).trim();
        }
        Matcher tm = TYPE_LEN.matcher(rest);
        if (!tm.matches()) {
            throw new IllegalArgumentException("unparseable column type: " + definition);
        }
        String rawType = tm.group(1).trim();
        Integer length = tm.group(2) != null ? Integer.parseInt(tm.group(2)) : null;
        Integer scale = tm.group(3) != null ? Integer.parseInt(tm.group(3)) : null;
        if (scale != null && !"NUMERIC".equals(normalizeType(rawType))) {
            throw new IllegalArgumentException("scale is supported only for NUMERIC: " + definition);
        }
        if (scale != null && scale > length) {
            throw new IllegalArgumentException("NUMERIC scale exceeds precision: " + definition);
        }
        return new ColumnSpec(normalizeType(rawType), length, scale, notNull, defaultExpr);
    }

    public static String normalizeDefault(String defaultExpr) {
        if (defaultExpr == null) {
            return null;
        }
        String d = defaultExpr.trim();
        Matcher cast = PG_CAST.matcher(d);
        if (cast.find()) {
            d = d.substring(0, cast.start()).trim();
        }
        if (d.equalsIgnoreCase("CURRENT_TIMESTAMP") || d.equalsIgnoreCase("CURRENT_TIMESTAMP()")) {
            return "CURRENT_TIMESTAMP";
        }
        return d;
    }

    public static String normalizeType(String typeName) {
        if (typeName == null) {
            return "";
        }
        String t = typeName.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (t) {
            case "CHARACTER VARYING", "VARCHAR" -> "VARCHAR";
            case "CHARACTER", "CHAR", "BPCHAR" -> "CHAR";
            case "INT", "INT4", "INTEGER" -> "INTEGER";
            case "INT8", "BIGINT" -> "BIGINT";
            case "INT2", "SMALLINT" -> "SMALLINT";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "DECIMAL", "NUMERIC" -> "NUMERIC";
            case "FLOAT4", "REAL" -> "REAL";
            case "FLOAT8", "DOUBLE PRECISION", "DOUBLE" -> "DOUBLE PRECISION";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> "TIMESTAMPTZ";
            case "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP" -> "TIMESTAMP";
            case "BIGSERIAL" -> "BIGINT"; // compare as bigint for widen checks
            case "SERIAL" -> "INTEGER";
            default -> t;
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
}
