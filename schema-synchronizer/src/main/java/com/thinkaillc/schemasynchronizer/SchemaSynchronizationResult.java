// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.List;

/** Observable result for deployment logs, dry runs, and tests. */
public record SchemaSynchronizationResult(
        int changeSetsApplied,
        int tablesCreated,
        int columnsAdded,
        int columnsAltered,
        List<String> plannedSql,
        List<String> pendingSql
) {
    public boolean changed() {
        return changeSetsApplied + tablesCreated + columnsAdded + columnsAltered > 0;
    }
}
