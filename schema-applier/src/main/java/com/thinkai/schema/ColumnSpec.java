package com.thinkai.schema;

/**
 * Parsed column definition from {@code schema-definition.json} {@code definition} strings.
 */
public record ColumnSpec(
        String baseType,
        Integer length,
        Integer scale,
        boolean notNull,
        String defaultExpr
) {}
