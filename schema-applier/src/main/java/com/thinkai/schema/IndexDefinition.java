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
    private static final Pattern TEXT_ARRAY_CAST = Pattern.compile(
            "(?i)\\(ARRAY\\[((?:'(?:''|[^'])*'::text)(?:,'(?:''|[^'])*'::text)*)\\]\\)::text\\[\\]");
    private static final Pattern REDUNDANT_LITERAL_TEXT_CAST = Pattern.compile(
            "(?i)\\(('(?:''|[^'])*')::text\\)::text");

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
        int whereOffset = findTopLevelWhere(rawTail);
        String rawStructure = whereOffset < 0 ? rawTail : rawTail.substring(0, whereOffset);
        String rawPredicate = whereOffset < 0 ? null : rawTail.substring(whereOffset + 5);
        String structure = normalize(rawStructure.trim()
                .replaceFirst("(?i)^USING\\s+BTREE\\s+", ""));
        String predicate = rawPredicate == null ? null : normalize(rawPredicate);
        String structural = "CREATE " + unique + "INDEX " + name + " ON " + table + " " + structure;
        String canonical = structural + (predicate == null ? "" : " WHERE " + predicate);
        return new IndexDefinition(name, schema, table, structural, predicate, canonical);
    }

    boolean hasSameStructure(IndexDefinition other) {
        return other != null && structuralSql.equals(other.structuralSql);
    }

    boolean hasEquivalentPredicate(IndexDefinition other) {
        return other != null
                && canonicalPredicate(predicateSql).equals(canonicalPredicate(other.predicateSql));
    }

    private static String canonicalPredicate(String predicate) {
        if (predicate == null) return "";
        String result = predicate
                .replaceAll("(?i)::character varying", "::text")
                .replaceAll("(?i)::varchar", "::text");
        String previous;
        do {
            previous = result;
            result = REDUNDANT_LITERAL_TEXT_CAST.matcher(result).replaceAll("$1::text");
        } while (!result.equals(previous));
        return TEXT_ARRAY_CAST.matcher(result).replaceAll("ARRAY[$1]");
    }

    private static int findTopLevelWhere(String sql) {
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        String dollarTag = null;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (dollarTag != null) {
                if (sql.startsWith(dollarTag, index)) {
                    index += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (singleQuoted) {
                if (current == '\'' && next == '\'') index++;
                else if (current == '\'') singleQuoted = false;
                continue;
            }
            if (doubleQuoted) {
                if (current == '"' && next == '"') index++;
                else if (current == '"') doubleQuoted = false;
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '$') {
                int end = sql.indexOf('$', index + 1);
                if (end >= 0) {
                    String candidate = sql.substring(index, end + 1);
                    if (candidate.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")) {
                        dollarTag = candidate;
                        index = end;
                    }
                }
            } else if (current == '(' || current == '[') {
                depth++;
            } else if (current == ')' || current == ']') {
                depth--;
            } else if (depth == 0 && sql.regionMatches(true, index, "WHERE", 0, 5)
                    && (index == 0 || !Character.isJavaIdentifierPart(sql.charAt(index - 1)))
                    && (index + 5 == sql.length()
                    || !Character.isJavaIdentifierPart(sql.charAt(index + 5)))) {
                return index;
            }
        }
        return -1;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .trim();
    }
}
