package com.thinkai.schema;

/** Live column snapshot from JDBC / information_schema. */
public record LiveColumn(
        String baseType,
        Integer length,
        boolean notNull,
        String defaultExpr
) {}
