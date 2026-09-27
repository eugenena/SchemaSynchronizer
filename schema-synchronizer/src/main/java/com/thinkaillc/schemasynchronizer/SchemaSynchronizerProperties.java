// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot binding for the {@code schema-synchronizer.*} properties; see {@link SchemaSynchronizerOptions}
 * for the meaning of each option.
 */
@ConfigurationProperties("schema-synchronizer")
public class SchemaSynchronizerProperties {
    /** Runs the synchronizer at startup. Off unless set to {@code true} (opt-in since 2.0.0). */
    private boolean enabled = false;
    /** Classpath location of the schema definition JSON. */
    private String resource = "/schema-definition.json";
    /** Schema (MySQL/MariaDB: database; Oracle: user; SQL Server: schema in the current database) to synchronize. */
    private String schema = "public";
    /** Name of the change-set history table, created in {@link #schema}. */
    private String historyTable = "schema_synchronizer_history";
    /** Legacy lock key, still acquired alongside the (schema, history table) lock so 1.x and 2.x instances exclude each other. */
    private long advisoryLockId = 7_249_031_147L;
    /** Computes the plan without applying it. */
    private boolean dryRun;
    /** Fails startup when changes remain that need manual review. */
    private boolean failOnPending = true;
    /** Fails startup when the definition resource is missing. */
    private boolean requireDefinition = true;

    /** Creates properties holding the defaults. */
    public SchemaSynchronizerProperties() {
    }

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
