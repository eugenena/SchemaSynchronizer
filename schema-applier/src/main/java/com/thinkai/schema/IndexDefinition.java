package com.thinkai.schema;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed subset of PostgreSQL CREATE INDEX emitted by pg_indexes. */
record IndexDefinition(String name, String schema, String table, String structuralSql,
                       String predicateSql, String canonicalSql) {
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "([a-zA-Z_][a-zA-Z0-9_]*)\\s+ON\\s+"
                    + "(?:(?:([a-zA-Z_][a-zA-Z0-9_]*)\\.)?([a-zA-Z_][a-zA-Z0-9_]*))"
                    + "\\s+(.+?)\\s*;?\\s*$");

    static IndexDefinition parse(String sql) {
        Matcher matcher = CREATE_INDEX.matcher(sql == null ? "" : sql);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("index definition must use unquoted PostgreSQL identifiers");
        }
        String unique = matcher.group(1) == null ? "" : "UNIQUE ";
        String name = SqlIdentifiers.requireIdentifier(matcher.group(2), "index");
        String schema = matcher.group(3) == null ? null
                : SqlIdentifiers.requireIdentifier(matcher.group(3), "index schema");
        String table = SqlIdentifiers.requireIdentifier(matcher.group(4), "index table");
        String rawTail = matcher.group(5).trim();
        String[] tailParts = rawTail.split("(?i)\\s+WHERE\\s+", 2);
        String structure = normalize(tailParts[0].trim()
                .replaceFirst("(?i)^USING\\s+BTREE\\s+", ""));
        String predicate = tailParts.length == 1 ? null : normalize(tailParts[1]);
        String structural = "CREATE " + unique + "INDEX " + name + " ON " + table + " " + structure;
        String canonical = structural + (predicate == null ? "" : " WHERE " + predicate);
        return new IndexDefinition(name, schema, table, structural, predicate, canonical);
    }

    boolean hasSameStructure(IndexDefinition other) {
        return other != null && structuralSql.equals(other.structuralSql);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .trim();
    }
}
