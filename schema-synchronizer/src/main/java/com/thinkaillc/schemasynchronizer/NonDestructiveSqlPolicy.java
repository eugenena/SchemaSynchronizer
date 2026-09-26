// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed policy for versioned custom SQL.
 *
 * <p>This is a guardrail that keeps reviewed change sets additive; it is not a security
 * boundary against hostile SQL authors. Methods without a {@link DatabaseDialect} lex SQL
 * with PostgreSQL rules.
 */
public final class NonDestructiveSqlPolicy {
    private static final List<Pattern> FORBIDDEN = List.of(
            token("DROP"),
            token("TRUNCATE"),
            token("EXEC"),
            Pattern.compile("(?is)\\bEXECUTE\\b(?!\\s+(?:FUNCTION|PROCEDURE|ON)\\b)"),
            token("EXECUTE\\s+IMMEDIATE"),
            token("SP_EXECUTESQL"),
            token("PREPARE"),
            token("OPENQUERY"),
            token("OPENROWSET"),
            token("OPENDATASOURCE"),
            token("DBMS_SQL"),
            token("XP_CMDSHELL"),
            Pattern.compile("(?is)\\bDBLINK\\w*\\s*\\("),
            token("OUTFILE"),
            token("DUMPFILE"),
            Pattern.compile("(?is)\\b(?:PG_TERMINATE_BACKEND|PG_CANCEL_BACKEND|PG_DROP_REPLICATION_SLOT"
                    + "|PG_RELOAD_CONF|PG_ROTATE_LOGFILE|PG_PROMOTE|PG_READ_\\w+|PG_WRITE_\\w+|PG_FILE_\\w+"
                    + "|LO_IMPORT|LO_EXPORT|LO_UNLINK|LOAD_FILE)\\s*\\(")
    );

    private static final Pattern DELETE = Pattern.compile("(?i)\\bDELETE\\b");

