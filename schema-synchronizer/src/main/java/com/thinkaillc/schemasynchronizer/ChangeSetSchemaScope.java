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
 * Rejects change-set SQL that targets a schema/catalog other than the configured
 * namespace. Declarative {@code createSql} already enforces this; change sets did not.
 *
 * <p>Any {@code schema.object} reference (bare or quoted/bracket/backtick) outside
 * string literals must use the configured namespace or a system catalog. Prefer
 * unqualified column names in {@code SET} clauses ({@code SET note = …} rather than
 * {@code SET items.note = …}) so table-qualified columns are not mistaken for
 * cross-schema targets. A three-part name is a cross-database reference where an object
 * belongs ({@code FROM a.b.c}, {@code UPDATE a.b.c}, {@code a.b.c(…)}); in an expression it
 * is {@code schema.table.column} and only its schema is checked.
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
            "(?is)(?:\\bSET\\s+(?:LOCAL\\s+|SESSION\\s+)?search_path\\b"
                    + "|\\bset_config\"?\\s*\\("
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
        if (SESSION_NAMESPACE.matcher(scannable).find() || sessionNamespaceMutation(tokens, mode)) {
            throw new IllegalArgumentException(
                    "schema change SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        boolean routine = SqlTokenizer.routine(tokens, mode) != null;
        int commentColumn = checkCommentOnColumn(tokens, mode, allowed, configuredNamespace, sql);
        scan(scannable, tokens, allowed, configuredNamespace, sql, "", routine, commentColumn);
        if (routine) {
            for (SqlTokenizer.Body body : SqlTokenizer.postgresBodies(tokens, mode, sql)) {
                String bodyText = bodyScannable(body.text(), mode);
                scan(bodyText, body.tokens(), allowed, configuredNamespace, sql, " inside routine body", true, -1);
                if (SESSION_NAMESPACE.matcher(bodyText).find() || sessionNamespaceMutation(body.tokens(), mode)) {
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
            Token schema = chain.parts().getFirst();
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
                mode)) {
            throw new IllegalArgumentException(
                    "schema verification SQL must not alter session namespace (search_path / CURRENT_SCHEMA / USE): "
                            + summarize(sql));
        }
        requireNoDatabaseLink(scannable, dialect, sql);
    }

    /**
     * {@code set_config(…)} called by a quoted or escaped name, {@code SET "search_path"}, and
     * PostgreSQL {@code SET SCHEMA '…'}. Configuration parameter names are case-insensitive.
     */
    private static boolean sessionNamespaceMutation(List<Token> tokens, SqlLexer.Mode mode) {
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
                return true;
            }
            if (mode == SqlLexer.Mode.POSTGRES && target.keyword("SCHEMA") && at + 1 < tokens.size()
                    && tokens.get(at + 1).type() == SqlTokenizer.Type.STRING) {
                return true;
            }
        }
        return false;
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
                             String sql, String where, boolean routine, int skipChainAt) {
        DatabaseDialect dialect = allowed.dialect();
        // Oracle PL/SQL uses `..` as its range operator (FOR i IN lo..hi LOOP).
        if (dialect != DatabaseDialect.ORACLE && DOUBLE_DOT.matcher(text).find()) {
            throw new IllegalArgumentException("schema change SQL must not use database..object references"
                    + where + ": " + summarize(sql));
        }
        requireNoDatabaseLink(text, dialect, sql);
        rejectUnsafeGrants(text, allowed, configuredNamespace, sql);
        rejectForeignChains(tokens, allowed, configuredNamespace, sql, where, routine, skipChainAt);
        rejectForeignSchema(text, IN_SCHEMA, allowed, configuredNamespace, sql, "uses IN SCHEMA" + where);
        rejectForeignSchema(text, EXTENSION_OR_COMMENT_SCHEMA, allowed, configuredNamespace, sql,
                "references schema" + where);
        rejectCatalogWritesThroughAliases(tokens, allowed, sql);
    }

    private static void rejectForeignChains(List<Token> tokens, Namespace allowed, String configuredNamespace,
                                            String sql, String where, boolean routine, int skipChainAt) {
        DatabaseDialect dialect = allowed.dialect();
        SqlLexer.Mode mode = SqlLexer.mode(dialect);
        ListOwners objectLists = new ListOwners(tokens, OBJECT_LIST_OWNERS);
        ListOwners writeLists = new ListOwners(tokens, WRITE_LIST_OWNERS);
        for (Chain chain : chains(tokens, mode)) {
            if (chain.first() == skipChainAt) {
                continue;
            }
            int parts = chain.parts().size();
            if ((dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.SQLSERVER)
                    && (parts >= 4 || (parts == 3 && objectPosition(tokens, chain, objectLists)))) {
                throw new IllegalArgumentException("schema change SQL must not use database.schema.object references"
                        + where + ": " + summarize(sql));
            }
            Token schema = chain.parts().getFirst();
            if (isSystemCatalog(schema, dialect)) {
                if (writeTarget(tokens, chain.first(), writeLists)) {
                    throw catalogWrite(schema, sql);
                }
                continue;
            }
            if (routine && !schema.quoted()
                    && ("new".equalsIgnoreCase(schema.value()) || "old".equalsIgnoreCase(schema.value()))) {
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
            return true;
        }
        return previous.punct(",") && lists.owns(at);
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
        int depth = tokens.get(open).depth();
        for (int at = open + 1; at < tokens.size(); at++) {
            if (tokens.get(at).punct(")") && tokens.get(at).depth() == depth) {
                return at;
            }
        }
        return tokens.size() - 1;
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
            if (isSystemCatalog(chain.parts().getFirst(), dialect)) {
                throw catalogWrite(chain.parts().getFirst(), sql);
            }
            return;
        }
        if (hops > 8) {
            return;
        }
        Token name = chain.parts().getFirst();
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
            if (isSystemCatalog(chain.parts().getFirst(), dialect)) {
                throw catalogWrite(chain.parts().getFirst(), sql);
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
