// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schema-synchronizer")
public class SchemaSynchronizerProperties {
    private boolean enabled = true;
    private String resource = "/schema-definition.json";
    private String schema = "public";
    private String historyTable = "schema_synchronizer_history";
    private long advisoryLockId = 7_249_031_147L;
    private boolean dryRun;
    private boolean failOnPending = true;
    private boolean requireDefinition = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getHistoryTable() { return historyTable; }
    public void setHistoryTable(String historyTable) { this.historyTable = historyTable; }
    public long getAdvisoryLockId() { return advisoryLockId; }
    public void setAdvisoryLockId(long advisoryLockId) { this.advisoryLockId = advisoryLockId; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isFailOnPending() { return failOnPending; }
    public void setFailOnPending(boolean failOnPending) { this.failOnPending = failOnPending; }
    public boolean isRequireDefinition() { return requireDefinition; }
    public void setRequireDefinition(boolean requireDefinition) { this.requireDefinition = requireDefinition; }

    SchemaSynchronizerOptions toOptions() {
        return new SchemaSynchronizerOptions(schema, historyTable, advisoryLockId, dryRun, failOnPending,
                requireDefinition);
    }
}
