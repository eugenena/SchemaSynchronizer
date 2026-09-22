package com.thinkai.schema;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed subset of PostgreSQL CREATE INDEX emitted by pg_indexes. */
record IndexDefinition(String name, String schema, String table, String canonicalSql) {
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
        String tail = matcher.group(5).trim()
                .replaceFirst("(?i)^USING\\s+BTREE\\s+", "")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1");
        String canonical = "CREATE " + unique + "INDEX " + name + " ON " + table + " " + tail;
        return new IndexDefinition(name, schema, table, canonical);
    }
}
