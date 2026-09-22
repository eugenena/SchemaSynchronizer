package com.thinkai.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Target schema state loaded from {@code schema-definition.json}.
 *
 * <p>SchemaApplier applies non-destructive diffs automatically; destructive
 * changes are logged as pending manual SQL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaDefinition(Map<String, TableDef> tables) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableDef(String createSql, List<ColumnDef> columns, List<String> indexes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnDef(String name, String definition) {}
}
