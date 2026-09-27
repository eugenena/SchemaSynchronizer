// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.thinkaillc.schemasynchronizer.SqlTokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects change-set SQL that targets a schema/catalog other than the configured
 * namespace. Declarative {@code createSql} already enforces this; change sets did not.
 *
 * <p>Any {@code schema.object} reference (bare or quoted/bracket/backtick) outside
 * string literals must use the configured namespace or a system catalog, except a two-part
 * {@code q.column} in an expression whose qualifier is a table name or alias declared in the
 * same query block and set-operation branch, a trigger/{@code OUTPUT}/{@code ON CONFLICT}
 * pseudo-row (Oracle: colon-prefixed, or bare in the trigger {@code WHEN}), a routine
 * parameter, variable, loop record, block label or the routine's own name, or a {@code %TYPE}
 * anchor; routine items followed by fields or (Oracle, procedural code) collection methods;
 * Oracle {@code seq.NEXTVAL}/{@code CURRVAL}, {@link #ORACLE_SUPPLIED_PACKAGES}, and
 * {@link #ORACLE_SUPPLIED_TYPES}; and
 * PostgreSQL object names given as text or {@code reg*} literals, which are checked as names. Other calls
 * ({@code q.f(…)}) and object positions are always schema-checked, and so are type positions
 * ({@code ::}, {@code CAST … AS}, parameter, return, declaration, and column types), where
 * no alias or routine item can qualify a name. A three-part name is a
 * cross-database reference where an object belongs ({@code FROM a.b.c}, {@code UPDATE a.b.c},
 * {@code a.b.c(…)}); in an expression it is {@code schema.table.column} and only its schema
 * is checked.
 *
 * <p>This is a guardrail against accidental cross-namespace change sets, not a security
 * boundary against hostile SQL authors.
 */
final class ChangeSetSchemaScope {
    private static final String IDENT_CHAR = "\\p{L}\\p{N}_$#@";

    /** Quoted forms, or a bare word that is not purely numeric and starts at a token boundary. */
    private static final String IDENT =
            "(?:\"([^\"]+)\"|\\[([^\\]]+)\\]|`([^`]+)`"
                    + "|(?<![" + IDENT_CHAR + "])((?=\\p{N}*[\\p{L}_$#@])[" + IDENT_CHAR + "]+))";

    /** SQL Server {@code db..object} (default schema in another database). */
    private static final Pattern DOUBLE_DOT = Pattern.compile(IDENT + "\\s*\\.\\s*\\.");

    private static final Pattern IN_SCHEMA = Pattern.compile(
            "(?i)\\bIN\\s+SCHEMA\\s+" + IDENT);

    private static final Pattern EXTENSION_OR_COMMENT_SCHEMA = Pattern.compile(
            "(?i)\\b(?:CREATE\\s+EXTENSION\\b[^;]*?\\bSCHEMA\\s+"
                    + "|COMMENT\\s+ON\\s+SCHEMA\\s+"
                    + "|GRANT\\b[^;]*?\\bON\\s+SCHEMA(?:\\s*::\\s*|\\s+)"
                    + "|COMMENT\\s+ON\\s+DATABASE\\s+)"
                    + IDENT);

    /**
     * Every {@code ON [TABLE] db.*} / {@code ON *.*} occurrence (matched independently so a
     * later in-scope grant cannot hide an earlier foreign one).
     */
    private static final Pattern GRANT_ON_DB_STAR = Pattern.compile(
            "(?i)\\bON(?=[\\s*\"`\\[])\\s*(?:TABLE\\s+)?(?:"
                    + "(\\*)\\s*\\.\\s*\\*"
                    + "|" + IDENT + "\\s*\\.\\s*\\*)");

    /** {@code ON DATABASE x} (PostgreSQL) and {@code ON DATABASE::x} (SQL Server). */
    private static final Pattern GRANT_ON_DATABASE = Pattern.compile("(?i)\\bON\\s+DATABASE\\b");

    private static final Pattern GRANT_KEYWORD = Pattern.compile("(?i)\\bGRANT\\b");

    /** Unescaped MySQL grant wildcard characters in a database name. */
    private static final Pattern MYSQL_GRANT_WILDCARD = Pattern.compile("(?<!\\\\)[_%]");

    /** Session namespace mutators that would defeat schema binding for unqualified DDL. */
    private static final Pattern SESSION_NAMESPACE = Pattern.compile(
            "(?is)(?:\\bset_config\"?\\s*\\("
                    + "|\\bALTER\\s+SESSION\\s+SET\\s+CURRENT_SCHEMA\\b"
                    + "|\\bUSE\\s+[A-Za-z_\"`\\[])");

    private static final Pattern SET_CONFIG = Pattern.compile("SET_CONFIG");

    private static final Pattern ORACLE_DB_LINK = Pattern.compile("[\\p{L}\\p{N}_$#\"]\\s*@\\s*[\\p{L}\"]");

    /** Keywords after which a qualified name is an object, not a column expression. */
    private static final Set<String> OBJECT_KEYWORDS = Set.of("FROM", "JOIN", "UPDATE", "INTO", "TABLE",
            "REFERENCES", "USING", "MERGE", "INSERT", "DELETE", "FUNCTION", "PROCEDURE", "VIEW", "SEQUENCE", "INDEX",
            "TRIGGER", "EXEC", "EXECUTE", "CALL", "FOR", "COLUMN", "APPLY", "TRUNCATE");

    /** Keywords after which a qualified name is written or defined. */
    private static final Set<String> WRITE_KEYWORDS = Set.of("UPDATE", "INTO", "TABLE", "REFERENCES", "VIEW",
            "SEQUENCE", "COLUMN", "INDEX", "TRIGGER", "INSERT", "MERGE", "DELETE", "TRUNCATE");

    /** Modifiers between a statement keyword and its object ({@code TOP (…)} is skipped separately). */
    private static final Set<String> OBJECT_MODIFIERS = Set.of("IF", "NOT", "EXISTS", "ONLY", "PERCENT", "TEMP",
            "TEMPORARY", "UNLOGGED", "LOW_PRIORITY", "IGNORE", "QUICK", "DELAYED", "LATERAL");

    private static final Set<String> JOIN_KEYWORDS = Set.of("JOIN", "USING", "APPLY");

    private static final Set<String> ON_OWNERS = Set.of("FROM", "SELECT", "WHERE", "INDEX", "TRIGGER", "GRANT",
            "REVOKE", "DENY", "TABLE", "UPDATE", "INTO", "SET", "POLICY", "RULE", "COMMENT");

    private static final Set<String> OBJECT_LIST_OWNERS =
            Set.of("FROM", "UPDATE", "TABLE", "INTO", "DELETE", "TRUNCATE", "LOCK");

    private static final Set<String> WRITE_LIST_OWNERS = Set.of("UPDATE", "TABLE", "INTO", "DELETE", "TRUNCATE");

    private static final Set<String> EXPRESSION_LIST_OWNERS = Set.of("SELECT", "WHERE", "SET", "VALUES", "BY",
            "HAVING", "RETURNING", "OUTPUT", "TO", "WHEN", "THEN", "ELSE", "AND", "OR", "RETURN");

    /** Words before UPDATE/DELETE that make it a trigger event, privilege, or clause, not a statement. */
    private static final Set<String> NON_STATEMENT_WRITE_PREDECESSORS =
            Set.of("ON", "BEFORE", "AFTER", "OR", "OF", "FOR", "KEY", "GRANT", "REVOKE", "DENY", "INSTEAD");

    /** Words that follow a table reference without being its alias. */
    private static final Set<String> NOT_ALIASES = Set.of("SET", "WHERE", "ON", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "OUTER", "NATURAL", "USING", "WITH", "WHEN", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION",
            "OUTPUT", "FROM", "VALUES", "SELECT", "RETURNING", "TABLESAMPLE", "FOR", "OPTION", "WINDOW", "PARTITION",
            "EXCEPT", "INTERSECT", "APPLY", "STRAIGHT_JOIN", "USE", "FORCE", "IGNORE", "DEFAULT");

    private ChangeSetSchemaScope() {
    }

    /**
     * Namespace comparison follows the dialect's identifier folding: PostgreSQL folds
     * unquoted names to lower case, Oracle to upper case, and quoted names are exact.
     * MySQL/MariaDB catalog and SQL Server schema case sensitivity depends on server
     * settings / collation, so those compare exactly (fail closed).
     */
    static void requireScoped(String sql, String configuredNamespace, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        Namespace allowed = new Namespace(canonical(configuredNamespace, false, dialect), dialect);
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        // Comments and literals masked with the dialect's own lexing rules; quoted
        // identifiers and dollar bodies stay visible for binding checks.
        String scannable = SqlLexer.maskForScope(sql, mode, false);
        List<Token> tokens = SqlTokenizer.tokenize(sql, mode);
        if (SESSION_NAMESPACE.matcher(scannable).find() || sessionNamespaceMutation(tokens, mode, allowed)) {
            throw new IllegalArgumentException(
                    "schema change SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        boolean routine = SqlTokenizer.routine(tokens, mode) != null;
        int commentColumn = checkCommentOnColumn(tokens, mode, allowed, configuredNamespace, sql);
        scan(scannable, tokens, allowed, configuredNamespace, sql, "", routine, commentColumn, List.of());
        if (routine) {
            List<Token> header = routineNames(tokens, mode);
            for (SqlTokenizer.Body body : SqlTokenizer.postgresBodies(tokens, mode, sql)) {
                String bodyText = bodyScannable(body.text(), mode);
                scan(bodyText, body.tokens(), allowed, configuredNamespace, sql, " inside routine body", true, -1,
                        header);
                if (SESSION_NAMESPACE.matcher(bodyText).find() || sessionNamespaceMutation(body.tokens(), mode, null)) {
                    throw new IllegalArgumentException(
                            "schema change SQL must not alter session namespace inside routine body: "
                                    + summarize(sql));
                }
            }
        }
    }

    private static String bodyScannable(String body, SqlLexer.Mode mode) {
        try {
            return SqlLexer.maskForScope(body, mode, true);
        } catch (IllegalArgumentException unlexable) {
            return body;
        }
    }

    /**
     * Validates the column name of {@code COMMENT ON COLUMN} (three parts: the first must be the
     * configured namespace) and returns the index of that name so {@code table.column} is not
     * read as {@code schema.object}; -1 when absent.
     */
    private static int checkCommentOnColumn(List<Token> tokens, SqlLexer.Mode mode, Namespace allowed,
                                            String configuredNamespace, String sql) {
        if (tokens.size() < 4 || !tokens.get(0).keyword("COMMENT") || !tokens.get(1).keyword("ON")
                || !tokens.get(2).keyword("COLUMN")) {
            return -1;
        }
        Chain chain = chainAt(tokens, 3, mode);
        if (chain == null || chain.parts().size() > 3) {
            return -1;
        }
        if (chain.parts().size() == 3) {
            Token schema = chain.parts().get(0);
            if (!allowed.matches(schema.value(), schema.quoted())) {
                throw new IllegalArgumentException("schema change SQL targets namespace '" + schema.value()
                        + "' but synchronizer is configured for '" + configuredNamespace + "': " + summarize(sql));
            }
        }
        return 3;
    }

    /** Oracle database links ({@code table@link}) reach outside the connected database. */
    static void requireNoDatabaseLink(String scannable, DatabaseDialect dialect, String sql) {
        if (dialect == DatabaseDialect.ORACLE && ORACLE_DB_LINK.matcher(scannable).find()) {
            throw new IllegalArgumentException(
                    "schema change SQL must not reference an Oracle database link (object@link): "
                            + summarize(sql));
        }
    }

    /**
     * Read-only verification queries routinely use {@code alias.column}, which is lexically
     * identical to {@code schema.table}, so only session-namespace mutators are rejected there.
     */
    static void requireNoSessionNamespaceChange(String sql, DatabaseDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        String scannable = SqlLexer.maskForScope(sql, mode, false);
        if (SESSION_NAMESPACE.matcher(scannable).find() || sessionNamespaceMutation(SqlTokenizer.tokenize(sql, mode),
                mode, null)) {
            throw new IllegalArgumentException(
                    "schema verification SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        requireNoDatabaseLink(scannable, dialect, sql);
    }

    /**
     * {@code set_config(…)} called by a quoted or escaped name, {@code SET [LOCAL|SESSION] search_path}
     * (also quoted), and PostgreSQL {@code SET SCHEMA '…'}. Configuration parameter names are
     * case-insensitive. With a namespace, a PostgreSQL routine's own {@code SET search_path}
     * clause is allowed when {@link #routineSearchPath} accepts it.
     */
    private static boolean sessionNamespaceMutation(List<Token> tokens, SqlLexer.Mode mode, Namespace routineScope) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token next = SqlTokenizer.next(tokens, index);
            if (next != null && next.punct("(") && SqlTokenizer.nameMatches(token, SET_CONFIG, mode)) {
                return true;
            }
            if (!token.keyword("SET")) {
                continue;
            }
            int at = index + 1;
            while (at < tokens.size() && (tokens.get(at).keyword("LOCAL") || tokens.get(at).keyword("SESSION"))) {
                at++;
            }
            if (at >= tokens.size()) {
                continue;
            }
            Token target = tokens.get(at);
            if (target.name() && "search_path".equalsIgnoreCase(target.value())) {
                if (routineScope != null && mode == SqlLexer.Mode.POSTGRES && at == index + 1 && token.depth() == 0
                        && routineSettingStatement(tokens, index, mode) && routineSearchPath(tokens, at + 1, routineScope)) {
                    continue;
                }
                return true;
            }
            if (mode == SqlLexer.Mode.POSTGRES && target.keyword("SCHEMA") && at + 1 < tokens.size()
                    && tokens.get(at + 1).type() == SqlTokenizer.Type.STRING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the statement holding {@code index} is {@code CREATE [OR REPLACE] FUNCTION|PROCEDURE}
     * or {@code ALTER FUNCTION|PROCEDURE|ROUTINE}, whose {@code SET} clause applies only while the
     * routine runs.
     */
    private static boolean routineSettingStatement(List<Token> tokens, int index, SqlLexer.Mode mode) {
        int start = 0;
        for (int[] range : Correlations.statements(tokens, mode)) {
            if (range[0] <= index && index < range[1]) {
                start = range[0];
            }
        }
        int at = start;
        if (at < tokens.size() && tokens.get(at).keyword("CREATE")) {
            at++;
            if (at + 1 < tokens.size() && tokens.get(at).keyword("OR") && tokens.get(at + 1).keyword("REPLACE")) {
                at += 2;
            }
            return at < tokens.size() && (tokens.get(at).keyword("FUNCTION") || tokens.get(at).keyword("PROCEDURE"));
        }
        return at + 1 < tokens.size() && tokens.get(at).keyword("ALTER")
                && (tokens.get(at + 1).keyword("FUNCTION") || tokens.get(at + 1).keyword("PROCEDURE")
                || tokens.get(at + 1).keyword("ROUTINE"));
    }

    /**
     * A routine {@code SET search_path {TO|=} …} value that lists only the configured schema,
     * {@code pg_catalog}, and {@code pg_temp} (any order; unquoted names fold, quoted names and
     * string items are exact), or the empty string. {@code FROM CURRENT} copies the session's
     * path and {@code DEFAULT} takes the server's, so both are rejected.
     */
    private static boolean routineSearchPath(List<Token> tokens, int from, Namespace allowed) {
        if (from >= tokens.size() || !(tokens.get(from).keyword("TO") || tokens.get(from).punct("="))) {
            return false;
        }
        int at = from + 1;
        while (true) {
            if (at >= tokens.size()) {
                return false;
            }
            Token item = tokens.get(at);
            String name;
            if (item.type() == SqlTokenizer.Type.WORD) {
                if (item.keyword("FROM") || item.keyword("DEFAULT")) {
                    return false;
                }
                name = item.value().toLowerCase(Locale.ROOT);
            } else if (item.type() == SqlTokenizer.Type.QUOTED || item.type() == SqlTokenizer.Type.STRING) {
                name = item.value();
            } else {
                return false;
            }
            if (!(name.isEmpty() || name.equals("pg_catalog") || name.equals("pg_temp") || name.equals(allowed.canonical()))) {
                return false;
            }
            at++;
            if (at >= tokens.size() || !tokens.get(at).punct(",")) {
                return at >= tokens.size() || !tokens.get(at).punct(".");
            }
            at++;
        }
    }

    /** True when an unquoted {@code reference} names the configured namespace under the dialect's folding. */
    static boolean sameNamespace(String configuredNamespace, String reference, DatabaseDialect dialect) {
        return canonical(configuredNamespace, false, dialect).equals(canonical(reference, false, dialect));
    }

    static String canonical(String identifier, boolean quoted, DatabaseDialect dialect) {
        if (quoted) {
            return identifier;
        }
        return switch (dialect) {
            case POSTGRESQL -> identifier.toLowerCase(Locale.ROOT);
            case ORACLE -> identifier.toUpperCase(Locale.ROOT);
            case MYSQL, MARIADB, SQLSERVER -> identifier;
        };
    }

    private record Namespace(String canonical, DatabaseDialect dialect) {
        boolean matches(String raw, boolean quoted) {
            return canonical.equals(ChangeSetSchemaScope.canonical(raw, quoted, dialect));
        }
    }

    /** A dotted name; {@code first}/{@code last} are token indexes. Bracket/backtick forms count as quoted. */
    private record Chain(List<Token> parts, int first, int last) {}

    private static void scan(String text, List<Token> tokens, Namespace allowed, String configuredNamespace,
                             String sql, String where, boolean routine, int skipChainAt, List<Token> inherited) {
        DatabaseDialect dialect = allowed.dialect();
        // Oracle PL/SQL uses `..` as its range operator (FOR i IN lo..hi LOOP).
        if (dialect != DatabaseDialect.ORACLE && DOUBLE_DOT.matcher(text).find()) {
            throw new IllegalArgumentException("schema change SQL must not use database..object references"
                    + where + ": " + summarize(sql));
        }
        requireNoDatabaseLink(text, dialect, sql);
        rejectUnsafeGrants(text, allowed, configuredNamespace, sql);
        rejectForeignChains(tokens, allowed, configuredNamespace, sql, where, routine, skipChainAt, inherited);
        if (dialect == DatabaseDialect.POSTGRESQL) {
            rejectForeignRelationLiterals(tokens, allowed, configuredNamespace, sql, where);
        }
        rejectForeignSchema(text, IN_SCHEMA, allowed, configuredNamespace, sql, "uses IN SCHEMA" + where);
        rejectForeignSchema(text, EXTENSION_OR_COMMENT_SCHEMA, allowed, configuredNamespace, sql,
                "references schema" + where);
        rejectCatalogWritesThroughAliases(tokens, allowed, sql);
    }

    private static void rejectForeignChains(List<Token> tokens, Namespace allowed, String configuredNamespace,
                                            String sql, String where, boolean routine, int skipChainAt,
                                            List<Token> inherited) {
        DatabaseDialect dialect = allowed.dialect();
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        ListOwners objectLists = new ListOwners(tokens, OBJECT_LIST_OWNERS);
        ListOwners writeLists = new ListOwners(tokens, WRITE_LIST_OWNERS);
        Correlations correlations = new Correlations(tokens, mode, dialect, routine, objectLists, inherited);
        for (Chain chain : chains(tokens, mode)) {
            if (chain.first() == skipChainAt) {
                continue;
            }
            int parts = chain.parts().size();
            boolean typed = typePosition(tokens, chain, dialect) || correlations.typeRegion(chain.first());
            boolean anchor = columnAnchor(tokens, chain, dialect);
            if ((dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.SQLSERVER)
                    && (parts >= 4 || (parts == 3 && !anchor && (typed || objectPosition(tokens, chain, objectLists))))) {
                throw new IllegalArgumentException("schema change SQL must not use database.schema.object references"
                        + where + ": " + summarize(sql));
            }
            Token schema = chain.parts().get(0);
            if (anchor && parts >= 3 && dialect == DatabaseDialect.ORACLE && routine
                    && correlations.routineItem(chain.first(), schema)) {
                continue;
            }
            if (!typed && dialect == DatabaseDialect.POSTGRESQL && !schema.quoted()
                    && POSITIONAL_PARAMETER.matcher(schema.value()).matches()) {
                continue;
            }
            if (isSystemCatalog(schema, dialect)) {
                if (writeTarget(tokens, chain.first(), writeLists)) {
                    throw catalogWrite(schema, sql);
                }
                continue;
            }
            if (dialect == DatabaseDialect.ORACLE && !objectKeywordPosition(tokens, chain, objectLists)
                    && (oracleSuppliedPackage(chain) || oracleSuppliedType(chain) || oracleSequence(tokens, chain))) {
                continue;
            }
            if (dialect == DatabaseDialect.ORACLE && oracleDual(chain)) {
                if (!readPosition(tokens, chain, objectLists, writeLists)) {
                    throw catalogWrite(schema, sql);
                }
                continue;
            }
            if (!typed && dialect == DatabaseDialect.ORACLE && parts >= 3
                    && !objectKeywordPosition(tokens, chain, objectLists)
                    && correlations.aliasDeclares(chain.first(), schema)) {
                continue;
            }
            if (parts == 2 && anchor) {
                continue;
            }
            if (!typed && parts == 2 && columnExpression(tokens, chain, objectLists)
                    && (correlations.pseudoRow(chain) || correlations.declares(chain.first(), schema))) {
                continue;
            }
            if (!typed && routine && correlations.routineItemChain(chain, objectLists)) {
                continue;
            }
            if (!allowed.matches(schema.value(), schema.quoted())) {
                throw new IllegalArgumentException(
                        "schema change SQL targets namespace" + where + " '" + schema.value()
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
    }

    /** A PostgreSQL positional parameter ({@code $1.qty}); an unquoted schema name cannot start with {@code $}. */
    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("\\$\\d+");

    private static final Set<String> CAST_FUNCTIONS = Set.of("CAST", "TRY_CAST", "TREAT", "XMLCAST");

    /**
     * Whether a dotted name stands where the engine reads a type or collation, so it is
     * {@code schema.type} and never {@code alias.column} or a local item: after {@code ::}
     * (PostgreSQL), {@code COLLATE}, {@code TYPE} ({@code ALTER COLUMN c TYPE t}), or
     * {@code AS [REF]} in {@code CAST}/{@code TRY_CAST}/{@code TREAT}/{@code XMLCAST}; before a
     * string constant ({@code t 'x'}, PostgreSQL); the first argument of SQL Server
     * {@code CONVERT}/{@code TRY_CONVERT} and the type after {@code @variable [AS]}; a column type
     * in a {@code TABLE (…)}, {@code CREATE TABLE t (…)}, or {@code ADD}/{@code MODIFY} column list;
     * and the types in an Oracle {@code IS OF [TYPE] (…)} test. Routine headers and declaration
     * sections are covered by {@link Correlations#typeRegion}.
     */
    private static boolean typePosition(List<Token> tokens, Chain chain, DatabaseDialect dialect) {
        int first = chain.first();
        Token previous = SqlTokenizer.previous(tokens, first);
        if (previous == null) {
            return false;
        }
        Token next = SqlTokenizer.next(tokens, chain.last());
        if (dialect == DatabaseDialect.POSTGRESQL && (previous.punct("::") || (next != null && stringConstant(next)))) {
            return true;
        }
        if (previous.keyword("COLLATE") || (previous.keyword("TYPE") && !(first >= 2 && tokens.get(first - 2).punct("%")))) {
            return true;
        }
        int as = previous.keyword("REF") ? first - 2 : first - 1;
        if (as >= 1 && tokens.get(as).keyword("AS")) {
            int open = enclosingOpen(tokens, as);
            if (open > 0 && tokens.get(open).punct("(") && tokens.get(open - 1).keyword(CAST_FUNCTIONS)) {
                return true;
            }
        }
        if (dialect == DatabaseDialect.SQLSERVER) {
            Token beforePrevious = first >= 2 ? tokens.get(first - 2) : null;
            if (previous.type() == SqlTokenizer.Type.VARIABLE
                    || (previous.keyword("AS") && beforePrevious != null && beforePrevious.type() == SqlTokenizer.Type.VARIABLE)
                    || (previous.punct("(") && beforePrevious != null
                    && (beforePrevious.keyword("CONVERT") || beforePrevious.keyword("TRY_CONVERT")))) {
                return true;
            }
        }
        if (dialect == DatabaseDialect.ORACLE && (previous.punct("(") || previous.punct(",") || previous.keyword("ONLY"))) {
            int open = enclosingOpen(tokens, first);
            if (open > 0 && tokens.get(open).punct("(")) {
                Token opener = tokens.get(open - 1);
                if (opener.keyword("OF") || (opener.keyword("TYPE") && open >= 2 && tokens.get(open - 2).keyword("OF"))) {
                    return true;
                }
            }
        }
        return columnDefinitionType(tokens, first, previous);
    }

    /**
     * A column's type in a column list: the name before it starts an item of a list opened by
     * {@code TABLE (}, {@code TABLE name (}, or {@code ADD}/{@code MODIFY (}, or follows
     * {@code ADD}/{@code MODIFY}/{@code COLUMN}/{@code IF NOT EXISTS} in {@code ALTER TABLE}.
     */
    private static boolean columnDefinitionType(List<Token> tokens, int first, Token previous) {
        if (!previous.name() || first < 2) {
            return false;
        }
        Token itemStart = tokens.get(first - 2);
        if (itemStart.keyword("ADD") || itemStart.keyword("MODIFY") || itemStart.keyword("COLUMN")
                || (itemStart.keyword("EXISTS") && first >= 4 && tokens.get(first - 3).keyword("NOT"))) {
            return !tokens.isEmpty() && tokens.get(0).keyword("ALTER");
        }
        if (!(itemStart.punct("(") || itemStart.punct(","))) {
            return false;
        }
        int open = itemStart.punct("(") ? first - 2 : enclosingOpen(tokens, first - 2);
        if (open < 1 || !tokens.get(open).punct("(")) {
            return false;
        }
        Token opener = tokens.get(open - 1);
        if (opener.keyword("TABLE") || opener.keyword("ADD") || opener.keyword("MODIFY")) {
            return true;
        }
        int name = nameStart(tokens, open - 1);
        return opener.name() && name >= 1 && (tokens.get(name - 1).keyword("TABLE")
                || (tokens.get(name - 1).keyword("EXISTS") && name >= 3 && tokens.get(name - 3).keyword("TABLE")));
    }

    private static List<Chain> chains(List<Token> tokens, SqlLexer.Mode mode) {
        List<Chain> result = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (index > 0 && tokens.get(index - 1).punct(".")) {
                continue;
            }
            Chain chain = chainAt(tokens, index, mode);
            if (chain != null && chain.parts().size() >= 2) {
                result.add(chain);
                index = chain.last();
            }
        }
        return result;
    }

    /** The dotted name starting at {@code index} (possibly a single part), or null when no name starts there. */
    private static Chain chainAt(List<Token> tokens, int index, SqlLexer.Mode mode) {
        List<Token> parts = new ArrayList<>();
        int end = namePartEnd(tokens, index, mode, parts);
        if (end < 0) {
            return null;
        }
        int last = end - 1;
        while (end + 1 < tokens.size() && tokens.get(end).punct(".")) {
            int next = namePartEnd(tokens, end + 1, mode, parts);
            if (next < 0) {
                break;
            }
            last = next - 1;
            end = next;
        }
        return new Chain(List.copyOf(parts), index, last);
    }

    /**
     * Adds the name at {@code index} to {@code parts} and returns the index after it, or -1.
     * {@code [x]} and {@code `x`} outside the engines that quote with them are still read as
     * quoted names (fail closed); a PostgreSQL array subscript {@code a[i]} is not a name.
     */
    private static int namePartEnd(List<Token> tokens, int index, SqlLexer.Mode mode, List<Token> parts) {
        if (index >= tokens.size()) {
            return -1;
        }
        Token token = tokens.get(index);
        if (token.name()) {
            parts.add(token);
            return index + 1;
        }
        boolean bracket = token.punct("[") && mode != SqlLexer.Mode.SQLSERVER;
        boolean backtick = token.punct("`") && mode != SqlLexer.Mode.MYSQL;
        if ((bracket || backtick) && index + 2 < tokens.size() && tokens.get(index + 1).name()
                && tokens.get(index + 2).punct(bracket ? "]" : "`")) {
            Token previous = SqlTokenizer.previous(tokens, index);
            if (bracket && previous != null && (previous.name() || previous.punct(")") || previous.punct("]"))
                    && previous.end() == token.start()) {
                return -1;
            }
            Token inner = tokens.get(index + 1);
            parts.add(new Token(SqlTokenizer.Type.QUOTED, inner.text(), inner.text(), token.start(),
                    tokens.get(index + 2).end(), token.depth()));
            return index + 3;
        }
        return -1;
    }

    /** Whether a chain stands where an object belongs rather than in a column expression. */
    private static boolean objectPosition(List<Token> tokens, Chain chain, ListOwners lists) {
        Token after = SqlTokenizer.next(tokens, chain.last());
        if (after != null && after.punct("(")) {
            return true;
        }
        return objectKeywordPosition(tokens, chain, lists);
    }

    /** Whether the words before a chain make it an object ({@code FROM x.y}, {@code JOIN x.y}, …), calls aside. */
    private static boolean objectKeywordPosition(List<Token> tokens, Chain chain, ListOwners lists) {
        int at = previousSignificant(tokens, chain.first());
        if (at < 0) {
            return true;
        }
        Token previous = tokens.get(at);
        if (previous.keyword("ON")) {
            return !joinCondition(tokens, at);
        }
        if (previous.keyword("LIKE")) {
            return at > 0 && tokens.get(at - 1).punct("(");
        }
        if (previous.keyword(OBJECT_KEYWORDS)) {
            return !operandKeyword(tokens, at);
        }
        return previous.punct(",") && lists.owns(at);
    }

    /**
     * {@code FROM}/{@code FOR} inside a function call ({@code EXTRACT(YEAR FROM x)},
     * {@code TRIM(BOTH ' ' FROM x)}, {@code SUBSTRING(x FROM 2 FOR 3)}) or after
     * {@code IS [NOT] DISTINCT} takes an operand, not an object. A {@code FROM} whose
     * parentheses hold a {@code SELECT}/{@code DELETE} at its depth is a query clause.
     */
    private static boolean operandKeyword(List<Token> tokens, int at) {
        Token token = tokens.get(at);
        if (!(token.keyword("FROM") || token.keyword("FOR"))) {
            return false;
        }
        if (token.keyword("FROM") && at > 1 && tokens.get(at - 1).keyword("DISTINCT")
                && (tokens.get(at - 2).keyword("IS") || tokens.get(at - 2).keyword("NOT"))) {
            return true;
        }
        int depth = token.depth();
        for (int back = at - 1; back >= 0 && depth > 0; back--) {
            Token candidate = tokens.get(back);
            if (candidate.depth() < depth) {
                return candidate.punct("(") && back > 0 && tokens.get(back - 1).name();
            }
            if (candidate.depth() == depth && (candidate.keyword("SELECT") || candidate.keyword("DELETE")
                    || candidate.punct(";"))) {
                return false;
            }
        }
        return false;
    }

    /** {@code table.column%TYPE}: a column of a table in the current schema (PostgreSQL and Oracle). */
    private static boolean columnAnchor(List<Token> tokens, Chain chain, DatabaseDialect dialect) {
        if (dialect != DatabaseDialect.POSTGRESQL && dialect != DatabaseDialect.ORACLE) {
            return false;
        }
        Token percent = SqlTokenizer.next(tokens, chain.last());
        Token type = SqlTokenizer.next(tokens, chain.last() + 1);
        return percent != null && type != null && percent.punct("%") && type.keyword("TYPE")
                && tokens.get(chain.last()).end() == percent.start() && percent.end() == type.start();
    }

    /** Oracle {@code seq.NEXTVAL} / {@code seq.CURRVAL}: a sequence in the current schema. */
    private static boolean oracleSequence(List<Token> tokens, Chain chain) {
        if (chain.parts().size() != 2) {
            return false;
        }
        String pseudo = canonical(chain.parts().get(chain.parts().size() - 1).value(), chain.parts().get(chain.parts().size() - 1).quoted(),
                DatabaseDialect.ORACLE);
        Token after = SqlTokenizer.next(tokens, chain.last());
        return (pseudo.equals("NEXTVAL") || pseudo.equals("CURRVAL")) && (after == null || !after.punct("("));
    }

    /**
     * Oracle-supplied packages whose members only compute values or write the session output
     * buffer, called as {@code PKG.member} or {@code SYS.PKG.member}; an empty set allows
     * every member. {@code UTL_RAW} and {@code UTL_I18N} convert RAW and character-set values.
     * File, network, SQL, lock, and SQL-text validation ({@code DBMS_ASSERT}) packages are not listed.
     * {@code DBMS_LOB} lists its non-file routines plus every constant and exception its 19c
     * package specification declares.
     */
    private static final Map<String, Set<String>> ORACLE_SUPPLIED_PACKAGES = Map.of(
            "DBMS_OUTPUT", Set.of(),
            "DBMS_UTILITY", Set.of("FORMAT_ERROR_STACK", "FORMAT_ERROR_BACKTRACE", "FORMAT_CALL_STACK"),
            "DBMS_RANDOM", Set.of(),
            "UTL_RAW", Set.of(),
            "UTL_I18N", Set.of(),
            "DBMS_LOB", Set.of("APPEND", "COMPARE", "CONVERTTOBLOB", "CONVERTTOCLOB", "COPY", "CREATETEMPORARY",
                    "ERASE", "FREETEMPORARY", "GETCHUNKSIZE", "GETLENGTH", "INSTR", "ISTEMPORARY", "READ",
                    "SUBSTR", "TRIM", "WRITE", "WRITEAPPEND",
                    "FILE_READONLY", "LOB_READONLY", "LOB_READWRITE", "LOBMAXSIZE", "CALL", "TRANSACTION",
                    "SESSION", "WARN_INCONVERTIBLE_CHAR", "DEFAULT_CSID", "DEFAULT_LANG_CTX", "NO_WARNING",
                    "OPT_COMPRESS", "OPT_ENCRYPT", "OPT_DEDUPLICATE", "COMPRESS_OFF", "COMPRESS_ON",
                    "ENCRYPT_OFF", "ENCRYPT_ON", "DEDUPLICATE_OFF", "DEDUPLICATE_ON", "DBFS_LINK_NEVER",
                    "DBFS_LINK_YES", "DBFS_LINK_NO", "DBFS_LINK_NOCACHE", "DBFS_LINK_CACHE",
                    "DBFS_LINK_PATH_MAX_SIZE", "CONTENTTYPE_MAX_SIZE", "INVALID_ARGVAL_NUM", "ECCESS_ERROR_NUM",
                    "NOEXIST_DIRECTORY_NUM", "NOPRIV_DIRECTORY_NUM", "INVALID_DIRECTORY_NUM",
                    "OPERATION_FAILED_NUM", "UNOPENED_FILE_NUM", "OPEN_TOOMANY_NUM", "SECUREFILE_BADLOB_NUM",
                    "SECUREFILE_BADPARAM_NUM", "SECUREFILE_MARKERASED_NUM", "SECUREFILE_OUTOFBOUNDS_NUM",
                    "CONTENTTYPE_TOOLONG_NUM", "CONTENTTYPEBUF_WRONG_NUM",
                    "INVALID_ARGVAL", "ACCESS_ERROR", "NOEXIST_DIRECTORY", "NOPRIV_DIRECTORY",
                    "INVALID_DIRECTORY", "OPERATION_FAILED", "UNOPENED_FILE", "OPEN_TOOMANY",
                    "SECUREFILE_BADLOB", "SECUREFILE_BADPARAM", "SECUREFILE_MARKERASED",
                    "SECUREFILE_OUTOFBOUNDS", "CONTENTTYPE_TOOLONG", "CONTENTTYPEBUF_WRONG"));

    /**
     * Oracle-supplied {@code SYS} data types, used as {@code SYS.type} (constructor or declared
     * type) or through the listed static members ({@code [SYS.]type.member}). The {@code ODCI*LIST}
     * entries are the {@code VARRAY}s of scalars in {@code ALL_COLL_TYPES}; the lists of
     * {@code BFILE}, {@code ANYDATA}, and index-framework objects are not listed. An
     * {@code XMLTYPE} built from a file still needs {@code BFILENAME}, which is rejected.
     */
    private static final Map<String, Set<String>> ORACLE_SUPPLIED_TYPES = Map.of(
            "ODCINUMBERLIST", Set.of(),
            "ODCIVARCHAR2LIST", Set.of(),
            "ODCIDATELIST", Set.of(),
            "ODCIRAWLIST", Set.of(),
            "ODCIGRANULELIST", Set.of(),
            "ODCIRIDLIST", Set.of(),
            "XMLTYPE", Set.of("CREATEXML"));

    private static boolean oracleSuppliedType(Chain chain) {
        List<Token> parts = chain.parts();
        boolean sys = oracleName(parts.get(0)).equals("SYS");
        if (parts.size() == 2 && sys) {
            return ORACLE_SUPPLIED_TYPES.containsKey(oracleName(parts.get(1)));
        }
        int offset = sys ? 1 : 0;
        if (parts.size() != offset + 2) {
            return false;
        }
        Set<String> members = ORACLE_SUPPLIED_TYPES.get(oracleName(parts.get(offset)));
        return members != null && members.contains(oracleName(parts.get(offset + 1)));
    }

    /** Oracle {@code SYS.DUAL}, the read-only one-row table. */
    private static boolean oracleDual(Chain chain) {
        return chain.parts().size() == 2 && oracleName(chain.parts().get(0)).equals("SYS")
                && oracleName(chain.parts().get(chain.parts().size() - 1)).equals("DUAL");
    }

    /** Whether a table stands where a query reads it: after {@code FROM}/{@code JOIN} or in a {@code FROM} list. */
    private static boolean readPosition(List<Token> tokens, Chain chain, ListOwners objectLists,
                                        ListOwners writeLists) {
        int at = previousSignificant(tokens, chain.first());
        if (at < 0) {
            return false;
        }
        Token previous = tokens.get(at);
        if (previous.keyword("FROM") || previous.keyword("JOIN")) {
            return !(at > 0 && tokens.get(at - 1).keyword("DELETE"));
        }
        return previous.punct(",") && objectLists.owns(at) && !writeTarget(tokens, chain.first(), writeLists);
    }

    private static boolean oracleSuppliedPackage(Chain chain) {
        List<Token> parts = chain.parts();
        int offset;
        if (parts.size() == 2) {
            offset = 0;
        } else if (parts.size() == 3 && oracleName(parts.get(0)).equals("SYS")) {
            offset = 1;
        } else {
            return false;
        }
        Set<String> members = ORACLE_SUPPLIED_PACKAGES.get(oracleName(parts.get(offset)));
        return members != null && (members.isEmpty() || members.contains(oracleName(parts.get(offset + 1))));
    }

    private static String oracleName(Token token) {
        return canonical(token.value(), token.quoted(), DatabaseDialect.ORACLE);
    }

    /**
     * PostgreSQL functions that resolve an object named by their first argument (classified from
     * {@code pg_proc}). The {@link #NAME_REQUIRED_CALLS} use or change that object — the sequence
     * functions, {@code pg_get_serial_sequence} (whose result {@code nextval} accepts), extension
     * configuration, and index maintenance — so their argument must be a checkable literal. The
     * others return only metadata and also accept an oid or column expression without string
     * literals ({@code pg_relation_size(c.oid)}). The export functions that read a relation's rows
     * or run a query string ({@code table_to_xml}, {@code query_to_xml}, {@code ts_stat}, …) are
     * rejected outright by {@link NonDestructiveSqlPolicy}.
     */
    private static final Pattern RELATION_NAME_CALLS = Pattern.compile(
            "NEXTVAL|CURRVAL|SETVAL"
                    + "|TO_REGCLASS|TO_REGPROC|TO_REGPROCEDURE|TO_REGTYPE|TO_REGTYPEMOD|TO_REGNAMESPACE|TO_REGOPER"
                    + "|TO_REGOPERATOR|TO_REGCOLLATION|TO_REGROLE"
                    + "|PG_GET_SERIAL_SEQUENCE|PG_GET_VIEWDEF|PG_RELATION_SIZE|PG_TABLE_SIZE|PG_TOTAL_RELATION_SIZE"
                    + "|PG_INDEXES_SIZE|PG_RELATION_FILENODE|PG_RELATION_FILEPATH|PG_SEQUENCE_LAST_VALUE"
                    + "|PG_GET_REPLICA_IDENTITY_INDEX|PG_COLUMN_IS_UPDATABLE|PG_RELATION_IS_UPDATABLE"
                    + "|PG_RELATION_IS_PUBLISHABLE|PG_PARTITION_ROOT|PG_PARTITION_TREE|PG_PARTITION_ANCESTORS"
                    + "|PG_EXTENSION_CONFIG_DUMP|PG_INDEX_HAS_PROPERTY|PG_INDEX_COLUMN_HAS_PROPERTY"
                    + "|BRIN_SUMMARIZE_NEW_VALUES|BRIN_SUMMARIZE_RANGE|BRIN_DESUMMARIZE_RANGE|GIN_CLEAN_PENDING_LIST");

    private static final Pattern NAME_REQUIRED_CALLS = Pattern.compile(
            "NEXTVAL|CURRVAL|SETVAL|PG_GET_SERIAL_SEQUENCE|PG_EXTENSION_CONFIG_DUMP"
                    + "|BRIN_SUMMARIZE_NEW_VALUES|BRIN_SUMMARIZE_RANGE|BRIN_DESUMMARIZE_RANGE|GIN_CLEAN_PENDING_LIST");

    /** Text types a string literal may be cast through before a {@code reg*} conversion. */
    private static final Set<String> TEXT_TYPES = Set.of("text", "varchar", "char", "character", "bpchar", "name",
            "unknown", "varying");

    private static final Pattern SERIAL_SEQUENCE = Pattern.compile("PG_GET_SERIAL_SEQUENCE");

    /** What a name string resolves to, and so how its qualifier is checked. */
    private enum NameKind {
        /** {@code [schema.]relation}, at most two parts. */
        RELATION,
        /** A type, function, operator, collation, or text-search object; every {@code q.} in it is checked. */
        QUALIFIED,
        /** A schema name. */
        SCHEMA,
        /** A role, which belongs to the cluster, not a schema. */
        UNSCOPED
    }

    /** {@code reg*} types PostgreSQL resolves a name string into ({@code 's'::regclass}, {@code regclass('s')}). */
    private static final Map<String, NameKind> REGTYPES = Map.ofEntries(
            Map.entry("regclass", NameKind.RELATION),
            Map.entry("regnamespace", NameKind.SCHEMA),
            Map.entry("regrole", NameKind.UNSCOPED),
            Map.entry("regproc", NameKind.QUALIFIED),
            Map.entry("regprocedure", NameKind.QUALIFIED),
            Map.entry("regtype", NameKind.QUALIFIED),
            Map.entry("regoper", NameKind.QUALIFIED),
            Map.entry("regoperator", NameKind.QUALIFIED),
            Map.entry("regconfig", NameKind.QUALIFIED),
            Map.entry("regdictionary", NameKind.QUALIFIED),
            Map.entry("regcollation", NameKind.QUALIFIED));

    /** PostgreSQL privilege checks; they return a boolean for a named object, so their literal names are checked. */
    private static final Pattern PRIVILEGE_CALLS = Pattern.compile(
            "HAS_(?:TABLE|SEQUENCE|ANY_COLUMN|COLUMN|FUNCTION|TYPE)_PRIVILEGE");

    private static final Pattern SCHEMA_PRIVILEGE_CALL = Pattern.compile("HAS_SCHEMA_PRIVILEGE");

    private static final Pattern INPUT_CHECK_CALLS = Pattern.compile("PG_INPUT_IS_VALID|PG_INPUT_ERROR_INFO");

    /** A qualifier ({@code q.} / {@code "q".}) inside a type, function, or operator name string. */
    private static final Pattern NAME_QUALIFIER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$\"])(\"(?:[^\"]|\"\")+\"|[\\p{L}_][\\p{L}\\p{N}_$]*)\\s*\\.");

    /**
     * PostgreSQL resolves string arguments of the {@link #RELATION_NAME_CALLS} functions, the
     * privilege and input-check functions, and every {@code reg*} conversion ({@code 's'::regclass},
     * {@code regclass 's'}, {@code regclass('s')}, {@code CAST('s' AS regclass)}, the type bare or as
     * {@code pg_catalog.regclass}) as names, so each literal is checked the way a written name is.
     * Every string-constant spelling counts ({@code '…'}, {@code E'…'}, {@code U&'…'},
     * {@code $$…$$}), seen through parentheses and text casts ({@code ('s')::text::regclass}). An
     * operand that holds a string constant but does not reduce to one literal is rejected, as is
     * a literal converted to a {@code reg*} array.
     */
    private static void rejectForeignRelationLiterals(List<Token> tokens, Namespace allowed,
                                                      String configuredNamespace, String sql, String where) {
        NameCheck check = new NameCheck(allowed, configuredNamespace, sql, where);
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token next = SqlTokenizer.next(tokens, index);
            boolean call = next != null && next.punct("(");
            if (token.keyword("OPERATOR") && call && index + 3 < tokens.size() && tokens.get(index + 2).name()
                    && tokens.get(index + 3).punct(".")) {
                check.requireSchema(tokens.get(index + 2));
                continue;
            }
            if (call && SqlTokenizer.nameMatches(token, RELATION_NAME_CALLS, SqlLexer.Mode.POSTGRES)) {
                List<Integer> arguments = arguments(tokens, index + 1);
                boolean required = SqlTokenizer.nameMatches(token, NAME_REQUIRED_CALLS, SqlLexer.Mode.POSTGRES);
                if (arguments.isEmpty()) {
                    if (required) {
                        throw check.notLiteral(token);
                    }
                    continue;
                }
                checkNameArgument(tokens, token, arguments.get(0), relationCallKind(token), check, required);
                continue;
            }
            if (call && SqlTokenizer.nameMatches(token, PRIVILEGE_CALLS, SqlLexer.Mode.POSTGRES)) {
                for (int argument : arguments(tokens, index + 1)) {
                    checkNameArgument(tokens, token, argument, NameKind.QUALIFIED, check, false);
                }
                continue;
            }
            if (call && SqlTokenizer.nameMatches(token, SCHEMA_PRIVILEGE_CALL, SqlLexer.Mode.POSTGRES)) {
                List<Integer> arguments = arguments(tokens, index + 1);
                for (int at = 0; at < arguments.size(); at++) {
                    NameKind kind = at == arguments.size() - 2 ? NameKind.SCHEMA : NameKind.UNSCOPED;
                    checkNameArgument(tokens, token, arguments.get(at), kind, check, false);
                }
                continue;
            }
            if (call && SqlTokenizer.nameMatches(token, INPUT_CHECK_CALLS, SqlLexer.Mode.POSTGRES)) {
                checkInputCheck(tokens, token, index + 1, check);
                continue;
            }
            int regType = regTypeChain(tokens, index);
            if (regType < 0 || (index > 0 && tokens.get(index - 1).punct("."))) {
                continue;
            }
            String typeName = regTypeName(tokens.get(regType));
            NameKind kind = regKind(typeName);
            Token before = SqlTokenizer.previous(tokens, index);
            Token after = SqlTokenizer.next(tokens, regType);
            boolean array = typeName.startsWith("_") || (after != null && (after.punct("[") || after.keyword("ARRAY")));
            int from = -1;
            int to = -1;
            if (before != null && before.punct("::") && index >= 2) {
                to = index - 2;
                from = operandStart(tokens, to);
            } else if (before != null && before.keyword("AS") && index >= 2 && insideCast(tokens, index - 1)) {
                to = index - 2;
                from = enclosingOpen(tokens, index - 1) + 1;
            } else if (after != null && after.punct("(")) {
                from = regType + 2;
                to = SqlTokenizer.matchingClose(tokens, regType + 1) - 1;
            } else if (after != null && stringConstant(after)) {
                from = regType + 1;
                to = regType + 1;
            }
            if (from >= 0 && from <= to) {
                checkRegOperand(tokens, from, to, tokens.get(regType), kind, array, check);
            }
        }
    }

    /** A PostgreSQL string constant: {@code '…'}, {@code E'…'}, {@code U&'…'} (decoded), or {@code $tag$…$tag$}. */
    private static boolean stringConstant(Token token) {
        return token.type() == SqlTokenizer.Type.STRING || token.type() == SqlTokenizer.Type.BODY;
    }

    private static boolean containsStringConstant(List<Token> tokens, int from, int to) {
        for (int at = from; at <= to; at++) {
            if (stringConstant(tokens.get(at))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The single string constant the tokens {@code from..to} evaluate to, seen through enclosing
     * parentheses and casts to text or {@code reg*} types ({@code ('s')::text},
     * {@code CAST('s' AS varchar)}, {@code text 's'}); null when they are anything else.
     */
    private static Token reducedLiteral(List<Token> tokens, int from, int to) {
        while (from <= to) {
            if (tokens.get(from).punct("(") && SqlTokenizer.matchingClose(tokens, from) == to) {
                from++;
                to--;
                continue;
            }
            if (tokens.get(from).keyword("CAST") && from + 1 < to && tokens.get(from + 1).punct("(")
                    && SqlTokenizer.matchingClose(tokens, from + 1) == to) {
                int as = topLevelKeyword(tokens, from + 2, to - 1, "AS");
                if (as < 0 || !castTarget(tokens, as + 1, to - 1)) {
                    return null;
                }
                from += 2;
                to = as - 1;
                continue;
            }
            int cast = lastTopLevelCast(tokens, from, to);
            if (cast >= 0) {
                if (!castTarget(tokens, cast + 1, to)) {
                    return null;
                }
                to = cast - 1;
                continue;
            }
            if (from + 1 == to && castTarget(tokens, from, from) && stringConstant(tokens.get(to))) {
                from = to;
                continue;
            }
            return from == to && stringConstant(tokens.get(from)) ? tokens.get(from) : null;
        }
        return null;
    }

    /** Whether {@code from..to} names a text type or a scalar {@code reg*} type, optionally {@code pg_catalog}-qualified. */
    private static boolean castTarget(List<Token> tokens, int from, int to) {
        if (from <= to && tokens.get(to).punct(")")) {
            int open = enclosingOpenOf(tokens, to);
            if (open <= from) {
                return false;
            }
            to = open - 1;
        }
        if (from + 2 <= to && isSystemCatalog(tokens.get(from), DatabaseDialect.POSTGRESQL)
                && tokens.get(from + 1).punct(".")) {
            from += 2;
        }
        if (from > to) {
            return false;
        }
        for (int at = from; at <= to; at++) {
            Token part = tokens.get(at);
            if (!part.name()) {
                return false;
            }
            String name = canonical(part.value(), part.quoted(), DatabaseDialect.POSTGRESQL);
            if (!TEXT_TYPES.contains(name) && !(from == to && REGTYPES.containsKey(name))) {
                return false;
            }
        }
        return true;
    }

    private static int lastTopLevelCast(List<Token> tokens, int from, int to) {
        int depth = tokens.get(from).depth();
        for (int at = to; at > from; at--) {
            if (tokens.get(at).depth() == depth && tokens.get(at).punct("::")) {
                return at;
            }
        }
        return -1;
    }

    private static int topLevelKeyword(List<Token> tokens, int from, int to, String keyword) {
        int depth = tokens.get(from).depth();
        for (int at = from; at <= to; at++) {
            if (tokens.get(at).depth() == depth && tokens.get(at).keyword(keyword)) {
                return at;
            }
        }
        return -1;
    }

    /** The {@code (} that the {@code )} at {@code close} closes. */
    private static int enclosingOpenOf(List<Token> tokens, int close) {
        int depth = tokens.get(close).depth();
        for (int at = close - 1; at >= 0; at--) {
            if (tokens.get(at).punct("(") && tokens.get(at).depth() == depth) {
                return at;
            }
        }
        return -1;
    }

    /** The {@code (} enclosing the token at {@code index}. */
    private static int enclosingOpen(List<Token> tokens, int index) {
        int depth = tokens.get(index).depth();
        for (int at = index - 1; at >= 0; at--) {
            if (tokens.get(at).depth() < depth) {
                return at;
            }
        }
        return -1;
    }

    /**
     * The first token of the operand that ends at {@code end} and precedes a {@code ::} cast: a
     * parenthesized expression or call, an {@code ARRAY[…]} constructor or subscript, a dotted
     * name, or a single token, extended left through earlier casts ({@code 's'::text::regclass}).
     */
    private static int operandStart(List<Token> tokens, int end) {
        Token last = tokens.get(end);
        int start = end;
        if (last.punct(")")) {
            start = Math.max(0, enclosingOpenOf(tokens, end));
            if (start > 0 && tokens.get(start - 1).name()
                    && (tokens.get(start - 1).quoted() || !tokens.get(start - 1).keyword(NOT_FUNCTION_NAMES))) {
                start = nameStart(tokens, start - 1);
            }
        } else if (last.punct("]")) {
            int nesting = 0;
            for (int at = end; at >= 0; at--) {
                if (tokens.get(at).punct("]")) {
                    nesting++;
                } else if (tokens.get(at).punct("[") && --nesting == 0) {
                    start = at;
                    break;
                }
            }
            if (start > 0) {
                start = operandStart(tokens, start - 1);
            }
        } else if (last.name()) {
            start = nameStart(tokens, end);
            if (start > 0 && tokens.get(start).keyword("VARYING") && tokens.get(start - 1).keyword("CHARACTER")) {
                start--;
            }
        }
        if (start >= 2 && tokens.get(start - 1).punct("::")) {
            return operandStart(tokens, start - 2);
        }
        return start;
    }

    /** Words before {@code (} that start a parenthesized expression rather than call a function. */
    private static final Set<String> NOT_FUNCTION_NAMES = Set.of("SELECT", "WHERE", "AND", "OR", "NOT", "THEN",
            "ELSE", "WHEN", "RETURN", "RETURNING", "ON", "HAVING", "BY", "AS", "IS", "IN", "DISTINCT", "ALL", "ANY",
            "SOME", "VALUES", "LIKE", "ILIKE", "BETWEEN", "CASE", "USING", "PERFORM", "SET", "FROM", "JOIN", "OFFSET",
            "LIMIT", "INTO", "DO", "IF", "ELSIF", "WHILE", "RAISE", "ASSERT");

    private static int nameStart(List<Token> tokens, int end) {
        int start = end;
        while (start >= 2 && tokens.get(start - 1).punct(".") && tokens.get(start - 2).name()) {
            start -= 2;
        }
        return start;
    }

    /**
     * A {@code reg*} conversion of {@code from..to}: a literal is checked (and rejected for an
     * array type); an operand holding any other string constant is rejected; a column or oid
     * expression without one only yields an oid and is allowed.
     */
    private static void checkRegOperand(List<Token> tokens, int from, int to, Token type, NameKind kind,
                                        boolean array, NameCheck check) {
        Token literal = reducedLiteral(tokens, from, to);
        if (literal != null && !array) {
            check.require(literal, kind);
            return;
        }
        if (literal != null || containsStringConstant(tokens, from, to)) {
            throw new IllegalArgumentException("schema change SQL must convert a single string literal to "
                    + type.value() + (array ? "[]" : "") + " so the name can be checked" + check.where() + ": "
                    + summarize(check.sql()));
        }
    }

    private static NameKind relationCallKind(Token call) {
        String name = canonical(call.value(), call.quoted(), DatabaseDialect.POSTGRESQL);
        return switch (name) {
            case "to_regnamespace" -> NameKind.SCHEMA;
            case "to_regrole" -> NameKind.UNSCOPED;
            case "to_regproc", "to_regprocedure", "to_regtype", "to_regtypemod", "to_regoper", "to_regoperator",
                 "to_regcollation" -> NameKind.QUALIFIED;
            default -> NameKind.RELATION;
        };
    }

    /**
     * Scope-checks the name argument starting at {@code argument}: a string constant (in any
     * spelling, through parentheses and text or {@code reg*} casts) is checked. For a relation, a
     * nested {@code pg_get_serial_sequence(…)} call is allowed, since its own literal table
     * argument is checked when the scan reaches it. Otherwise the argument is rejected when
     * {@code required} or when it holds a string constant; a column or oid expression is allowed.
     */
    private static void checkNameArgument(List<Token> tokens, Token call, int argument, NameKind kind,
                                          NameCheck check, boolean required) {
        int end = argumentEnd(tokens, argument);
        if (kind == NameKind.RELATION && tokens.get(argument).name()) {
            int function = argument;
            if (argument + 2 <= end && tokens.get(argument + 1).punct(".")
                    && isSystemCatalog(tokens.get(argument), DatabaseDialect.POSTGRESQL)) {
                function = argument + 2;
            }
            if (function + 1 <= end && tokens.get(function + 1).punct("(")
                    && SqlTokenizer.matchingClose(tokens, function + 1) == end
                    && SqlTokenizer.nameMatches(tokens.get(function), SERIAL_SEQUENCE, SqlLexer.Mode.POSTGRES)) {
                return;
            }
        }
        Token literal = reducedLiteral(tokens, argument, end);
        if (literal != null) {
            check.require(literal, kind);
            return;
        }
        if (required || containsStringConstant(tokens, argument, end)) {
            throw check.notLiteral(call);
        }
    }

    /** The last token of the call argument starting at {@code argument}. */
    private static int argumentEnd(List<Token> tokens, int argument) {
        int depth = tokens.get(argument).depth();
        for (int at = argument + 1; at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.depth() < depth || (token.depth() == depth && token.punct(","))) {
                return at - 1;
            }
        }
        return tokens.size() - 1;
    }

    /**
     * {@code pg_input_is_valid(value, type)} / {@code pg_input_error_info}: the type name is checked;
     * when it is a {@code reg*} type the value is a name of that kind and checked too, and a
     * {@code reg*} array type or a type that is not a literal rejects a string-constant value.
     */
    private static void checkInputCheck(List<Token> tokens, Token call, int open, NameCheck check) {
        List<Integer> arguments = arguments(tokens, open);
        if (arguments.size() < 2) {
            return;
        }
        int valueAt = arguments.get(0);
        int typeAt = arguments.get(1);
        Token type = reducedLiteral(tokens, typeAt, argumentEnd(tokens, typeAt));
        if (type == null) {
            checkNameArgument(tokens, call, typeAt, NameKind.QUALIFIED, check, false);
            if (containsStringConstant(tokens, valueAt, argumentEnd(tokens, valueAt))) {
                throw check.notLiteral(call);
            }
            return;
        }
        String text = type.value().strip();
        boolean array = false;
        java.util.regex.Matcher suffix = ARRAY_TYPE_SUFFIX.matcher(text);
        if (suffix.find()) {
            array = true;
            text = text.substring(0, suffix.start()).strip();
        }
        check.require(new Token(type.type(), text, text, type.start(), type.end(), type.depth()), NameKind.QUALIFIED);
        List<Token> typeParts = relationNameParts(text);
        if (typeParts == null || typeParts.isEmpty()) {
            return;
        }
        Token typeName = typeParts.get(typeParts.size() - 1);
        String name = canonical(typeName.value(), typeName.quoted(), DatabaseDialect.POSTGRESQL);
        NameKind kind = regKind(name);
        if (kind == null) {
            return;
        }
        if (array || name.startsWith("_")) {
            if (containsStringConstant(tokens, valueAt, argumentEnd(tokens, valueAt))) {
                throw new IllegalArgumentException("schema change SQL must not check a literal array of "
                        + name + " names" + check.where() + ": " + summarize(check.sql()));
            }
            return;
        }
        checkNameArgument(tokens, call, valueAt, kind, check, false);
    }

    /** {@code []}, {@code [3]}, or {@code ARRAY [3]} after a type name in text form. */
    private static final Pattern ARRAY_TYPE_SUFFIX = Pattern.compile("(?i)(?:\\s*\\[\\s*\\d*\\s*]|\\s+array\\b.*)+$");

    /** The kind of a {@code reg*} type name, or of the element of its array type {@code _regclass}; null otherwise. */
    private static NameKind regKind(String name) {
        if (name == null) {
            return null;
        }
        return REGTYPES.get(name.startsWith("_") ? name.substring(1) : name);
    }

    /** The first token of each top-level argument of the call whose {@code (} is at {@code open}. */
    private static List<Integer> arguments(List<Token> tokens, int open) {
        List<Integer> result = new ArrayList<>();
        int close = matchingClose(tokens, open);
        int depth = tokens.get(open).depth() + 1;
        if (open + 1 < close) {
            result.add(open + 1);
        }
        for (int at = open + 1; at < close; at++) {
            if (tokens.get(at).depth() == depth && tokens.get(at).punct(",") && at + 1 < close) {
                result.add(at + 1);
            }
        }
        return result;
    }

    /** Whether the {@code AS} at {@code as} belongs to {@code CAST(… AS type)}. */
    private static boolean insideCast(List<Token> tokens, int as) {
        int depth = tokens.get(as).depth();
        for (int at = as - 1; at > 0; at--) {
            if (tokens.get(at).depth() < depth) {
                return tokens.get(at).punct("(") && tokens.get(at - 1).keyword("CAST");
            }
        }
        return false;
    }

    /** Checks name strings against the configured schema. */
    private record NameCheck(Namespace allowed, String configuredNamespace, String sql, String where) {
        void require(Token literal, NameKind kind) {
            switch (kind) {
                case RELATION -> requireRelationInScope(literal, allowed, configuredNamespace, sql, where);
                case SCHEMA -> {
                    List<Token> parts = relationNameParts(literal.value());
                    if (parts == null || parts.size() != 1) {
                        throw new IllegalArgumentException("schema change SQL must name a schema as one identifier"
                                + where + ": " + summarize(sql));
                    }
                    requireSchema(parts.get(0));
                }
                case QUALIFIED -> {
                    Matcher qualifier = NAME_QUALIFIER.matcher(literal.value());
                    while (qualifier.find()) {
                        String name = qualifier.group(1);
                        boolean quoted = name.startsWith("\"");
                        String value = quoted ? name.substring(1, name.length() - 1).replace("\"\"", "\"") : name;
                        requireSchema(new Token(quoted ? SqlTokenizer.Type.QUOTED : SqlTokenizer.Type.WORD,
                                value, value, 0, 0, 0));
                    }
                }
                case UNSCOPED -> {
                }
            }
        }

        IllegalArgumentException notLiteral(Token call) {
            return new IllegalArgumentException("schema change SQL must name the object of " + call.value()
                    + "(…) with a string literal" + where + ": " + summarize(sql));
        }

        void requireSchema(Token schema) {
            if (!allowed.matches(schema.value(), schema.quoted()) && !isSystemCatalog(schema, allowed.dialect())) {
                throw new IllegalArgumentException("schema change SQL targets namespace" + where + " '"
                        + schema.value() + "' but synchronizer is configured for '" + configuredNamespace
                        + "': " + summarize(sql));
            }
        }
    }

    /**
     * The index of the {@code reg*} type name of a type reference starting at {@code index}, bare or
     * qualified by {@code pg_catalog} / a system catalog ({@code pg_catalog.regclass}); -1 otherwise.
     */
    private static int regTypeChain(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size() || !tokens.get(index).name()) {
            return -1;
        }
        if (index + 2 < tokens.size() && tokens.get(index + 1).punct(".") && tokens.get(index + 2).name()
                && isSystemCatalog(tokens.get(index), DatabaseDialect.POSTGRESQL)
                && regTypeName(tokens.get(index + 2)) != null) {
            return index + 2;
        }
        return regTypeName(tokens.get(index)) != null ? index : -1;
    }

    /** The canonical {@code reg*} type name of a token, or null. */
    private static String regTypeName(Token token) {
        if (!token.name()) {
            return null;
        }
        String name = canonical(token.value(), token.quoted(), DatabaseDialect.POSTGRESQL);
        return regKind(name) != null ? name : null;
    }

    private static void requireRelationInScope(Token literal, Namespace allowed, String configuredNamespace,
                                               String sql, String where) {
        List<Token> parts = relationNameParts(literal.value());
        if (parts == null || parts.size() > 2) {
            throw new IllegalArgumentException("schema change SQL must name a relation as [schema.]name"
                    + where + ": " + summarize(sql));
        }
        if (parts.size() == 2) {
            Token schema = parts.get(0);
            if (!allowed.matches(schema.value(), schema.quoted()) && !isSystemCatalog(schema, allowed.dialect())) {
                throw new IllegalArgumentException(
                        "schema change SQL targets namespace" + where + " '" + schema.value()
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
    }

    /** The dot-separated parts of a PostgreSQL qualified name in text form; null when malformed. */
    private static List<Token> relationNameParts(String text) {
        List<Token> parts = new ArrayList<>();
        int index = 0;
        while (true) {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '"') {
                StringBuilder name = new StringBuilder();
                index++;
                while (true) {
                    if (index >= text.length()) {
                        return null;
                    }
                    if (text.charAt(index) == '"') {
                        if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                            name.append('"');
                            index += 2;
                            continue;
                        }
                        index++;
                        break;
                    }
                    name.append(text.charAt(index++));
                }
                parts.add(new Token(SqlTokenizer.Type.QUOTED, name.toString(), name.toString(), 0, 0, 0));
            } else {
                int start = index;
                while (index < text.length() && text.charAt(index) != '.' && !Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
                if (start == index) {
                    return null;
                }
                String name = text.substring(start, index);
                parts.add(new Token(SqlTokenizer.Type.WORD, name, name, 0, 0, 0));
            }
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index == text.length()) {
                return parts;
            }
            if (text.charAt(index) != '.') {
                return null;
            }
            index++;
        }
    }

    /**
     * The name of a {@code FUNCTION}/{@code PROCEDURE} and its parameter names, which qualify
     * record parameters and parameters ({@code f.p}) inside the body.
     */
    private static List<Token> routineNames(List<Token> tokens, SqlLexer.Mode mode) {
        List<Token> names = new ArrayList<>();
        SqlTokenizer.Routine routine = SqlTokenizer.routine(tokens, mode);
        if (routine == null || !(routine.kind().equals("FUNCTION") || routine.kind().equals("PROCEDURE")
                || routine.kind().equals("PROC"))) {
            return names;
        }
        Chain name = chainAt(tokens, routine.kindIndex() + 1, mode);
        if (name == null) {
            return names;
        }
        names.add(name.parts().get(name.parts().size() - 1));
        int open = name.last() + 1;
        if (open >= tokens.size() || !tokens.get(open).punct("(")) {
            return names;
        }
        names.addAll(parameterNames(tokens, open));
        return names;
    }

    /**
     * The parameter names in the list opened at {@code open}. A parameter without a name
     * ({@code f(other.t)}, {@code f(int)}) is a bare type: its first word is followed by
     * {@code .}, {@code (}, {@code [}, {@code %}, {@code ,}, or {@code )}, not by a type word.
     */
    private static List<Token> parameterNames(List<Token> tokens, int open) {
        List<Token> names = new ArrayList<>();
        int close = matchingClose(tokens, open);
        int depth = tokens.get(open).depth() + 1;
        boolean itemStart = true;
        for (int at = open + 1; at < close; at++) {
            Token token = tokens.get(at);
            if (token.depth() == depth && token.punct(",")) {
                itemStart = true;
            } else if (itemStart && !token.keyword(PARAMETER_MODES)) {
                Token next = at + 1 < close ? tokens.get(at + 1) : null;
                if (token.name() && next != null && next.name()) {
                    names.add(token);
                }
                itemStart = false;
            }
        }
        return names;
    }

    /**
     * The header of a PostgreSQL, Oracle, or SQL Server {@code FUNCTION}/{@code PROCEDURE} after
     * its name — parameters and return type, up to {@code AS}/{@code IS} (PostgreSQL also
     * {@code BEGIN ATOMIC} or {@code RETURN}) — where every dotted name is a type; null otherwise.
     * No correlation is declared there, so no alias, parameter, or routine name qualifies it.
     */
    private static int[] routineHeader(List<Token> tokens, SqlLexer.Mode mode, DatabaseDialect dialect) {
        if (dialect == DatabaseDialect.MYSQL || dialect == DatabaseDialect.MARIADB) {
            return null;
        }
        SqlTokenizer.Routine routine = SqlTokenizer.routine(tokens, mode);
        if (routine == null || !(routine.kind().equals("FUNCTION") || routine.kind().equals("PROCEDURE")
                || routine.kind().equals("PROC"))) {
            return null;
        }
        Chain name = chainAt(tokens, routine.kindIndex() + 1, mode);
        int from = name == null ? routine.kindIndex() + 1 : name.last() + 1;
        for (int at = from; at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.depth() == 0 && (token.keyword("AS") || token.keyword("IS")
                    || (dialect == DatabaseDialect.POSTGRESQL && (token.keyword("BEGIN") || token.keyword("RETURN"))))) {
                return new int[] {from, at - 1};
            }
        }
        return new int[] {from, tokens.size() - 1};
    }

    private static final Set<String> PARAMETER_MODES = Set.of("IN", "OUT", "INOUT", "VARIADIC", "NOCOPY");

    /**
     * Whether a chain stands in a column expression: not where an object belongs and not called.
     * MySQL {@code ON DUPLICATE KEY UPDATE t.c = …} assigns columns.
     */
    private static boolean columnExpression(List<Token> tokens, Chain chain, ListOwners lists) {
        int at = previousSignificant(tokens, chain.first());
        if (at > 0 && tokens.get(at).keyword("UPDATE") && tokens.get(at - 1).keyword("KEY")) {
            Token after = SqlTokenizer.next(tokens, chain.last());
            return after == null || !after.punct("(");
        }
        return !objectPosition(tokens, chain, lists);
    }

    /**
     * Correlation names in scope at each token, so {@code alias.column} in an expression is not
     * read as {@code schema.object}.
     * <ul>
     *   <li>Per statement (ending at {@code ;} and, on SQL Server, where the engine starts the
     *       next statement): the table name and alias of every {@code FROM}, {@code JOIN},
     *       {@code UPDATE}, {@code DELETE}, {@code INSERT [INTO]}, {@code MERGE [INTO]},
     *       {@code USING} and {@code APPLY} reference, including derived tables, table functions
     *       ({@code OPENJSON(…) WITH (…) AS j}), and SQL Server table variables, plus the MySQL
     *       row alias {@code VALUES (…) AS new}. A name declared inside parentheses resolves
     *       only inside them, and one declared in a {@code UNION}/{@code INTERSECT}/
     *       {@code EXCEPT}/{@code MINUS} branch only in that branch.</li>
     *   <li>Per routine: the routine's own name and parameter names, PostgreSQL and Oracle
     *       {@code DECLARE} (and Oracle {@code IS}/{@code AS}) variables, and loop records of
     *       {@code FOR r IN …}. On Oracle a block variable resolves only inside its block and a
     *       loop record only inside its loop, since an out-of-scope {@code v.f} is a call of
     *       function {@code f} in schema {@code v}.</li>
     * </ul>
     * CTE names are not correlations: a query block reaches a CTE through {@code FROM}, which
     * declares it. {@code SELECT … INTO v} and {@code FETCH … INTO v} name variables and are not
     * declared.
     */
    private static final class Correlations {
        private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");
        private static final Set<String> NOT_VARIABLES = Set.of("TYPE", "SUBTYPE", "PRAGMA", "PROCEDURE",
                "FUNCTION", "BEGIN", "EXCEPTION", "END");

        private final List<Token> tokens;
        private final SqlLexer.Mode mode;
        private final DatabaseDialect dialect;
        private final boolean routine;
        private final int[] statementOf;
        private final List<List<Declared>> declared = new ArrayList<>();
        private final List<Boolean> output = new ArrayList<>();
        private final List<Boolean> conflict = new ArrayList<>();
        private final List<Declared> ranged = new ArrayList<>();
        /** The routine name and block labels: the qualifiers PL/pgSQL resolves in a three-part name. */
        private final List<Declared> blocks = new ArrayList<>();
        /** Token ranges of routine headers and declaration types, where a dotted name is {@code schema.type}. */
        private final List<int[]> typeRegions = new ArrayList<>();
        /** Routine and nested-subprogram headers, where the routine's own parameters are not yet in scope. */
        private final List<int[]> headers = new ArrayList<>();
        /** Table aliases (not table names) of each reference, with the statement range they cover. */
        private final List<Declared> aliases = new ArrayList<>();
        private final List<Token> referencing = new ArrayList<>();
        private int whenFrom = -1;
        private int whenTo = -1;

        Correlations(List<Token> tokens, SqlLexer.Mode mode, DatabaseDialect dialect, boolean routine,
                     ListOwners objectLists, List<Token> inherited) {
            this.tokens = tokens;
            this.mode = mode;
            this.dialect = dialect;
            this.routine = routine;
            this.statementOf = new int[tokens.size()];
            int last = tokens.size() - 1;
            inherited.forEach(name -> ranged.add(new Declared(name, 0, last)));
            if (!inherited.isEmpty()) {
                blocks.add(new Declared(inherited.get(0), 0, last));
            }
            if (routine) {
                List<Token> header = routineNames(tokens, mode);
                header.forEach(name -> ranged.add(new Declared(name, 0, last)));
                if (!header.isEmpty()) {
                    blocks.add(new Declared(header.get(0), 0, last));
                }
                int[] typed = routineHeader(tokens, mode, dialect);
                if (typed != null) {
                    typeRegions.add(typed);
                    headers.add(new int[] {0, typed[1]});
                }
                declareRoutineVariables();
            }
            for (int[] range : statements(tokens, mode)) {
                int statement = declared.size();
                List<Declared> names = new ArrayList<>();
                boolean hasOutput = false;
                boolean hasConflict = false;
                for (int index = range[0]; index < range[1]; index++) {
                    statementOf[index] = statement;
                    Token token = tokens.get(index);
                    hasOutput |= token.keyword("OUTPUT");
                    hasConflict |= token.keyword("CONFLICT");
                    if (declaresReference(tokens, index, range[0], objectLists)) {
                        List<Token> found = new ArrayList<>();
                        Token alias = declareReference(tokens, index + 1, range[1], mode, found);
                        int[] scope = scope(tokens, index, range);
                        found.forEach(name -> names.add(new Declared(name, scope[0], scope[1])));
                        if (alias != null) {
                            aliases.add(new Declared(alias, scope[0], scope[1]));
                        }
                    } else if ((dialect == DatabaseDialect.MYSQL || dialect == DatabaseDialect.MARIADB)
                            && token.keyword("AS") && rowAlias(tokens, index, range[0])
                            && index + 1 < range[1]) {
                        int[] scope = scope(tokens, index, range);
                        names.add(new Declared(tokens.get(index + 1), scope[0], scope[1]));
                    } else if (token.keyword("REFERENCING") && dialect == DatabaseDialect.ORACLE) {
                        referencingAliases(tokens, index + 1, range[1]);
                    }
                }
                declared.add(names);
                output.add(hasOutput);
                conflict.add(hasConflict);
            }
        }

        private record Declared(Token name, int from, int to) {
        }

        /**
         * A name declared inside parentheses resolves only inside them, otherwise in the whole
         * statement, and in either case only within its own set-operation branch.
         */
        private static int[] scope(List<Token> tokens, int index, int[] statement) {
            int depth = tokens.get(index).depth();
            int from = statement[0];
            int to = statement[1] - 1;
            for (int at = index - 1; at >= statement[0] && depth > 0; at--) {
                if (tokens.get(at).punct("(") && tokens.get(at).depth() == depth - 1) {
                    from = at;
                    to = matchingClose(tokens, at);
                    break;
                }
            }
            for (int at = index - 1; at > from; at--) {
                if (tokens.get(at).depth() == depth && tokens.get(at).keyword(SET_OPERATORS)) {
                    from = at;
                    break;
                }
            }
            for (int at = index + 1; at < to; at++) {
                if (tokens.get(at).depth() == depth && tokens.get(at).keyword(SET_OPERATORS)) {
                    to = at;
                    break;
                }
            }
            return new int[] {from, to};
        }

        /** MySQL {@code INSERT … VALUES (…)[, (…)] AS alias}: {@code as} follows the last row. */
        private static boolean rowAlias(List<Token> tokens, int as, int start) {
            if (as + 1 >= tokens.size() || !tokens.get(as + 1).name()) {
                return false;
            }
            int close = as - 1;
            while (close > start && tokens.get(close).punct(")")) {
                int open = matchingOpen(tokens, close);
                int before = open - 1;
                if (before > start && tokens.get(before).keyword("ROW")) {
                    before--;
                }
                if (before <= start) {
                    return false;
                }
                if (tokens.get(before).keyword("VALUES") || tokens.get(before).keyword("VALUE")) {
                    return true;
                }
                if (!tokens.get(before).punct(",")) {
                    return false;
                }
                close = before - 1;
            }
            return false;
        }

        /** Loop records, block and routine-header variables, and the Oracle trigger {@code WHEN} condition. */
        private void declareRoutineVariables() {
            SqlTokenizer.Routine header = SqlTokenizer.routine(tokens, mode);
            if (dialect == DatabaseDialect.ORACLE && header != null) {
                oracleHeader(header);
            }
            for (int index = 0; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if ((token.keyword("FOR") || token.keyword("FOREACH")) && index + 2 < tokens.size()
                        && tokens.get(index + 1).type() == SqlTokenizer.Type.WORD
                        && (tokens.get(index + 2).keyword("IN") || tokens.get(index + 2).keyword("SLICE"))) {
                    ranged.add(new Declared(tokens.get(index + 1), index, loopEnd(index)));
                } else if (token.keyword("DECLARE")
                        && (dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.ORACLE)) {
                    declarations(index + 1, false);
                } else if (token.punct("<<") && index + 2 < tokens.size()
                        && tokens.get(index + 1).type() == SqlTokenizer.Type.WORD && tokens.get(index + 2).punct(">>")
                        && (dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.ORACLE)) {
                    int end = labelEnd(index + 3);
                    if (end >= index) {
                        ranged.add(new Declared(tokens.get(index + 1), index, end));
                        blocks.add(new Declared(tokens.get(index + 1), index, end));
                    }
                }
            }
        }

        /**
         * The end of a block label's scope ({@code <<outer>> DECLARE … BEGIN … END}, or a labelled
         * loop): the routine on PostgreSQL, the labelled block or loop on Oracle. A label before a
         * plain statement (a {@code GOTO} target) qualifies nothing: -1.
         */
        private int labelEnd(int at) {
            if (at >= tokens.size()) {
                return -1;
            }
            Token first = tokens.get(at);
            boolean block = first.keyword("DECLARE") || first.keyword("BEGIN");
            boolean loop = first.keyword("FOR") || first.keyword("WHILE") || first.keyword("LOOP")
                    || first.keyword("FOREACH");
            if (!block && !loop) {
                return -1;
            }
            if (dialect != DatabaseDialect.ORACLE) {
                return tokens.size() - 1;
            }
            if (first.keyword("LOOP")) {
                return SqlTokenizer.blockEnd(tokens, at, mode);
            }
            if (loop) {
                return loopEnd(at);
            }
            int depth = first.depth();
            for (int begin = at; begin < tokens.size(); begin++) {
                if (tokens.get(begin).depth() == depth && tokens.get(begin).keyword("BEGIN")) {
                    return SqlTokenizer.blockEnd(tokens, begin, mode);
                }
            }
            return -1;
        }

        /**
         * A chain whose first part is a routine-level name in scope: on Oracle, a field chain
         * ({@code r.addr.city}) anywhere, since a SQL statement resolves a name no table in its
         * {@code FROM} provides to the local item, and outside a SQL statement also
         * {@code v.method(…)} and collection methods ({@code t.EXISTS(1)}, {@code t.DELETE}); on
         * PostgreSQL an uncalled {@code label.record.field} / {@code routine.param.field}, because
         * PL/pgSQL reads any other three-part name as {@code schema.table.column}. Object positions
         * are never routine items.
         */
        boolean routineItemChain(Chain chain, ListOwners lists) {
            Token qualifier = chain.parts().get(0);
            int index = chain.first();
            if (objectKeywordPosition(tokens, chain, lists) || !declaredIn(ranged, index, qualifier)) {
                return false;
            }
            Token after = SqlTokenizer.next(tokens, chain.last());
            boolean called = after != null && after.punct("(");
            return switch (dialect) {
                case ORACLE -> procedural(index) || !called;
                case POSTGRESQL -> chain.parts().size() == 3 && !called && declaredIn(blocks, index, qualifier);
                default -> false;
            };
        }

        /** Whether the qualifier is a table alias declared in the same query block and branch. */
        boolean aliasDeclares(int index, Token qualifier) {
            return declaredIn(aliases, index, qualifier);
        }

        /**
         * Whether the qualifier is a routine item or block label in scope at the index. In a
         * routine or nested-subprogram header (parameter list and return type) that routine's own
         * name and parameters are not yet in scope — Oracle resolves {@code p.t.c%TYPE} there to
         * schema {@code p} — so only items declared before the header count.
         */
        boolean routineItem(int index, Token qualifier) {
            int headerStart = headers.stream().filter(region -> region[0] <= index && index <= region[1])
                    .mapToInt(region -> region[0]).max().orElse(-1);
            return java.util.stream.Stream.concat(ranged.stream(), blocks.stream())
                    .anyMatch(name -> name.from() <= index && index <= name.to()
                            && (headerStart < 0 || name.from() < headerStart)
                            && sameCorrelation(name.name(), qualifier));
        }

        private boolean declaredIn(List<Declared> names, int index, Token qualifier) {
            return names.stream().anyMatch(name -> name.from() <= index && index <= name.to()
                    && sameCorrelation(name.name(), qualifier));
        }

        private static final Set<String> SQL_STATEMENT_WORDS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE",
                "MERGE", "WITH", "VALUES");
        /** Words that start PL/SQL code and never occur inside a SQL statement ({@code THEN} does, in CASE). */
        private static final Set<String> PROCEDURAL_BOUNDARIES = Set.of("LOOP", "BEGIN", "DECLARE", "EXCEPTION");

        /**
         * Whether the token is PL/SQL code rather than part of a SQL statement: walking back to the
         * statement start ({@code ;} or a block word at its depth) meets no SQL statement word.
         * Inside SQL, Oracle resolves {@code a.b(…)} as schema {@code a}'s function before a local.
         */
        private boolean procedural(int index) {
            int depth = tokens.get(index).depth();
            for (int at = index - 1; at >= 0; at--) {
                Token token = tokens.get(at);
                if (token.depth() > depth) {
                    continue;
                }
                if (token.keyword(SQL_STATEMENT_WORDS) && !(at > 0 && tokens.get(at - 1).punct("."))) {
                    return false;
                }
                if (token.depth() < depth) {
                    depth = token.depth();
                    continue;
                }
                if (token.punct(";") || token.keyword(PROCEDURAL_BOUNDARIES)) {
                    return true;
                }
            }
            return true;
        }

        /** Oracle routine-header variables ({@code FUNCTION f … IS v t; BEGIN}) and the trigger {@code WHEN}. */
        private void oracleHeader(SqlTokenizer.Routine header) {
            boolean subprogram = header.kind().equals("FUNCTION") || header.kind().equals("PROCEDURE");
            for (int index = header.kindIndex() + 1; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if (token.depth() != 0) {
                    continue;
                }
                if (subprogram && (token.keyword("IS") || token.keyword("AS"))) {
                    declarations(index + 1, true);
                    return;
                }
                if (token.keyword("COMPOUND") && index + 1 < tokens.size() && tokens.get(index + 1).keyword("TRIGGER")) {
                    compoundTrigger(index + 2);
                    return;
                }
                if (token.keyword("BEGIN") || token.keyword("DECLARE") || token.keyword("CALL")) {
                    return;
                }
                if (header.kind().equals("TRIGGER") && token.keyword("WHEN") && index + 1 < tokens.size()
                        && tokens.get(index + 1).punct("(")) {
                    whenFrom = index + 1;
                    whenTo = matchingClose(tokens, index + 1);
                }
            }
        }

        /**
         * An Oracle compound trigger: the declaration section before the first timing point
         * resolves in every timing-point section; each {@code BEFORE|AFTER|INSTEAD OF … IS}
         * section's own declarations resolve until that section's {@code END}.
         */
        private void compoundTrigger(int from) {
            int sections = declarations(from, true, true);
            for (int index = sections; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if (token.depth() == 0 && token.keyword("IS") && index > 0
                        && (tokens.get(index - 1).keyword("ROW") || tokens.get(index - 1).keyword("STATEMENT"))) {
                    declarations(index + 1, false);
                }
            }
        }

        private static final Set<String> TIMING_POINTS = Set.of("BEFORE", "AFTER", "INSTEAD");

        /**
         * Declares {@code name type …;} items up to {@code BEGIN} (or, in a compound trigger's
         * declaration section, the first timing point) and returns where the section ends. Nested
         * {@code PROCEDURE}/{@code FUNCTION} bodies are skipped. On Oracle a {@code DECLARE}
         * section's names resolve until its block's {@code END}; header names resolve in the
         * whole routine.
         */
        private int declarations(int from, boolean routineWide) {
            return declarations(from, routineWide, false);
        }

        private int declarations(int from, boolean routineWide, boolean compound) {
            List<Token> names = new ArrayList<>();
            int at = from;
            int sectionDepth = from < tokens.size() ? tokens.get(from).depth() : 0;
            while (at < tokens.size() && !tokens.get(at).keyword("BEGIN")
                    && !(compound && tokens.get(at).depth() == sectionDepth && tokens.get(at).keyword(TIMING_POINTS))) {
                Token first = tokens.get(at);
                if (first.keyword("PROCEDURE") || first.keyword("FUNCTION")) {
                    at = nestedSubprogram(at) + 1;
                    continue;
                }
                int nameAt = first.keyword("CURSOR") ? at + 1 : at;
                if (nameAt < tokens.size() && tokens.get(nameAt).type() == SqlTokenizer.Type.WORD
                        && !first.keyword(NOT_VARIABLES)) {
                    names.add(tokens.get(nameAt));
                }
                int depth = first.depth();
                int itemEnd = at;
                while (itemEnd < tokens.size() && !(tokens.get(itemEnd).depth() == depth && tokens.get(itemEnd).punct(";"))
                        && !tokens.get(itemEnd).keyword("BEGIN")) {
                    itemEnd++;
                }
                declarationType(first, at, nameAt, Math.min(itemEnd, tokens.size() - 1));
                at = itemEnd;
                if (at < tokens.size() && tokens.get(at).punct(";")) {
                    at++;
                }
            }
            int end = tokens.size() - 1;
            if (dialect == DatabaseDialect.ORACLE && !routineWide) {
                end = at < tokens.size() ? SqlTokenizer.blockEnd(tokens, at, mode) : from;
            }
            int scopeEnd = end;
            names.forEach(name -> ranged.add(new Declared(name, from, scopeEnd)));
            return at;
        }

        private static final Set<String> TYPE_STOPS = Set.of("DEFAULT", "NOT", "FOR", "IS");

        /**
         * Records where a declaration item names types: all of a {@code TYPE}/{@code SUBTYPE}
         * item, a cursor's parameters and {@code RETURN} type, and a variable's type up to its
         * default, {@code NOT NULL}, or cursor query.
         */
        private void declarationType(Token first, int at, int nameAt, int itemEnd) {
            if (first.keyword("TYPE") || first.keyword("SUBTYPE")) {
                typeRegions.add(new int[] {at, itemEnd});
                return;
            }
            if (first.keyword("PRAGMA") || first.keyword(NOT_VARIABLES)) {
                return;
            }
            int depth = first.depth();
            int stop = itemEnd;
            for (int index = nameAt + 1; index <= itemEnd; index++) {
                Token token = tokens.get(index);
                if (token.depth() == depth && (token.punct(":=") || token.punct("=") || token.keyword(TYPE_STOPS))) {
                    stop = index;
                    break;
                }
            }
            typeRegions.add(new int[] {nameAt + 1, stop});
        }

        /**
         * A PL/SQL subprogram nested in a declaration section. Its header names types; its
         * parameters, own name, and locals resolve only inside its body. Returns its last token.
         */
        private int nestedSubprogram(int start) {
            int depth = tokens.get(start).depth();
            Chain name = chainAt(tokens, start + 1, mode);
            int after = name == null ? start + 1 : name.last() + 1;
            List<Token> items = new ArrayList<>();
            if (name != null) {
                items.add(name.parts().get(name.parts().size() - 1));
            }
            if (after < tokens.size() && tokens.get(after).punct("(")) {
                items.addAll(parameterNames(tokens, after));
            }
            for (int at = after; at < tokens.size(); at++) {
                Token token = tokens.get(at);
                if (token.depth() != depth) {
                    continue;
                }
                if (token.punct(";")) {
                    typeRegions.add(new int[] {start, at});
                    headers.add(new int[] {start, at});
                    return at;
                }
                if (token.keyword("IS") || token.keyword("AS")) {
                    typeRegions.add(new int[] {start, at});
                    headers.add(new int[] {start, at});
                    int begin = declarations(at + 1, false);
                    int end = begin < tokens.size() ? SqlTokenizer.blockEnd(tokens, begin, mode) : tokens.size() - 1;
                    if (end <= begin) {
                        end = tokens.size() - 1;
                    }
                    int scopeEnd = end;
                    items.forEach(item -> ranged.add(new Declared(item, start, scopeEnd)));
                    if (name != null) {
                        blocks.add(new Declared(name.parts().get(name.parts().size() - 1), start, scopeEnd));
                    }
                    if (end + 1 < tokens.size() && tokens.get(end + 1).name() && end + 2 < tokens.size()
                            && tokens.get(end + 2).punct(";")) {
                        return end + 2;
                    }
                    return end + 1 < tokens.size() && tokens.get(end + 1).punct(";") ? end + 1 : end;
                }
            }
            return tokens.size() - 1;
        }

        /** Whether the token lies where a routine header or declaration names a type. */
        boolean typeRegion(int index) {
            return typeRegions.stream().anyMatch(region -> region[0] <= index && index <= region[1]);
        }

        /** The end of a loop record's scope: its {@code END LOOP} on Oracle, the routine elsewhere. */
        private int loopEnd(int loop) {
            if (dialect != DatabaseDialect.ORACLE) {
                return tokens.size() - 1;
            }
            int depth = tokens.get(loop).depth();
            for (int at = loop + 1; at < tokens.size(); at++) {
                if (tokens.get(at).depth() == depth && tokens.get(at).keyword("LOOP")) {
                    return SqlTokenizer.blockEnd(tokens, at, mode);
                }
            }
            return loop;
        }

        private static List<int[]> statements(List<Token> tokens, SqlLexer.Mode mode) {
            List<int[]> result = new ArrayList<>();
            int start = 0;
            for (int index = 0; index <= tokens.size(); index++) {
                if (index < tokens.size() && !tokens.get(index).punct(";")) {
                    continue;
                }
                int end = Math.min(index + 1, tokens.size());
                if (mode == SqlLexer.Mode.SQLSERVER) {
                    List<Token> chunk = tokens.subList(0, end);
                    for (int at = start; at < end; ) {
                        int next = Math.max(SqlTokenizer.sqlServerStatementEnd(chunk, at), at + 1);
                        result.add(new int[] {at, next});
                        at = next;
                    }
                } else if (start < end) {
                    result.add(new int[] {start, end});
                }
                start = end;
            }
            return result;
        }

        private static boolean declaresReference(List<Token> tokens, int index, int start, ListOwners lists) {
            Token token = tokens.get(index);
            Token previous = index > start ? tokens.get(index - 1) : null;
            if (token.keyword("FROM")) {
                return !operandKeyword(tokens, index);
            }
            if (token.keyword("JOIN") || token.keyword("USING") || token.keyword("APPLY")) {
                return true;
            }
            if (token.keyword("UPDATE") || token.keyword("DELETE")) {
                return previous == null || !(previous.punct(",") || previous.keyword(NON_STATEMENT_WRITE_PREDECESSORS));
            }
            if (token.keyword("INTO")) {
                return previous != null && (previous.keyword("INSERT") || previous.keyword("MERGE")
                        || previous.keyword("IGNORE") || previous.keyword("LOW_PRIORITY")
                        || previous.keyword("DELAYED") || previous.keyword("HIGH_PRIORITY"));
            }
            if (token.keyword("INSERT") || token.keyword("MERGE")) {
                return previous == null || !previous.keyword(NON_STATEMENT_WRITE_PREDECESSORS);
            }
            return token.punct(",") && lists.owns(index);
        }

        /** Declares the table name and alias of the reference after an owner keyword; returns the alias. */
        private static Token declareReference(List<Token> tokens, int from, int end, SqlLexer.Mode mode,
                                              List<Token> names) {
            int at = skipTargetModifiers(tokens, from);
            if (at >= end) {
                return null;
            }
            int last;
            if (tokens.get(at).punct("(")) {
                last = matchingClose(tokens, at);
            } else if (tokens.get(at).type() == SqlTokenizer.Type.VARIABLE && mode == SqlLexer.Mode.SQLSERVER) {
                last = at;
            } else {
                Chain chain = chainAt(tokens, at, mode);
                if (chain == null) {
                    return null;
                }
                names.add(chain.parts().get(chain.parts().size() - 1));
                last = chain.last();
                if (last + 1 < end && tokens.get(last + 1).punct("(")) {
                    last = matchingClose(tokens, last + 1);
                    if (mode == SqlLexer.Mode.SQLSERVER && last + 2 < end && tokens.get(last + 1).keyword("WITH")
                            && tokens.get(last + 2).punct("(")) {
                        last = matchingClose(tokens, last + 2);
                    }
                }
            }
            int alias = last + 1;
            if (alias < end && tokens.get(alias).keyword("AS")) {
                alias++;
            }
            if (alias < end && tokens.get(alias).name() && !tokens.get(alias).keyword(NOT_ALIASES)
                    && !tokens.get(alias).keyword("AS")) {
                names.add(tokens.get(alias));
                return tokens.get(alias);
            }
            return null;
        }

        /** Oracle {@code REFERENCING NEW AS n OLD AS o PARENT AS p}. */
        private void referencingAliases(List<Token> tokens, int from, int end) {
            int at = from;
            while (at + 1 < end && (tokens.get(at).keyword("NEW") || tokens.get(at).keyword("OLD")
                    || tokens.get(at).keyword("PARENT"))) {
                int alias = tokens.get(at + 1).keyword("AS") ? at + 2 : at + 1;
                if (alias >= end || !tokens.get(alias).name()) {
                    return;
                }
                referencing.add(tokens.get(alias));
                at = alias + 1;
            }
        }

        /**
         * Trigger rows of a two-part name: {@code NEW}/{@code OLD} in PostgreSQL and MySQL/MariaDB
         * routines; on Oracle {@code :NEW}/{@code :OLD}/{@code :PARENT} and {@code :alias} of a
         * {@code REFERENCING} clause, written with the colon, or bare only inside the trigger's
         * {@code WHEN (…)} condition (which cannot call functions); SQL Server
         * {@code inserted}/{@code deleted} in a trigger or an {@code OUTPUT} clause; PostgreSQL
         * {@code EXCLUDED} in an {@code ON CONFLICT} statement.
         */
        boolean pseudoRow(Chain chain) {
            Token qualifier = chain.parts().get(0);
            int index = chain.first();
            if (qualifier.quoted() || chain.parts().size() != 2) {
                return false;
            }
            String name = qualifier.value();
            if (dialect == DatabaseDialect.POSTGRESQL && conflict.get(statementOf[index])
                    && "excluded".equalsIgnoreCase(name)) {
                return true;
            }
            if (dialect == DatabaseDialect.SQLSERVER) {
                return (routine || output.get(statementOf[index]))
                        && ("inserted".equalsIgnoreCase(name) || "deleted".equalsIgnoreCase(name));
            }
            if (!routine) {
                return false;
            }
            boolean row = "new".equalsIgnoreCase(name) || "old".equalsIgnoreCase(name);
            if (dialect != DatabaseDialect.ORACLE) {
                return row;
            }
            boolean candidate = row || "parent".equalsIgnoreCase(name)
                    || referencing.stream().anyMatch(alias -> sameCorrelation(alias, qualifier));
            boolean colon = index > 0 && tokens.get(index - 1).punct(":")
                    && tokens.get(index - 1).end() == qualifier.start();
            return candidate && (colon || (whenFrom <= index && index <= whenTo));
        }

        boolean declares(int index, Token qualifier) {
            return declared.get(statementOf[index]).stream()
                    .anyMatch(name -> name.from() <= index && index <= name.to()
                            && sameCorrelation(name.name(), qualifier))
                    || ranged.stream().anyMatch(name -> name.from() <= index && index <= name.to()
                            && sameCorrelation(name.name(), qualifier));
        }

        /**
         * PostgreSQL and Oracle fold unquoted names and compare quoted names exactly. MySQL/MariaDB
         * table aliases are case-sensitive where table names are (the default on Linux), so they
         * compare exactly. SQL Server resolves aliases through the database collation, which is
         * case-insensitive by default; a looser match there can only accept a name the engine
         * reports as unresolved, since a two-part name in a SQL Server expression is always
         * {@code correlation.column}.
         */
        private boolean sameCorrelation(Token declaredName, Token qualifier) {
            return switch (dialect) {
                case POSTGRESQL, ORACLE -> canonical(declaredName.value(), declaredName.quoted(), dialect)
                        .equals(canonical(qualifier.value(), qualifier.quoted(), dialect));
                case MYSQL, MARIADB -> declaredName.value().equals(qualifier.value());
                case SQLSERVER -> declaredName.value().equalsIgnoreCase(qualifier.value());
            };
        }
    }

    /** Whether a system-catalog name is written or defined rather than read. */
    private static boolean writeTarget(List<Token> tokens, int first, ListOwners lists) {
        int at = previousSignificant(tokens, first);
        if (at < 0) {
            return false;
        }
        Token previous = tokens.get(at);
        if (previous.keyword("ON")) {
            return !joinCondition(tokens, at);
        }
        if (previous.keyword("FUNCTION") || previous.keyword("PROCEDURE")) {
            // EXECUTE FUNCTION pg_catalog.f() in a trigger calls a function; it does not define one.
            return !(at > 0 && tokens.get(at - 1).keyword("EXECUTE"));
        }
        if (previous.keyword(WRITE_KEYWORDS)) {
            return true;
        }
        return previous.punct(",") && lists.owns(at);
    }

    /** Index of the token before {@code index}, skipping object modifiers and {@code TOP (…)} / {@code TOP n}. */
    private static int previousSignificant(List<Token> tokens, int index) {
        int at = index - 1;
        while (at >= 0) {
            Token token = tokens.get(at);
            if (token.keyword(OBJECT_MODIFIERS)) {
                at--;
            } else if (token.punct(")")) {
                int open = matchingOpen(tokens, at);
                if (open > 0 && tokens.get(open - 1).keyword("TOP")) {
                    at = open - 2;
                } else {
                    return at;
                }
            } else if (token.type() == SqlTokenizer.Type.NUMBER && at > 0 && tokens.get(at - 1).keyword("TOP")) {
                at -= 2;
            } else {
                return at;
            }
        }
        return -1;
    }

    private static int matchingOpen(List<Token> tokens, int close) {
        int depth = tokens.get(close).depth();
        for (int at = close - 1; at >= 0; at--) {
            if (tokens.get(at).punct("(") && tokens.get(at).depth() == depth) {
                return at;
            }
        }
        return -1;
    }

    private static int matchingClose(List<Token> tokens, int open) {
        return SqlTokenizer.matchingClose(tokens, open);
    }

    /** Whether the {@code ON} at {@code index} is a join condition (after JOIN / MERGE … USING / APPLY). */
    private static boolean joinCondition(List<Token> tokens, int index) {
        int depth = tokens.get(index).depth();
        for (int at = index - 1; at >= 0; at--) {
            Token token = tokens.get(at);
            if (token.depth() < depth || token.punct(";")) {
                return false;
            }
            if (token.depth() > depth) {
                continue;
            }
            if (token.keyword(JOIN_KEYWORDS)) {
                return true;
            }
            if (token.keyword(ON_OWNERS)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether a comma separates items of a list owned by one of {@code owners}. A comma the
     * backward walk passes at the same depth has the same answer, so answers are kept per
     * comma and a list of n items is walked once rather than n times.
     */
    private static final class ListOwners {
        private final List<Token> tokens;
        private final Set<String> owners;
        private final byte[] known;

        ListOwners(List<Token> tokens, Set<String> owners) {
            this.tokens = tokens;
            this.owners = owners;
            this.known = new byte[tokens.size()];
        }

        boolean owns(int comma) {
            if (known[comma] != 0) {
                return known[comma] == 1;
            }
            int depth = tokens.get(comma).depth();
            boolean result = false;
            int stop = -1;
            for (int at = comma - 1; at >= 0; at--) {
                Token token = tokens.get(at);
                if (token.depth() < depth || token.punct(";")) {
                    stop = at;
                    break;
                }
                if (token.depth() > depth) {
                    continue;
                }
                if (known[at] != 0) {
                    result = known[at] == 1;
                    stop = at;
                    break;
                }
                if (token.keyword("ON")) {
                    // A comma after a join condition continues the FROM list; after GRANT/INDEX ON it lists objects.
                    if (joinCondition(tokens, at)) {
                        continue;
                    }
                    result = true;
                    stop = at;
                    break;
                }
                if (token.keyword(owners) || token.keyword(EXPRESSION_LIST_OWNERS)
                        || token.keyword(OBJECT_LIST_OWNERS)) {
                    result = token.keyword(owners);
                    stop = at;
                    break;
                }
            }
            byte answer = (byte) (result ? 1 : 2);
            for (int at = comma; at > stop; at--) {
                if (tokens.get(at).depth() == depth && tokens.get(at).punct(",")) {
                    known[at] = answer;
                }
            }
            return result;
        }
    }

    /**
     * {@code UPDATE alias … FROM sys.objects alias}, {@code DELETE alias FROM …},
     * {@code MERGE cte …}, {@code UPDATE (SELECT … FROM catalog) …}, and every table of a
     * MySQL multi-table {@code UPDATE} write the table they resolve to. Aliases and CTE names
     * resolve within their own statement only.
     */
    private static void rejectCatalogWritesThroughAliases(List<Token> tokens, Namespace allowed, String sql) {
        DatabaseDialect dialect = allowed.dialect();
        if (dialect == DatabaseDialect.ORACLE) {
            return;
        }
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        int start = 0;
        for (int index = 0; index <= tokens.size(); index++) {
            if (index == tokens.size() || tokens.get(index).punct(";")) {
                List<Token> statement = tokens.subList(start, index);
                ListOwners lists = new ListOwners(statement, OBJECT_LIST_OWNERS);
                for (int target : writeTargets(statement, mode)) {
                    rejectCatalogReference(statement, target, dialect, mode, sql, 0, lists);
                }
                start = index + 1;
            }
        }
    }

    /** Start indexes of the table references an UPDATE, DELETE, or MERGE writes. */
    private static List<Integer> writeTargets(List<Token> tokens, SqlLexer.Mode mode) {
        List<Integer> targets = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!(token.keyword("UPDATE") || token.keyword("DELETE") || token.keyword("MERGE"))) {
                continue;
            }
            Token previous = SqlTokenizer.previous(tokens, index);
            if (previous != null && (previous.punct(",") || previous.keyword(NON_STATEMENT_WRITE_PREDECESSORS))) {
                continue;
            }
            int at = skipTargetModifiers(tokens, index + 1);
            if (at >= tokens.size() || tokens.get(at).keyword("SET") || !startsReference(tokens, at, mode)) {
                continue;
            }
            targets.add(at);
            if (!token.keyword("UPDATE")) {
                continue;
            }
            int depth = token.depth();
            for (int item = at + 1; item < tokens.size(); item++) {
                Token candidate = tokens.get(item);
                if (candidate.depth() < depth || (candidate.depth() == depth && candidate.keyword("SET"))) {
                    break;
                }
                if (candidate.depth() == depth && (candidate.punct(",") || candidate.keyword("JOIN"))) {
                    int next = skipTargetModifiers(tokens, item + 1);
                    if (next < tokens.size() && startsReference(tokens, next, mode)) {
                        targets.add(next);
                    }
                }
            }
        }
        return targets;
    }

    private static int skipTargetModifiers(List<Token> tokens, int index) {
        int at = index;
        while (at < tokens.size()) {
            Token token = tokens.get(at);
            if (token.keyword("TOP") && at + 1 < tokens.size() && tokens.get(at + 1).punct("(")) {
                at = matchingClose(tokens, at + 1) + 1;
            } else if (token.keyword("TOP") && at + 1 < tokens.size()
                    && tokens.get(at + 1).type() == SqlTokenizer.Type.NUMBER) {
                at += 2;
            } else if (token.keyword(OBJECT_MODIFIERS) || token.keyword("FROM") || token.keyword("INTO")) {
                at++;
            } else {
                return at;
            }
        }
        return at;
    }

    private static boolean startsReference(List<Token> tokens, int index, SqlLexer.Mode mode) {
        return tokens.get(index).punct("(") || chainAt(tokens, index, mode) != null;
    }

    /** Rejects when the reference at {@code index} is, or resolves through aliases and CTEs to, a system catalog. */
    private static void rejectCatalogReference(List<Token> tokens, int index, DatabaseDialect dialect,
                                               SqlLexer.Mode mode, String sql, int hops, ListOwners lists) {
        if (tokens.get(index).punct("(")) {
            rejectCatalogInside(tokens, index, matchingClose(tokens, index), dialect, mode, sql);
            return;
        }
        Chain chain = chainAt(tokens, index, mode);
        if (chain == null) {
            return;
        }
        if (chain.parts().size() >= 2) {
            if (isSystemCatalog(chain.parts().get(0), dialect)) {
                throw catalogWrite(chain.parts().get(0), sql);
            }
            return;
        }
        if (hops > 8) {
            return;
        }
        Token name = chain.parts().get(0);
        for (int at = 0; at < tokens.size(); at++) {
            if (at == index) {
                continue;
            }
            Token candidate = tokens.get(at);
            if (candidate.keyword("AS") && at > 0 && at + 1 < tokens.size() && tokens.get(at + 1).punct("(")
                    && sameAlias(tokens.get(at - 1), name, dialect) && cteName(tokens, at - 1)) {
                int open = at + 1;
                rejectCatalogInside(tokens, open, matchingClose(tokens, open), dialect, mode, sql);
                continue;
            }
            if (!candidate.name() || !sameAlias(candidate, name, dialect)) {
                continue;
            }
            int reference = aliasedReference(tokens, at, mode, lists);
            if (reference >= 0 && reference != index) {
                rejectCatalogReference(tokens, reference, dialect, mode, sql, hops + 1, lists);
            }
        }
    }

    /** {@code WITH name AS (} or {@code , name AS (} at the CTE list's depth. */
    private static boolean cteName(List<Token> tokens, int nameIndex) {
        Token previous = SqlTokenizer.previous(tokens, nameIndex);
        return previous != null && (previous.keyword("WITH") || previous.keyword("RECURSIVE") || previous.punct(","));
    }

    /** Start index of the table reference that the alias token at {@code aliasIndex} names, or -1. */
    private static int aliasedReference(List<Token> tokens, int aliasIndex, SqlLexer.Mode mode, ListOwners lists) {
        if (tokens.get(aliasIndex).keyword(NOT_ALIASES)) {
            return -1;
        }
        int end = aliasIndex - 1;
        if (end >= 0 && tokens.get(end).keyword("AS")) {
            end--;
        }
        if (end < 0) {
            return -1;
        }
        int start;
        if (tokens.get(end).punct(")")) {
            start = matchingOpen(tokens, end);
        } else {
            start = end;
            while (start >= 2 && tokens.get(start - 1).punct(".") && tokens.get(start - 2).name()) {
                start -= 2;
            }
            if (!tokens.get(start).name()) {
                return -1;
            }
        }
        if (start < 1) {
            return -1;
        }
        Token owner = tokens.get(start - 1);
        boolean referencePosition = owner.keyword("FROM") || owner.keyword("JOIN") || owner.keyword("USING")
                || owner.keyword("UPDATE") || owner.keyword("INTO") || owner.keyword("MERGE") || owner.keyword("APPLY")
                || owner.keyword(OBJECT_MODIFIERS) || (owner.punct(",") && lists.owns(start - 1));
        return referencePosition && startsReference(tokens, start, mode) ? start : -1;
    }

    private static void rejectCatalogInside(List<Token> tokens, int open, int close, DatabaseDialect dialect,
                                            SqlLexer.Mode mode, String sql) {
        for (Chain chain : chains(tokens.subList(open, close + 1), mode)) {
            if (isSystemCatalog(chain.parts().get(0), dialect)) {
                throw catalogWrite(chain.parts().get(0), sql);
            }
        }
    }

    /**
     * Alias comparison over-resolves where the engine may: PostgreSQL and Oracle fold
     * unquoted aliases, while MySQL/MariaDB and SQL Server alias case sensitivity depends on
     * server settings or collation, so those compare case-insensitively (resolving more
     * aliases only rejects more catalog writes).
     */
    private static boolean sameAlias(Token candidate, Token name, DatabaseDialect dialect) {
        if (!candidate.name() || !name.name()) {
            return false;
        }
        return switch (dialect) {
            case POSTGRESQL, ORACLE -> canonical(candidate.value(), candidate.quoted(), dialect)
                    .equals(canonical(name.value(), name.quoted(), dialect));
            case MYSQL, MARIADB, SQLSERVER -> candidate.value().equalsIgnoreCase(name.value());
        };
    }

    private static void rejectUnsafeGrants(String scannable, Namespace allowed, String configuredNamespace,
                                           String sql) {
        Matcher star = GRANT_ON_DB_STAR.matcher(scannable);
        while (star.find()) {
            if (!grantKeywordInCurrentStatement(scannable, star.start())) {
                continue;
            }
            if (star.group(1) != null) {
                throw new IllegalArgumentException(
                        "schema change SQL must not GRANT on *.* (cross-database privileges): "
                                + summarize(sql));
            }
            String db = optionalCapture(star, 2, 3, 4, 5);
            boolean quoted = star.group(5) == null;
            if (db != null && allowed.dialect().isMySqlFamily()) {
                if (MYSQL_GRANT_WILDCARD.matcher(db).find()) {
                    throw new IllegalArgumentException("schema change SQL GRANT database name '" + db
                            + "' contains an unescaped MySQL wildcard (_ or %): " + summarize(sql));
                }
                db = db.replace("\\_", "_").replace("\\%", "%");
            }
            if (db == null || !allowed.matches(db, quoted)) {
                throw new IllegalArgumentException(
                        "schema change SQL GRANT targets database '" + (db == null ? "?" : db)
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
        Matcher database = GRANT_ON_DATABASE.matcher(scannable);
        while (database.find()) {
            if (grantKeywordInCurrentStatement(scannable, database.start())) {
                throw new IllegalArgumentException(
                        "schema change SQL must not GRANT database-level privileges "
                                + "(exceeds the configured namespace binding): " + summarize(sql));
            }
        }
    }

    /** True when {@code GRANT} appears in the same semicolon-delimited statement as {@code offset}. */
    private static boolean grantKeywordInCurrentStatement(String scannable, int offset) {
        int statementStart = scannable.lastIndexOf(';', Math.max(0, offset - 1)) + 1;
        return GRANT_KEYWORD.matcher(scannable.substring(statementStart, offset)).find();
    }

    private static void rejectForeignSchema(String text, Pattern pattern, Namespace allowed,
                                            String configuredNamespace, String sql, String verb) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String schema = firstNonNull(matcher, 1, 2, 3, 4);
            if (!allowed.matches(schema, matcher.group(4) == null)) {
                throw new IllegalArgumentException(
                        "schema change SQL " + verb + " '" + schema
                                + "' but synchronizer is configured for '" + configuredNamespace
                                + "': " + summarize(sql));
            }
        }
    }

    private static String optionalCapture(Matcher matcher, int... groups) {
        for (int group : groups) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonNull(Matcher matcher, int... groups) {
        String value = optionalCapture(matcher, groups);
        if (value == null) {
            throw new IllegalStateException("qualified-name match missing schema capture");
        }
        return value;
    }

    /**
     * PostgreSQL catalogs follow identifier folding (a quoted {@code "PG_CATALOG"} is another
     * schema). MySQL/MariaDB always compare {@code information_schema} case-insensitively,
     * and SQL Server's default collations are case-insensitive, so those compare ignoring case.
     */
    private static boolean isSystemCatalog(Token schema, DatabaseDialect dialect) {
        String lower = schema.value().toLowerCase(Locale.ROOT);
        return switch (dialect) {
            case POSTGRESQL -> {
                String folded = canonical(schema.value(), schema.quoted(), dialect);
                yield "pg_catalog".equals(folded) || "information_schema".equals(folded);
            }
            case SQLSERVER -> "sys".equals(lower) || "information_schema".equals(lower);
            case MYSQL, MARIADB -> "information_schema".equals(lower);
            case ORACLE -> false;
        };
    }

    private static IllegalArgumentException catalogWrite(Token schema, String sql) {
        return new IllegalArgumentException("schema change SQL must not write to or define objects in "
                + "system catalog '" + schema.value() + "': " + summarize(sql));
    }

    private static String summarize(String sql) {
        return SqlLexer.summarize(sql);
    }
}
