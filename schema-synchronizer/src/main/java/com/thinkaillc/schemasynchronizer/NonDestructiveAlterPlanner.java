// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Plans non-destructive ALTER COLUMN statements. Destructive / unsafe changes
 * go to {@link Plan#pendingSql()} and are never auto-executed by SchemaSynchronizer.
 */
final class NonDestructiveAlterPlanner {

    private NonDestructiveAlterPlanner() {}

    /** Auto-safe operations in {@link Plan#applySql()}, for dialect rewrites that must not parse SQL text. */
    enum Op { WIDEN_TYPE, SET_DEFAULT, DROP_DEFAULT, DROP_NOT_NULL }

    record Plan(List<String> applySql, List<String> pendingSql, Set<Op> applyOps) {
        Plan {
            applySql = List.copyOf(applySql);
            pendingSql = List.copyOf(pendingSql);
            applyOps = applyOps.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(applyOps));
        }

        Plan(List<String> applySql, List<String> pendingSql) {
            this(applySql, pendingSql, Set.of());
        }
    }

    /**
     * @param t the table as emitted SQL (already quoted, see {@link SqlIdentifiers})
     * @param c the column as emitted SQL (already quoted)
     */
    static Plan plan(String t, String c, ColumnSpec target, LiveColumn live) {
        List<String> apply = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Set<Op> ops = EnumSet.noneOf(Op.class);

        String liveType = ColumnDefinitionParser.normalizeType(live.baseType());
        String targetType = target.baseType();

        TypeChange typeChange = classifyTypeChange(liveType, live.length(), live.scale(),
                targetType, target.length(), target.scale());
        switch (typeChange) {
            case SAME -> { /* no-op */ }
            case WIDEN -> {
                apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " TYPE "
                        + formatType(targetType, target.length(), target.scale()));
                ops.add(Op.WIDEN_TYPE);
            }
            case NARROW, INCOMPATIBLE -> pending.add(
                    "ALTER TABLE " + t + " ALTER COLUMN " + c + " TYPE "
                            + formatType(targetType, target.length(), target.scale())
                            + "; -- pending: type change not auto-safe");
        }

        String liveDef = ColumnDefinitionParser.normalizeDefault(live.defaultExpr());
        String targetDef = ColumnDefinitionParser.normalizeDefault(target.defaultExpr());
        if (!Objects.equals(liveDef, targetDef)) {
            if (targetDef == null) {
                apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " DROP DEFAULT");
                ops.add(Op.DROP_DEFAULT);
            } else {
                apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " SET DEFAULT " + target.defaultExpr().trim());
                ops.add(Op.SET_DEFAULT);
            }
        }

        if (live.notNull() && !target.notNull()) {
            apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " DROP NOT NULL");
            ops.add(Op.DROP_NOT_NULL);
        } else if (!live.notNull() && target.notNull()) {
            pending.add(
                    "ALTER TABLE " + t + " ALTER COLUMN " + c
                            + " SET NOT NULL; -- pending: may fail if NULLs exist; backfill first");
        }

        return new Plan(apply, pending, ops);
    }

    enum TypeChange { SAME, WIDEN, NARROW, INCOMPATIBLE }

    static TypeChange classifyTypeChange(
            String liveType, Integer liveLen, Integer liveScale,
            String targetType, Integer targetLen, Integer targetScale) {
        if (liveType.equals(targetType)) {
            if ("BINARY".equals(liveType) || "BIT".equals(liveType)) {
                // Resizing fixed-length binary or a bit string re-pads every stored value.
                return java.util.Objects.equals(liveLen, targetLen) ? TypeChange.SAME : TypeChange.INCOMPATIBLE;
            }
            if (ColumnDefinitionParser.hasLength(liveType)) {
                int liveL = ColumnDefinitionParser.effectiveLength(liveLen);
                int targetL = ColumnDefinitionParser.effectiveLength(targetLen);
                if (targetL > liveL) return TypeChange.WIDEN;
                if (targetL < liveL) return TypeChange.NARROW;
                return TypeChange.SAME;
            }
            if (ColumnDefinitionParser.isNumeric(liveType)) {
                if (liveLen == null) return targetLen == null ? TypeChange.SAME : TypeChange.NARROW;
                if (targetLen == null) return TypeChange.WIDEN;
                int liveFraction = liveScale == null ? 0 : liveScale;
                int targetFraction = targetScale == null ? 0 : targetScale;
                int liveInteger = liveLen - liveFraction;
                int targetInteger = targetLen - targetFraction;
                if (targetInteger >= liveInteger && targetFraction >= liveFraction) {
                    return targetInteger == liveInteger && targetFraction == liveFraction
                            ? TypeChange.SAME : TypeChange.WIDEN;
                }
                return TypeChange.NARROW;
            }
            if (ColumnDefinitionParser.hasFractionalPrecision(liveType)) {
                // Unknown precision on either side is not comparable; any change rounds or rewrites values.
                return liveLen == null || targetLen == null || liveLen.equals(targetLen)
                        ? TypeChange.SAME : TypeChange.INCOMPATIBLE;
            }
            // Only Oracle reads a live FLOAT precision (binary digits); a higher one keeps every stored value.
            if ("FLOAT".equals(liveType) && liveLen != null && targetLen != null) {
                return targetLen > liveLen ? TypeChange.WIDEN
                        : targetLen < liveLen ? TypeChange.NARROW : TypeChange.SAME;
            }
            if ("VECTOR".equals(liveType)) {
                return java.util.Objects.equals(liveLen, targetLen)
                        ? TypeChange.SAME : TypeChange.INCOMPATIBLE;
            }
            return TypeChange.SAME;
        }
        if (("CHAR".equals(liveType) && "VARCHAR".equals(targetType))
                || ("NCHAR".equals(liveType) && "NVARCHAR".equals(targetType))) {
            int liveL = ColumnDefinitionParser.effectiveLength(liveLen);
            int targetL = ColumnDefinitionParser.effectiveLength(targetLen);
            return targetL >= liveL ? TypeChange.WIDEN : TypeChange.NARROW;
        }
        // VARCHAR → TEXT
        if ("VARCHAR".equals(liveType) && "TEXT".equals(targetType)) {
            return TypeChange.WIDEN;
        }
        if ("TEXT".equals(liveType) && "VARCHAR".equals(targetType)) {
            return TypeChange.NARROW;
        }
        int liveInt = ColumnDefinitionParser.integerRank(liveType);
        int targetInt = ColumnDefinitionParser.integerRank(targetType);
        if (liveInt > 0 && targetInt > 0) {
            if (targetInt > liveInt) return TypeChange.WIDEN;
            if (targetInt < liveInt) return TypeChange.NARROW;
            return TypeChange.SAME;
        }
        int liveFloat = ColumnDefinitionParser.floatRank(liveType);
        int targetFloat = ColumnDefinitionParser.floatRank(targetType);
        if (liveFloat > 0 && targetFloat > 0) {
            if (targetFloat > liveFloat) return TypeChange.WIDEN;
            if (targetFloat < liveFloat) return TypeChange.NARROW;
            return TypeChange.SAME;
        }
        return TypeChange.INCOMPATIBLE;
    }

    static String formatType(String baseType, Integer length, Integer scale) {
        if (ColumnDefinitionParser.isNumeric(baseType) && length != null && length > 0) {
            return (scale == null ? "NUMERIC(" + length + ")" : "NUMERIC(" + length + "," + scale + ")")
                    + baseType.substring("NUMERIC".length());
        }
        if (ColumnDefinitionParser.hasFractionalPrecision(baseType) && length != null && length >= 0) {
            return baseType + "(" + length + ")";
        }
        if ("VECTOR".equals(baseType) && length != null && length > 0) {
            return "VECTOR(" + length + ")";
        }
        if (ColumnDefinitionParser.hasLength(baseType) && length != null) {
            // CHAR(MAX) is invalid on SQL Server; only variable-length types use (MAX).
            if (length == ColumnDefinitionParser.MAX_LENGTH && ColumnDefinitionParser.isVariableLength(baseType)) {
                return baseType + "(MAX)";
            }
            if (length > 0 && length < 10_000) {
                return baseType + "(" + length + ")";
            }
        }
        return baseType;
    }
}