    /** Keywords that make {@code DELETE} an event or privilege name rather than a statement. */
    private static final Set<String> DELETE_EVENT_PREDECESSORS =
            Set.of("ON", "BEFORE", "AFTER", "FOR", "OR", "OF", ",", "GRANT");

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?s)^ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?\\S+\\s+(.*?)\\s*;?\\s*$");

    private static final List<Pattern> ALTER_TABLE_ACTIONS = List.of(
            Pattern.compile("(?s)^ADD\\b.*"),
            Pattern.compile("(?s)^WITH\\s+(?:NO)?CHECK\\s+ADD\\b.*"),
            Pattern.compile("(?s)^ALTER\\s+(?:COLUMN\\s+)?\\S+\\s+SET\\s+(?:DEFAULT\\b.*|NOT\\s+NULL)$"),
            Pattern.compile("^VALIDATE\\s+CONSTRAINT\\s+\\S+$"),
            Pattern.compile("^(?:ALGORITHM|LOCK)\\s*=?\\s*\\w+$")
    );

    private static final Pattern ORACLE_MODIFY = Pattern.compile("(?s)^MODIFY\\s*\\((.*)\\)$");
    private static final Pattern ORACLE_MODIFY_ITEM =
            Pattern.compile("(?s)^\\S+\\s+(?:DEFAULT\\b.*|NULL|NOT\\s+NULL)$");

    private static final Pattern GRANT_OBJECT = Pattern.compile("(?s)^GRANT\\b.*?\\bON\\b.*\\bTO\\b.*");
    private static final Pattern GRANT_NON_OBJECT_TARGET = Pattern.compile(
            "(?s)\\bON\\s*(?:LOGIN|SERVER|ENDPOINT|CERTIFICATE|ASYMMETRIC\\s+KEY|SYMMETRIC\\s+KEY"
                    + "|AVAILABILITY\\s+GROUP|DATABASE|TABLESPACE|FOREIGN|LANGUAGE|PARAMETER"
                    + "|LARGE\\s+OBJECT|USER|ROLE|APPLICATION\\s+ROLE|DIRECTORY|ASSEMBLY|EDITION|JAVA)\\b"
                    + "|\\bON\\s+(?:SYS\\s*\\.\\s*)?(?:UTL_\\w+|DBMS_\\w+)\\b"
                    + "|\\bPROXY\\b|\\bADMIN\\s+OPTION\\b");

    /** Server, database, schema, and system-event triggers act outside the table being changed. */
    private static final Pattern SERVER_OR_DATABASE_TRIGGER = Pattern.compile(
            "(?s)\\bON\\s+(?:ALL\\s+SERVER|(?:PLUGGABLE\\s+)?DATABASE|(?:[\\w$#\"]+\\s*\\.\\s*)?SCHEMA)\\b"
                    + "|\\b(?:LOGON|LOGOFF|STARTUP|SHUTDOWN|SERVERERROR|SUSPEND|DB_ROLE_CHANGE|CLONE|UNPLUG"
                    + "|SET\\s+CONTAINER)\\b");

    /** FK referential actions, whose SET/CASCADE keywords are not table-level ALTER clauses. */
    private static final Pattern REFERENTIAL_ACTION = Pattern.compile(
            "(?s)\\bON\\s+(?:DELETE|UPDATE)\\s+(?:SET\\s+(?:NULL|DEFAULT)|CASCADE|RESTRICT|NO\\s+ACTION)\\b");

    /** Oracle clauses that may follow {@code ADD (...)} without a comma and change or destroy data. */
    private static final Pattern TRAILING_ALTER_CLAUSE = Pattern.compile(
            "(?s)\\b(?:RENAME|MODIFY|SET\\s+UNUSED|MOVE|SHRINK|EXCHANGE|SPLIT|MERGE|COALESCE"
                    + "|DISABLE\\s+(?:ALL\\s+TRIGGERS|TABLE\\s+LOCK|PRIMARY\\s+KEY|UNIQUE|CONSTRAINT))\\b");

    /** DDL, privilege, and rename statements inside routine or trigger bodies. */
    private static final Pattern BODY_DDL = Pattern.compile(
            "(?s)\\b(?:ALTER|RENAME|GRANT|REVOKE|CREATE|COMMENT\\s+ON)\\b");

    private NonDestructiveSqlPolicy() {}

    public static void requireSafe(String sql) {
        requireSafe(sql, DatabaseDialect.POSTGRESQL);
    }

    public static void requireSafe(String sql, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("schema change statement is blank");
        }
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        String normalized = executableSql(sql, mode).toUpperCase(Locale.ROOT);
        requireSingleStatement(sql, mode, "schema change SQL");
        requireNoForbiddenTokens(sql, mode);
        if (!matchesAllowedStatement(normalized)) {
            throw new IllegalArgumentException("unsupported schema change SQL: " + summarize(sql));
        }
    }

    public static void requireReadOnlyVerification(String sql) {
        requireReadOnlyVerification(sql, DatabaseDialect.POSTGRESQL);
    }

    public static void requireReadOnlyVerification(String sql, DatabaseDialect dialect) {
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        String normalized = sql == null ? "" : executableSql(sql, mode).toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?s)^(SELECT|WITH)\\b.*")) {
            throw new IllegalArgumentException("verification SQL must be a SELECT or WITH query");
        }
        requireSingleStatement(sql, mode, "verification SQL");
        requireNoForbiddenTokens(sql, mode);
        if (normalized.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*")) {
            throw new IllegalArgumentException("verification SQL must not contain a data-modifying statement");
        }
        String unquoted = SqlLexer.mask(sql, mode, true, false).toUpperCase(Locale.ROOT)
                .replaceAll("[\"`\\[\\]]", "");
        if (VERIFICATION_SIDE_EFFECT.matcher(normalized).find() || VERIFICATION_SIDE_EFFECT.matcher(unquoted).find()) {
            throw new IllegalArgumentException("verification SQL must not write, lock, or advance sequences: "
                    + summarize(sql));
        }
    }

    /** Covers SELECT INTO / INTO OUTFILE, row locks, lock functions, and sequence advancement. */
    private static final Pattern VERIFICATION_SIDE_EFFECT = Pattern.compile(
            "(?s)\\bINTO\\b|\\bOUTFILE\\b|\\bDUMPFILE\\b"
                    + "|\\bFOR\\s+(?:NO\\s+KEY\\s+)?UPDATE\\b|\\bFOR\\s+(?:KEY\\s+)?SHARE\\b"
                    + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b|\\b(?:UPDLOCK|XLOCK|TABLOCKX?|HOLDLOCK)\\b"
                    + "|\\b(?:SETVAL|NEXTVAL|GET_LOCK|RELEASE_LOCK|RELEASE_ALL_LOCKS|IS_FREE_LOCK"
                    + "|PG_(?:TRY_)?ADVISORY\\w*|SP_GETAPPLOCK|SP_RELEASEAPPLOCK|LO_\\w+"
                    + "|PG_READ_\\w+|PG_TERMINATE_BACKEND|PG_CANCEL_BACKEND|LOAD_FILE)\\s*\\("
                    + "|\\bDBMS_LOCK\\b|\\.\\s*NEXTVAL\\b|\\bNEXT\\s+VALUE\\s+FOR\\b");

    public static void requireCreateTable(String sql) {
        requireCreateTable(sql, DatabaseDialect.POSTGRESQL);
    }

    public static void requireCreateTable(String sql, DatabaseDialect dialect) {
        requireSafe(sql, dialect);
        if (!executableSql(sql, SqlLexer.mode(dialect)).toUpperCase(Locale.ROOT)
                .matches("(?s)^CREATE\\s+TABLE\\b.*")) {
            throw new IllegalArgumentException("table createSql must contain one CREATE TABLE statement");
        }
    }

    public static void requireCreateIndex(String sql) {
        requireCreateIndex(sql, true);
    }

    static void requireCreateIndex(String sql, boolean requireIfNotExists) {
        requireCreateIndex(sql, requireIfNotExists, DatabaseDialect.POSTGRESQL);
    }

    static void requireCreateIndex(String sql, boolean requireIfNotExists, DatabaseDialect dialect) {
        requireSafe(sql, dialect);
        String executable = executableSql(sql, SqlLexer.mode(dialect)).toUpperCase(Locale.ROOT);
        if (!executable.matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*")) {
            throw new IllegalArgumentException("index definition must contain one CREATE INDEX statement");
        }
        if (requireIfNotExists && !executable.matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\b.*")) {
            throw new IllegalArgumentException("index definition must use IF NOT EXISTS");
        }
        IndexDefinition.parse(sql);
    }

    private static void requireSingleStatement(String sql, SqlLexer.Mode mode, String label) {
        List<SqlLexer.Span> spans = SqlLexer.spans(sql, mode);
        boolean terminated = false;
        for (SqlLexer.Span span : spans) {
            if (span.kind() == SqlLexer.Kind.COMMENT) {
                continue;
            }
            if (terminated && !(span.kind() == SqlLexer.Kind.CODE && span.content().isBlank())) {
                throw new IllegalArgumentException(label + " must contain exactly one statement");
            }
            if (span.kind() == SqlLexer.Kind.CODE) {
                int semicolon = span.content().indexOf(';');
                if (semicolon >= 0) {
                    if (!span.content().substring(semicolon + 1).isBlank()) {
                        throw new IllegalArgumentException(label + " must contain exactly one statement");
                    }
                    terminated = true;
                }
            }
        }
    }

    private static void requireNoForbiddenTokens(String sql, SqlLexer.Mode mode) {
        String normalized = executableSql(sql, mode).toUpperCase(Locale.ROOT);
        rejectForbidden(normalized, sql);
        // CREATE FUNCTION / TRIGGER / PROCEDURE bodies may be dollar-quoted or
        // string-quoted; scan every literal's unescaped content as potential code.
        Matcher routine = ROUTINE_HEAD.matcher(normalized);
        if (routine.lookingAt()) {
            rejectBodyDdl(normalized.substring(routine.end()), sql);
            for (String literal : SqlLexer.literals(sql, mode)) {
                String body = SqlLexer.bodyAsCode(literal, mode, false).toUpperCase(Locale.ROOT);
                rejectForbidden(body, sql);
                rejectBodyDdl(body, sql);
            }
        }
    }

    private static final Pattern ROUTINE_HEAD =
            Pattern.compile("(?s)CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FUNCTION|TRIGGER|PROCEDURE)\\b");

    private static void rejectBodyDdl(String text, String sql) {
        if (BODY_DDL.matcher(text).find()) {
            throw new IllegalArgumentException("routine and trigger bodies must not run DDL, GRANT/REVOKE, or "
                    + "RENAME; use a separate change set: " + summarize(sql));
        }
    }

    private static void rejectForbidden(String text, String sql) {
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(text).find()) {
                throw destructive(sql);
            }
        }
        Matcher delete = DELETE.matcher(text);
        while (delete.find()) {
            if (!DELETE_EVENT_PREDECESSORS.contains(previousToken(text, delete.start()))) {
                throw destructive(sql);
            }
        }
    }

    private static String previousToken(String text, int offset) {
        int end = offset;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == ',') {
            return ",";
        }
        int start = end;
        while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    /** Routine body contents (all literals) for scanning, PostgreSQL lexing. */
    static String routineBodyForScan(String sql) {
        return SqlLexer.literalContents(sql, SqlLexer.Mode.POSTGRES);
    }

    private static boolean matchesAllowedStatement(String sql) {
        if (sql.matches("(?s)^ALTER\\s+TABLE\\b.*")) {
            return alterTableActionsAllowed(sql);
        }
        if (sql.matches("(?s)^GRANT\\b.*")) {
            return GRANT_OBJECT.matcher(sql).matches() && !GRANT_NON_OBJECT_TARGET.matcher(sql).find();
        }
        if (sql.matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?TRIGGER\\b.*")) {
            return !SERVER_OR_DATABASE_TRIGGER.matcher(sql).find();
        }
        return sql.matches("(?s)^CREATE\\s+TABLE\\b.*")
                || sql.matches("(?s)^CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*")
                || sql.matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\b.*")
                || sql.matches("(?s)^CREATE\\s+EXTENSION\\b.*")
                || sql.matches("(?s)^(UPDATE|INSERT\\s+INTO)\\b.*")
                || sql.matches("(?s)^COMMENT\\s+ON\\b.*")
                || sql.matches("(?s)^SELECT\\b.*");
    }

    /** Every top-level comma-separated ALTER TABLE action must be additive. */
    private static boolean alterTableActionsAllowed(String sql) {
        Matcher alter = ALTER_TABLE.matcher(sql.trim());
        if (!alter.matches()) {
            return false;
        }
        List<String> actions = splitTopLevel(alter.group(1));
        if (actions.isEmpty()) {
            return false;
        }
        for (String action : actions) {
            if (!alterActionAllowed(action.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean alterActionAllowed(String action) {
        for (Pattern allowed : ALTER_TABLE_ACTIONS) {
            if (allowed.matcher(action).matches()) {
                String topLevel = REFERENTIAL_ACTION.matcher(outsideParentheses(action)).replaceAll(" ");
                return !TRAILING_ALTER_CLAUSE.matcher(topLevel).find();
            }
        }
        Matcher modify = ORACLE_MODIFY.matcher(action);
        if (modify.matches() && outsideParentheses(action).trim().equals("MODIFY")) {
            List<String> items = splitTopLevel(modify.group(1));
            return !items.isEmpty() && items.stream()
                    .allMatch(item -> ORACLE_MODIFY_ITEM.matcher(item.trim()).matches());
        }
        return false;
    }

    private static String outsideParentheses(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int depth = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
            }
            result.append(depth == 0 && current != ')' ? current : ' ');
        }
        return result.toString();
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        parts.removeIf(String::isBlank);
        return parts;
    }

    private static Pattern token(String expression) {
        return Pattern.compile("(?s)\\b" + expression + "\\b", Pattern.CASE_INSENSITIVE);
    }

    /** Returns only executable SQL text: comments, literals, and bodies blanked; identifiers as X. */
    private static String executableSql(String sql, SqlLexer.Mode mode) {
        return SqlLexer.mask(sql, mode, false, false).trim();
    }

    private static IllegalArgumentException destructive(String sql) {
        return new IllegalArgumentException("destructive or manually-reviewed SQL is not allowed: "
                + summarize(sql));
    }

    private static String summarize(String sql) {
        return SqlLexer.summarize(sql);
    }
}
