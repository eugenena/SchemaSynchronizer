// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/** Live column snapshot from JDBC / information_schema. */
public record LiveColumn(
        String baseType,
        Integer length,
        Integer scale,
        boolean notNull,
        String defaultExpr
) {}
