package com.thinkai.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plans non-destructive ALTER COLUMN statements. Destructive / unsafe changes
 * go to {@link Plan#pendingSql()} and are never auto-executed by SchemaApplier.
 */
public final class NonDestructiveAlterPlanner {

    private NonDestructiveAlterPlanner() {}

    public record Plan(List<String> applySql, List<String> pendingSql) {}

    public static Plan plan(String table, String column, ColumnSpec target, LiveColumn live) {
        List<String> apply = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        String t = quoteIdent(table);
        String c = quoteIdent(column);

        String liveType = ColumnDefinitionParser.normalizeType(live.baseType());
        String targetType = target.baseType();

        TypeChange typeChange = classifyTypeChange(liveType, live.length(), targetType, target.length());
        switch (typeChange) {
            case SAME -> { /* no-op */ }
            case WIDEN -> apply.add(
                    "ALTER TABLE " + t + " ALTER COLUMN " + c + " TYPE " + formatType(targetType, target.length()));
            case NARROW, INCOMPATIBLE -> pending.add(
                    "ALTER TABLE " + t + " ALTER COLUMN " + c + " TYPE "
                            + formatType(targetType, target.length()) + "; -- pending: type change not auto-safe");
        }

        String liveDef = ColumnDefinitionParser.normalizeDefault(live.defaultExpr());
        String targetDef = ColumnDefinitionParser.normalizeDefault(target.defaultExpr());
        if (!Objects.equals(liveDef, targetDef)) {
            if (targetDef == null) {
                apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " DROP DEFAULT");
            } else {
                apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " SET DEFAULT " + target.defaultExpr().trim());
            }
        }

        if (live.notNull() && !target.notNull()) {
            apply.add("ALTER TABLE " + t + " ALTER COLUMN " + c + " DROP NOT NULL");
        } else if (!live.notNull() && target.notNull()) {
            pending.add(
                    "ALTER TABLE " + t + " ALTER COLUMN " + c
                            + " SET NOT NULL; -- pending: may fail if NULLs exist; backfill first");
        }

        return new Plan(List.copyOf(apply), List.copyOf(pending));
    }

    enum TypeChange { SAME, WIDEN, NARROW, INCOMPATIBLE }

    static TypeChange classifyTypeChange(
            String liveType, Integer liveLen, String targetType, Integer targetLen) {
        if (liveType.equals(targetType)) {
            if ("VARCHAR".equals(liveType)) {
                int liveL = liveLen == null ? Integer.MAX_VALUE : liveLen;
                int targetL = targetLen == null ? Integer.MAX_VALUE : targetLen;
                if (targetL > liveL) return TypeChange.WIDEN;
                if (targetL < liveL) return TypeChange.NARROW;
                return TypeChange.SAME;
            }
            return TypeChange.SAME;
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

    static String formatType(String baseType, Integer length) {
        if ("VARCHAR".equals(baseType) && length != null && length > 0 && length < 10_000) {
            return "VARCHAR(" + length + ")";
        }
        return baseType;
    }

    static String quoteIdent(String name) {
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid identifier: " + name);
        }
        return name.toLowerCase();
    }
}
