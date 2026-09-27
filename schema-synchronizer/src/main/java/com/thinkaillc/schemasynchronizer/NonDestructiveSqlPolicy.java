// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.thinkaillc.schemasynchronizer.SqlTokenizer.Token;

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
    /** Unquoted keywords; a quoted identifier with the same spelling is only a name. */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of("DROP", "TRUNCATE", "EXEC", "PREPARE",
            "OPENQUERY", "OPENROWSET", "OPENDATASOURCE", "OUTFILE", "DUMPFILE");

    /** Objects that run dynamic SQL or reach outside the database, quoted or not. */
    private static final Pattern FORBIDDEN_NAMES = Pattern.compile(
            "SP_EXECUTESQL|DBMS_SQL|XP_CMDSHELL|OPENQUERY|OPENROWSET|OPENDATASOURCE|DBMS_JOB|DBMS_SCHEDULER"
                    + "|DBMS_DDL|EXEC_DDL_STATEMENT");

    /**
     * Oracle packages, types, and functions that reach the network or file system, or run a query string.
     * {@code BFILENAME} and the {@code DBMS_LOB} file routines read server files.
     */
    private static final Pattern ORACLE_EXTERNAL_NAMES = Pattern.compile(
            "UTL_HTTP|UTL_TCP|UTL_SMTP|UTL_MAIL|UTL_FILE|UTL_INADDR|DBMS_XMLGEN|DBMS_XMLQUERY|DBMS_PIPE"
                    + "|HTTPURITYPE|BFILENAME|FILEOPEN|FILEEXISTS|FILEGETNAME|FILEISOPEN|FILECLOSE|FILECLOSEALL"
                    + "|LOADFROMFILE|LOADBLOBFROMFILE|LOADCLOBFROMFILE");

    /** Functions whose call (quoted or schema-qualified) reads files, signals backends, or runs a query string. */
    private static final Pattern FORBIDDEN_CALLS = Pattern.compile(
            "DBLINK\\w*|PG_TERMINATE_BACKEND|PG_CANCEL_BACKEND|PG_DROP_REPLICATION_SLOT"
                    + "|PG_CREATE_\\w*REPLICATION_SLOT|PG_RELOAD_CONF|PG_ROTATE_LOGFILE|PG_PROMOTE"
                    + "|PG_READ_\\w+|PG_WRITE_\\w+|PG_FILE_\\w+|PG_LS_\\w+|PG_STAT_FILE|PG_SWITCH_WAL"
                    + "|PG_CREATE_RESTORE_POINT|PG_LOGICAL_EMIT_MESSAGE|PG_(?:BACKUP|START|STOP)_\\w+"
                    + "|PG_STAT_RESET\\w*|PG_REPLICATION_ORIGIN_\\w+|PG_IMPORT_SYSTEM_COLLATIONS"
                    + "|LO_\\w+|LOWRITE|LOAD_FILE|QUERY_TO_XML\\w*|SET_ROLE");

    /**
     * PostgreSQL configuration parameters a routine's {@code SET} clause or a {@code SET} statement must not
     * change: identity, replication, row-level security, and library loading. Every {@code log_*} parameter
     * and every {@code pgaudit.*} parameter is also forbidden, since those control logging and auditing.
     */
    private static final Set<String> POSTGRES_SET_FORBIDDEN = Set.of("role", "session_authorization",
            "session_replication_role", "row_security", "session_preload_libraries", "local_preload_libraries",
            "shared_preload_libraries", "jit_provider", "dynamic_library_path");

    /** MySQL scopes whose {@code SET} changes the server for every session. */
    private static final Set<String> MYSQL_GLOBAL_SCOPES = Set.of("GLOBAL", "PERSIST", "PERSIST_ONLY");

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
    private static final Set<String> BODY_DDL = Set.of("ALTER", "RENAME", "GRANT", "REVOKE", "CREATE");

    /** Reserved T-SQL words for statements a routine body must not run; reserved, so never an identifier. */
    private static final Set<String> TSQL_BODY_FORBIDDEN = Set.of("DENY", "DBCC", "BACKUP", "RESTORE",
            "RECONFIGURE", "SHUTDOWN", "KILL", "CHECKPOINT", "SETUSER");

    /** Statements a routine body must not start; matched only at a statement start inside the body. */
    private static final Set<String> BODY_STATEMENT_FORBIDDEN = Set.of("DENY", "COPY", "LOAD", "LOCK", "VACUUM",
            "CLUSTER", "REINDEX", "REFRESH", "SECURITY", "IMPORT", "DISCARD", "CHECKPOINT", "INSTALL", "UNINSTALL",
            "FLUSH", "KILL", "SHUTDOWN", "PURGE", "AUDIT", "NOAUDIT", "CHANGE");

    /** Words that cannot follow a statement keyword, so the word before them is a column or variable. */
    private static final Set<String> NOT_STATEMENT_CONTINUATIONS = Set.of("END", "ELSE", "WHEN", "THEN", "AND",
            "OR", "IS", "AS", "FROM", "NOT", "IN", "LIKE", "BETWEEN");

    /** Keywords whose {@code INTO} targets variables or rows, not a new table. */
    private static final Set<String> INTO_OWNERS = Set.of("SELECT", "INSERT", "MERGE", "FETCH", "RETURNING",
            "RETURN", "EXECUTE", "EXEC", "UPDATE", "DELETE", "VALUES", "OUTPUT");

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
        List<Token> tokens = SqlTokenizer.tokenize(sql, mode);
        requireSingleStatement(tokens, mode, sql, "schema change SQL");
        requireNoForbiddenTokens(sql, tokens, mode);
        if (!matchesAllowedStatement(normalized)) {
            throw new IllegalArgumentException("unsupported schema change SQL: " + summarize(sql));
        }
    }

    public static void requireReadOnlyVerification(String sql) {
        requireReadOnlyVerification(sql, DatabaseDialect.POSTGRESQL);
    }

    public static void requireReadOnlyVerification(String sql, DatabaseDialect dialect) {
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        List<Token> tokens = sql == null ? List.of() : SqlTokenizer.tokenize(sql, mode);
        if (tokens.isEmpty() || !(tokens.getFirst().keyword("SELECT") || tokens.getFirst().keyword("WITH"))) {
            throw new IllegalArgumentException("verification SQL must be a SELECT or WITH query");
        }
        requireSingleStatement(tokens, mode, sql, "verification SQL");
        requireNoForbiddenTokens(sql, tokens, mode);
        if (tokens.stream().anyMatch(token -> token.keyword(DATA_MODIFYING))) {
            throw new IllegalArgumentException("verification SQL must not contain a data-modifying statement");
        }
        if (hasVerificationSideEffect(tokens, mode)) {
            throw new IllegalArgumentException("verification SQL must not write, lock, or advance sequences: "
                    + summarize(sql));
        }
    }

    private static final Set<String> DATA_MODIFYING = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

    private static final Set<String> LOCKING_HINTS =
            Set.of("UPDLOCK", "XLOCK", "TABLOCK", "TABLOCKX", "HOLDLOCK", "PAGLOCK");

    /**
     * Lock functions, sequence and transaction-id advancement, notifications, and file or
     * backend access, called quoted or unquoted.
     */
    private static final Pattern SIDE_EFFECT_CALLS = Pattern.compile(
            "SETVAL|NEXTVAL|GET_LOCK|RELEASE_LOCK|RELEASE_ALL_LOCKS|IS_FREE_LOCK|PG_(?:TRY_)?ADVISORY\\w*"
                    + "|SP_GETAPPLOCK|SP_RELEASEAPPLOCK|LO_\\w+|PG_READ_\\w+|PG_TERMINATE_BACKEND"
                    + "|PG_CANCEL_BACKEND|LOAD_FILE|PG_NOTIFY|TXID_CURRENT|PG_CURRENT_XACT_ID");

    private static final Pattern DBMS_LOCK = Pattern.compile("DBMS_LOCK");

    private static final Pattern NEXTVAL = Pattern.compile("NEXTVAL");

    /**
     * Covers SELECT INTO / INTO OUTFILE, row locks, lock functions, and sequence advancement.
     * Keywords match unquoted words only, so {@code "into"} or {@code [updlock]} as a column
     * name is not a side effect; function and object names match quoted or unquoted.
     */
    private static boolean hasVerificationSideEffect(List<Token> tokens, SqlLexer.Mode mode) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token next = SqlTokenizer.next(tokens, index);
            if (token.keyword("INTO") || token.keyword("OUTFILE") || token.keyword("DUMPFILE")
                    || token.keyword(LOCKING_HINTS)) {
                return true;
            }
            if (token.keyword("FOR") && followedBy(tokens, index, "UPDATE", "NO", "SHARE", "KEY")) {
                return true;
            }
            if (token.keyword("LOCK") && followedBy(tokens, index, "IN")
                    && followedBy(tokens, index + 1, "SHARE")) {
                return true;
            }
            if (token.keyword("NEXT") && followedBy(tokens, index, "VALUE") && followedBy(tokens, index + 1, "FOR")) {
                return true;
            }
            if (next != null && next.punct("(") && SqlTokenizer.nameMatches(token, SIDE_EFFECT_CALLS, mode)) {
                return true;
            }
            if (SqlTokenizer.nameMatches(token, DBMS_LOCK, mode)) {
                return true;
            }
            if (token.punct(".") && next != null && SqlTokenizer.nameMatches(next, NEXTVAL, mode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean followedBy(List<Token> tokens, int index, String... keywords) {
        Token next = SqlTokenizer.next(tokens, index);
        if (next == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (next.keyword(keyword)) {
                return true;
            }
        }
        return false;
    }

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

    private static void requireSingleStatement(List<Token> tokens, SqlLexer.Mode mode, String sql, String label) {
        if (SqlTokenizer.statements(tokens, mode, sql).size() > 1) {
            throw new IllegalArgumentException(label + " must contain exactly one statement");
        }
    }

    /**
     * Scans the statement's code, and for routines and triggers also the body: the code after
     * the routine head (MySQL/MariaDB, SQL Server, Oracle, PostgreSQL {@code BEGIN ATOMIC})
     * and PostgreSQL literal bodies, tokenized as code with their own literals as data.
     */
    private static void requireNoForbiddenTokens(String sql, List<Token> tokens, SqlLexer.Mode mode) {
        rejectForbidden(tokens, mode, sql);
        SqlTokenizer.Routine routine = SqlTokenizer.routine(tokens, mode);
        if (routine == null) {
            return;
        }
        String language = mode == SqlLexer.Mode.POSTGRES ? SqlTokenizer.postgresLanguage(tokens) : null;
        if (language != null && !language.equals("sql") && !language.equals("plpgsql")) {
            throw new IllegalArgumentException("only LANGUAGE sql and plpgsql bodies can be checked; create "
                    + "routines in other languages outside change sets: " + summarize(sql));
        }
        List<SqlTokenizer.Body> postgresBodies = SqlTokenizer.postgresBodies(tokens, mode, sql);
        if (mode == SqlLexer.Mode.POSTGRES && language == null && !postgresBodies.isEmpty()) {
            throw new IllegalArgumentException("a routine with an AS body must declare LANGUAGE sql or plpgsql: "
                    + summarize(sql));
        }
        if (mode == SqlLexer.Mode.POSTGRES) {
            rejectRoutineSetClause(tokens, routine, sql);
        }
        boolean selectIntoCreatesTable = mode == SqlLexer.Mode.SQLSERVER || mode == SqlLexer.Mode.POSTGRES;
        rejectBodyStatements(tokens.subList(routine.kindIndex() + 1, tokens.size()), mode, sql, false,
                selectIntoCreatesTable);
        // PL/pgSQL SELECT … INTO assigns variables; in LANGUAGE sql bodies it creates a table.
        boolean plpgsql = "plpgsql".equals(language);
        for (SqlTokenizer.Body body : postgresBodies) {
            rejectForbidden(body.tokens(), mode, sql);
            rejectBodyStatements(body.tokens(), mode, sql, true, !plpgsql);
        }
    }

    /** Checks for statements a routine or trigger body must not run; {@code atStatement}: the first token starts one. */
    static void rejectBodyStatements(List<Token> tokens, SqlLexer.Mode mode, String sql, boolean atStatement,
                                     boolean selectIntoCreatesTable) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token next = SqlTokenizer.next(tokens, index);
            if (token.keyword(BODY_DDL) || (token.keyword("COMMENT") && next != null && next.keyword("ON"))) {
                throw bodyDdl(sql);
            }
            if ((token.keyword("DISABLE") || token.keyword("ENABLE")) && next != null && next.keyword("TRIGGER")) {
                throw bodyStatement(sql);
            }
            if (mode == SqlLexer.Mode.SQLSERVER && token.keyword(TSQL_BODY_FORBIDDEN)) {
                throw bodyStatement(sql);
            }
            if (token.keyword(BODY_STATEMENT_FORBIDDEN) && startsStatement(tokens, index, atStatement)) {
                throw bodyStatement(sql);
            }
            if ((token.keyword("SET") && (sessionSet(tokens, index, atStatement)
                    || (mode == SqlLexer.Mode.POSTGRES && postgresBodySetting(tokens, index, atStatement))))
                    || (token.keyword("RESET") && next != null && (next.keyword("ROLE") || next.keyword("SESSION")))) {
                throw bodyStatement(sql);
            }
            if (token.keyword("INTO") && next != null && (next.keyword("TEMP") || next.keyword("TEMPORARY")
                    || next.keyword("UNLOGGED") || next.keyword("TABLE")
                    || (selectIntoCreatesTable && "SELECT".equals(intoOwner(tokens, index))))) {
                throw bodyDdl(sql);
            }
        }
    }

    /** A statement keyword at a statement start, not a column or variable of the same spelling. */
    private static boolean startsStatement(List<Token> tokens, int index, boolean atStatement) {
        Token previous = SqlTokenizer.previous(tokens, index);
        boolean boundary = previous == null ? atStatement
                : previous.punct(";") || previous.punct(":") || previous.punct(">>")
                || previous.keyword(SqlTokenizer.STATEMENT_BOUNDARY_WORDS) || SqlTokenizer.eachRow(tokens, index - 1, 0);
        if (!boundary) {
            return false;
        }
        Token next = SqlTokenizer.next(tokens, index);
        if (next == null || next.punct(";")) {
            return true;
        }
        return (next.name() || next.type() == SqlTokenizer.Type.STRING) && !next.keyword(NOT_STATEMENT_CONTINUATIONS);
    }

    /**
     * {@code SET [SESSION|LOCAL] ROLE}, {@code SET … SESSION AUTHORIZATION}, or MySQL
     * {@code SET DEFAULT ROLE}, unless the {@code SET} belongs to an {@code UPDATE} or
     * MySQL {@code INSERT … SET} (it then follows the target table or its alias).
     * PostgreSQL configuration parameter names are case-insensitive, quoted or not.
     */
    private static boolean sessionSet(List<Token> tokens, int index, boolean atStatement) {
        if (!settingStatement(tokens, index, atStatement)) {
            return false;
        }
        int at = index + 1;
        while (at < tokens.size() && (tokens.get(at).keyword("SESSION") || tokens.get(at).keyword("LOCAL"))) {
            if (tokens.get(at).keyword("SESSION") && at + 1 < tokens.size()
                    && tokens.get(at + 1).keyword("AUTHORIZATION")) {
                return true;
            }
            at++;
        }
        if (at >= tokens.size()) {
            return false;
        }
        Token target = tokens.get(at);
        if (target.keyword("DEFAULT") && at + 1 < tokens.size() && tokens.get(at + 1).keyword("ROLE")) {
            return true;
        }
        return target.keyword("ROLE") || target.keyword("AUTHORIZATION")
                || (target.quoted() && "role".equalsIgnoreCase(target.value()));
    }

    /** A {@code SET} statement, not the {@code SET} of an {@code UPDATE} or MySQL {@code INSERT … SET}. */
    private static boolean settingStatement(List<Token> tokens, int index, boolean atStatement) {
        Token previous = SqlTokenizer.previous(tokens, index);
        boolean dataSet = previous != null
                && ((previous.name() && !previous.keyword(SqlTokenizer.STATEMENT_BOUNDARY_WORDS)
                && !SqlTokenizer.eachRow(tokens, index - 1, 0))
                || previous.punct(")") || previous.type() == SqlTokenizer.Type.VARIABLE);
        return !dataSet && !(previous == null && !atStatement);
    }

    /** The keyword that owns an {@code INTO} at the same parenthesis depth within its statement. */
    private static String intoOwner(List<Token> tokens, int index) {
        int depth = tokens.get(index).depth();
        for (int at = index - 1; at >= 0; at--) {
            Token token = tokens.get(at);
            if (token.depth() < depth || token.punct(";") || token.keyword(SqlTokenizer.STATEMENT_BOUNDARY_WORDS)) {
                return null;
            }
            if (token.depth() == depth && token.keyword(INTO_OWNERS)) {
                return token.text().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static void rejectForbidden(List<Token> tokens, SqlLexer.Mode mode, String sql) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token next = SqlTokenizer.next(tokens, index);
            if (token.keyword(FORBIDDEN_KEYWORDS)) {
                throw destructive(sql);
            }
            if (token.keyword("EXECUTE") && !(next != null
                    && (next.keyword("FUNCTION") || next.keyword("PROCEDURE") || next.keyword("ON")))) {
                throw destructive(sql);
            }
            if (token.keyword("DELETE") && !DELETE_EVENT_PREDECESSORS.contains(previousWord(tokens, index))) {
                throw destructive(sql);
            }
            if (SqlTokenizer.nameMatches(token, FORBIDDEN_NAMES, mode)
                    || (next != null && next.punct("(") && SqlTokenizer.nameMatches(token, FORBIDDEN_CALLS, mode))
                    || (mode == SqlLexer.Mode.ORACLE && SqlTokenizer.nameMatches(token, ORACLE_EXTERNAL_NAMES, mode))
                    || (mode == SqlLexer.Mode.SQLSERVER && token.keyword("BULK"))) {
                throw destructive(sql);
            }
            if (mode == SqlLexer.Mode.SQLSERVER && token.keyword("ADD") && tsqlModuleSignature(tokens, index)) {
                throw new IllegalArgumentException("change sets must not sign modules or change sensitivity "
                        + "classifications: " + summarize(sql));
            }
            if (mode == SqlLexer.Mode.POSTGRES && postgresSetConfig(tokens, index)) {
                throw new IllegalArgumentException("change sets must not change logging, auditing, role, or "
                        + "library settings: " + summarize(sql));
            }
            if (mode == SqlLexer.Mode.MYSQL && mysqlGlobalSet(tokens, index)) {
                throw new IllegalArgumentException("change sets must not change GLOBAL or PERSIST server "
                        + "variables: " + summarize(sql));
            }
        }
    }

    /**
     * {@code SET GLOBAL|PERSIST|PERSIST_ONLY …} or {@code SET @@global.name = …}, alone or
     * after a comma in a {@code SET} list. Scope keywords are case-insensitive keywords, so
     * {@code UPDATE t SET global = 1} (a column) is not a match.
     */
    private static boolean mysqlGlobalSet(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        Token previous = SqlTokenizer.previous(tokens, index);
        if (previous == null || !(previous.keyword("SET") || previous.punct(","))) {
            return false;
        }
        Token next = SqlTokenizer.next(tokens, index);
        if (token.keyword(MYSQL_GLOBAL_SCOPES) && next != null && next.name()) {
            Token after = SqlTokenizer.next(tokens, index + 1);
            return previous.keyword("SET") || (after != null && (after.punct("=") || after.punct(":=")));
        }
        if (token.type() != SqlTokenizer.Type.VARIABLE || !token.text().startsWith("@@") || next == null
                || !next.punct(".") || index + 3 >= tokens.size()) {
            return false;
        }
        Token assignment = tokens.get(index + 3);
        return MYSQL_GLOBAL_SCOPES.contains(token.text().substring(2).toUpperCase(Locale.ROOT))
                && tokens.get(index + 2).name() && (assignment.punct("=") || assignment.punct(":="));
    }

    /**
     * A PostgreSQL routine's {@code SET parameter …} clause, which applies for every call.
     * Configuration parameter names are case-insensitive, quoted or not.
     */
    private static void rejectRoutineSetClause(List<Token> tokens, SqlTokenizer.Routine routine, String sql) {
        for (int index = routine.kindIndex() + 1; index + 1 < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() != 0) {
                continue;
            }
            if ((token.keyword("BEGIN") && tokens.get(index + 1).keyword("ATOMIC")) || token.keyword("RETURN")) {
                return;
            }
            if (token.keyword("SET") && postgresSettingForbidden(settingName(tokens, index + 1))) {
                throw bodyStatement(sql);
            }
        }
    }

    /** The configuration parameter name starting at {@code index} ({@code name} or {@code prefix.name}), lowercased. */
    private static String settingName(List<Token> tokens, int index) {
        if (index >= tokens.size() || !tokens.get(index).name()) {
            return null;
        }
        String name = tokens.get(index).value().toLowerCase(Locale.ROOT);
        if (index + 2 < tokens.size() && tokens.get(index + 1).punct(".") && tokens.get(index + 2).name()) {
            name += "." + tokens.get(index + 2).value().toLowerCase(Locale.ROOT);
        }
        return name;
    }

    private static boolean postgresSettingForbidden(String name) {
        return name != null && (POSTGRES_SET_FORBIDDEN.contains(name) || name.startsWith("log_")
                || name.startsWith("pgaudit."));
    }

    /**
     * {@code ADD [COUNTER]SIGNATURE TO …} or {@code ADD SENSITIVITY CLASSIFICATION …}; a column named
     * {@code signature} in {@code ALTER TABLE … ADD} is followed by its type, never by {@code TO}.
     */
    private static boolean tsqlModuleSignature(List<Token> tokens, int index) {
        Token next = SqlTokenizer.next(tokens, index);
        Token after = SqlTokenizer.next(tokens, index + 1);
        if (next == null || after == null) {
            return false;
        }
        return ((next.keyword("SIGNATURE") || next.keyword("COUNTERSIGNATURE")) && after.keyword("TO"))
                || (next.keyword("SENSITIVITY") && after.keyword("CLASSIFICATION"));
    }

    /** {@code SET [SESSION|LOCAL] parameter …} at a statement start in a PostgreSQL body. */
    private static boolean postgresBodySetting(List<Token> tokens, int index, boolean atStatement) {
        if (!settingStatement(tokens, index, atStatement)) {
            return false;
        }
        int at = index + 1;
        while (at < tokens.size() && (tokens.get(at).keyword("SESSION") || tokens.get(at).keyword("LOCAL"))) {
            at++;
        }
        return postgresSettingForbidden(settingName(tokens, at));
    }

    /** {@code set_config('parameter', …)} naming a forbidden parameter. */
    private static boolean postgresSetConfig(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (!token.name() || !"set_config".equals(token.value().toLowerCase(Locale.ROOT))
                || index + 2 >= tokens.size() || !tokens.get(index + 1).punct("(")) {
            return false;
        }
        Token argument = tokens.get(index + 2);
        return argument.type() != SqlTokenizer.Type.STRING
                || postgresSettingForbidden(argument.value().trim().toLowerCase(Locale.ROOT));
    }

    private static String previousWord(List<Token> tokens, int index) {
        Token previous = SqlTokenizer.previous(tokens, index);
        if (previous == null) {
            return "";
        }
        if (previous.punct(",")) {
            return ",";
        }
        return previous.type() == SqlTokenizer.Type.WORD ? previous.text().toUpperCase(Locale.ROOT) : "";
    }

    private static IllegalArgumentException bodyDdl(String sql) {
        return new IllegalArgumentException("routine and trigger bodies must not run DDL, GRANT/REVOKE, or "
                + "RENAME; use a separate change set: " + summarize(sql));
    }

    private static IllegalArgumentException bodyStatement(String sql) {
        return new IllegalArgumentException("routine and trigger bodies must not change roles, privileges, or "
                + "triggers, or run administrative statements: " + summarize(sql));
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
