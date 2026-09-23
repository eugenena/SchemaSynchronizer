package io.github.eugenena.schemasynchronizer;

/** Live column snapshot from JDBC / information_schema. */
public record LiveColumn(
        String baseType,
        Integer length,
        Integer scale,
        boolean notNull,
        String defaultExpr
) {}
