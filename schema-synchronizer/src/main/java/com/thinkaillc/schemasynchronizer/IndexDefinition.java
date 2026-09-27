// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed subset of CREATE INDEX: declarations, PostgreSQL {@code pg_get_indexdef}, and the forms
 * the catalog readers build. Names may be bare or quoted in the dialect's style (see
 * {@link SqlIdentifiers}); {@code name}, {@code table}, and quoted folded names inside the key
 * compare as their bare lower-case spelling, so {@code "order"} and {@code order} are the same index.
 */
record IndexDefinition(String name, String schema, String table, String structuralSql,
                       String predicateSql, String canonicalSql, boolean unique, String rawStructure,
                       String rawPredicate, String key) {
    private static final Pattern CREATE_INDEX_HEAD = Pattern.compile(
            "(?is)\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS(?![\\p{L}\\p{N}_$#@])\\s*)?");
    private static final Pattern ON_KEYWORD = Pattern.compile("(?is)\\s*ON(?![\\p{L}\\p{N}_$#@])\\s*");
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile("\\s*;?\\s*$");
    private static final Pattern USING_BTREE = Pattern.compile("(?i)^USING\\s+BTREE\\s+");
    private static final Pattern TEXT_ARRAY_CAST = Pattern.compile(
            "(?i)\\(ARRAY\\[((?:'(?:''|[^'])*'::text)(?:,'(?:''|[^'])*'::text)*)\\]\\)::text\\[\\]");
    private static final Pattern REDUNDANT_LITERAL_TEXT_CAST = Pattern.compile(
            "(?i)\\(('(?:''|[^'])*')::text\\)::text");
    private static final Pattern PLAIN_KEY = Pattern.compile(
            "^\\((?:[a-z_][a-z0-9_]*(?:\\(\\d+\\))?(?: desc)?)(?:,[a-z_][a-z0-9_]*(?:\\(\\d+\\))?(?: desc)?)*\\)$");
    private static final Pattern PLAIN_KEY_PART = Pattern.compile("([a-z_][a-z0-9_]*)((?:\\(\\d+\\))?)((?: desc)?)");

    static IndexDefinition parse(String sql) {
        return parse(sql, SqlIdentifiers.EXTENDED_MAX_LENGTH, DatabaseDialect.POSTGRESQL);
    }

    static IndexDefinition parse(String sql, DatabaseDialect dialect) {
        return parse(sql, SqlIdentifiers.EXTENDED_MAX_LENGTH, dialect);
    }

    static IndexDefinition parse(String sql, int maxIdentifierLength) {
        return parse(sql, maxIdentifierLength, DatabaseDialect.POSTGRESQL);
    }

    static IndexDefinition parse(String sql, int maxIdentifierLength, DatabaseDialect dialect) {
        return parse(sql, maxIdentifierLength, dialect, false);
    }

    /**
     * An index as a catalog reader rendered it: the name and table are exact catalog spellings
     * (quoted when the reader quoted them) and are not validated as declared identifiers.
     */
    static IndexDefinition parseLive(String sql, DatabaseDialect dialect) {
        return parse(sql, Integer.MAX_VALUE, dialect, true);
    }

    private static IndexDefinition parse(String sql, int maxIdentifierLength, DatabaseDialect dialect, boolean live) {
        String text = sql == null ? "" : sql;
        Matcher head = CREATE_INDEX_HEAD.matcher(text);
        if (!head.lookingAt()) {
            throw invalid(dialect);
        }
        SqlIdentifiers.Token nameToken = SqlIdentifiers.readToken(text, head.end(), dialect);
        if (nameToken == null) {
            throw invalid(dialect);
        }
        Matcher on = ON_KEYWORD.matcher(text).region(nameToken.end(), text.length());
        if (!on.lookingAt()) {
            throw invalid(dialect);
        }
        SqlIdentifiers.Token first = SqlIdentifiers.readToken(text, on.end(), dialect);
        if (first == null) {
            throw invalid(dialect);
        }
        SqlIdentifiers.Token schemaToken = null;
        SqlIdentifiers.Token tableToken = first;
        int afterTable = first.end();
        int dot = skipWhitespace(text, afterTable);
        if (dot < text.length() && text.charAt(dot) == '.') {
            schemaToken = first;
            tableToken = SqlIdentifiers.readToken(text, dot + 1, dialect);
            if (tableToken == null) {
                throw invalid(dialect);
            }
            afterTable = tableToken.end();
        }
        String rawTail = TRAILING_SEMICOLON.matcher(text.substring(afterTable)).replaceFirst("").trim();
        if (rawTail.isEmpty()) {
            throw invalid(dialect);
        }
        String unique = head.group(1) == null ? "" : "UNIQUE ";
        String name = live ? nameToken.value()
                : SqlIdentifiers.declaredName(nameToken, dialect, "index", maxIdentifierLength);
        String schema = schemaToken == null ? null : live ? schemaToken.value()
                : ChangeSetSchemaScope.canonical(SqlIdentifiers.requireIdentifierPreservingCase(
                        schemaToken.value(), "index schema", maxIdentifierLength), schemaToken.quoted(), dialect);
        String table = live ? tableToken.value()
                : SqlIdentifiers.declaredName(tableToken, dialect, "index table", maxIdentifierLength);
        int whereOffset = findTopLevelWhere(rawTail);
        String rawStructure = USING_BTREE.matcher((whereOffset < 0 ? rawTail : rawTail.substring(0, whereOffset))
                .trim()).replaceFirst("");
        String rawPredicate = whereOffset < 0 ? null : rawTail.substring(whereOffset + 5).trim();
        String structure = lowerCode(normalize(unquoteFolded(rawStructure, dialect)), dialect)
                .replaceAll("\\s+asc(?=[,)])", "");
        String predicate = rawPredicate == null ? null : normalize(unquoteFolded(rawPredicate, dialect));
        String structural = "CREATE " + unique + "INDEX " + name + " ON " + table + " " + structure;
        String canonical = structural + (predicate == null ? "" : " WHERE " + predicate);
        return new IndexDefinition(name, schema, table, structural, predicate, canonical, !unique.isEmpty(),
                rawStructure, rawPredicate, structure);
    }

    private static IllegalArgumentException invalid(DatabaseDialect dialect) {
        return new IllegalArgumentException("index definition must be CREATE [UNIQUE] INDEX name ON [schema.]table "
                + "(...) with plain or " + dialect.id() + "-quoted (" + SqlIdentifiers.openQuote(dialect)
                + "…) identifiers");
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /** The part after {@code ON table}, e.g. {@code (a,b DESC)} or {@code USING gin(tags)}. */
    String structure() {
        return key;
    }

    /** Same uniqueness and key; the name and table are matched by the caller under the backend's case rules. */
    boolean hasSameStructure(IndexDefinition other) {
        return other != null && unique == other.unique && key.equals(other.key);
    }

    boolean hasEquivalentPredicate(IndexDefinition other) {
        return other != null
                && canonicalPredicate(predicateSql).equals(canonicalPredicate(other.predicateSql));
    }

    /**
     * The statement that creates this index on {@code tableSql} (an already quoted table reference),
     * with the index name and each column of a plain key quoted. Other keys and predicates are
     * emitted as written.
     */
    String toSql(DatabaseDialect dialect, String tableSql) {
        String key = structure();
        String keySql;
        if (PLAIN_KEY.matcher(key).matches()) {
            List<String> parts = new ArrayList<>();
            for (String part : key.substring(1, key.length() - 1).split(",")) {
                Matcher item = PLAIN_KEY_PART.matcher(part);
                if (!item.matches()) {
                    throw new IllegalStateException("unreadable index key part: " + part);
                }
                parts.add(SqlIdentifiers.quote(dialect, item.group(1)) + item.group(2)
                        + (item.group(3).isEmpty() ? "" : " DESC"));
            }
            keySql = "(" + String.join(", ", parts) + ")";
        } else {
            keySql = rawStructure;
        }
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + (dialect.supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "")
                + SqlIdentifiers.quote(dialect, name) + " ON " + tableSql + " " + keySql
                + (rawPredicate == null ? "" : " WHERE " + rawPredicate);
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

    /**
     * Rewrites identifiers quoted in the dialect's style as bare lower-case names when the quoted
     * spelling is the folded one ({@code "order"} on PostgreSQL, {@code "ORDER"} on Oracle), or on
     * MySQL/MariaDB, where column names compare case-insensitively so {@code `UserId`} is the column
     * {@code userid}; any other spelling names a different object and stays quoted.
     */
    static String unquoteFolded(String text, DatabaseDialect dialect) {
        char open = SqlIdentifiers.openQuote(dialect);
        StringBuilder result = new StringBuilder(text.length());
        for (SqlLexer.Span span : SqlLexer.spans(text, SqlLexer.mode(dialect))) {
            String piece = text.substring(span.start(), span.end());
            if (span.kind() == SqlLexer.Kind.QUOTED_IDENT && piece.charAt(0) == open
                    && span.content().matches("[A-Za-z_][A-Za-z0-9_]*")
                    && (dialect.isMySqlFamily()
                    || span.content().equals(SqlIdentifiers.storedForm(dialect, span.content())))) {
                result.append(span.content().toLowerCase(Locale.ROOT));
            } else {
                result.append(piece);
            }
        }
        return result.toString();
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
                if (current == '\n' || current == '\r') lineComment = false;
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

    /** Lower-cases code; literals and identifiers quoted in the dialect's style keep their spelling. */
    private static String lowerCode(String sql, DatabaseDialect dialect) {
        StringBuilder result = new StringBuilder(sql.length());
        for (SqlLexer.Span span : SqlLexer.spans(sql, SqlLexer.mode(dialect))) {
            String piece = sql.substring(span.start(), span.end());
            result.append(span.kind() == SqlLexer.Kind.CODE ? piece.toLowerCase(Locale.ROOT) : piece);
        }
        return result.toString();
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .trim();
    }
}
