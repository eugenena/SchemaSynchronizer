// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/** Live column snapshot from JDBC / information_schema. */
record LiveColumn(
        String baseType,
        Integer length,
        Integer scale,
        boolean notNull,
        String defaultExpr
) {}
