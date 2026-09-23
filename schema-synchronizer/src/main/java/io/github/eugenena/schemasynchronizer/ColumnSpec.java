// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package io.github.eugenena.schemasynchronizer;

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
