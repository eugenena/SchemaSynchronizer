// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-dialect tokenizer and statement splitter for the change-set guardrails, built on
 * {@link SqlLexer}. Comments are dropped; string literals, quoted identifiers, and
 * PostgreSQL dollar bodies become single tokens carrying their unescaped value, including
 * PostgreSQL {@code E'…'}, {@code U&'…'}, and {@code U&"…"} escapes. Routine bodies are
 * one statement: PostgreSQL {@code BEGIN ATOMIC}, MySQL/MariaDB compound statements, and
 * Oracle PL/SQL blocks are balanced, and a SQL Server routine extends to the end of the
 * text as it does to the end of a batch.
 *
 * <p>Input the tokenizer cannot split unambiguously is rejected. Like the lexer, this is a
 * guardrail against accidental unsafe change sets, not a SQL parser and not a security
 * boundary against hostile SQL authors.
 */
final class SqlTokenizer {

    enum Type { WORD, QUOTED, STRING, NUMBER, BODY, VARIABLE, PUNCT }

    /**
     * {@code value} is the unescaped content of strings, quoted identifiers, and bodies and
     * the text itself otherwise; {@code depth} is the parenthesis depth.
     */
    record Token(Type type, String text, String value, int start, int end, int depth) {

        /** Keywords compare case-insensitively on every supported engine; identifiers never use this. */
        boolean keyword(String word) {
            return type == Type.WORD && text.equalsIgnoreCase(word);
        }

        boolean keyword(Set<String> words) {
            return type == Type.WORD && words.contains(text.toUpperCase(Locale.ROOT));
        }

        boolean punct(String symbol) {
            return type == Type.PUNCT && text.equals(symbol);
        }

        boolean name() {
            return type == Type.WORD || type == Type.QUOTED;
        }

        boolean quoted() {
            return type == Type.QUOTED;
        }

        Token atDepth(int newDepth) {
            return new Token(type, text, value, start, end, newDepth);
        }
    }

    /** A routine or PL/SQL block statement; {@code kindIndex} is the index of FUNCTION/TRIGGER/… or BEGIN. */
    record Routine(int kindIndex, String kind) {}

    /** A routine body scanned as code, with its text for the lexical checks that still run on masked text. */
    record Body(String text, List<Token> tokens) {}

    private static final Set<String> ROUTINE_KINDS =
            Set.of("FUNCTION", "PROCEDURE", "PROC", "TRIGGER", "PACKAGE", "TYPE", "EVENT");

    private static final Set<String> ROUTINE_HEAD_MODIFIERS = Set.of("OR", "REPLACE", "ALTER", "EDITIONABLE",
            "NONEDITIONABLE", "EDITIONING", "AGGREGATE", "CONSTRAINT", "COMPOUND");

    /** Keywords after which a new statement starts inside a routine body. */
    static final Set<String> STATEMENT_BOUNDARY_WORDS =
            Set.of("BEGIN", "THEN", "ELSE", "LOOP", "DO", "REPEAT", "ATOMIC", "TRY", "CATCH");

    private static final Set<String> BLOCK_SUFFIXES = Set.of("IF", "LOOP", "WHILE", "REPEAT", "CASE");

    private static final Set<String> MYSQL_ROUTINE_CHARACTERISTICS =
            Set.of("DETERMINISTIC", "DATA", "SQL", "INVOKER", "DEFINER", "CONTAINS");

    /** Reserved T-SQL words that can start a statement; reserved, so never an unquoted identifier. */
    private static final Set<String> TSQL_STATEMENT_WORDS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE",
            "MERGE", "ALTER", "CREATE", "DROP", "GRANT", "REVOKE", "DENY", "EXEC", "EXECUTE", "DECLARE", "SET",
            "TRUNCATE", "BACKUP", "RESTORE", "DBCC", "KILL", "SHUTDOWN", "USE", "WAITFOR", "PRINT", "RAISERROR",
            "IF", "WHILE", "BEGIN", "END", "RETURN", "BULK", "OPEN", "CLOSE", "FETCH", "DEALLOCATE", "CHECKPOINT",
            "RECONFIGURE", "WITH", "SETUSER", "REVERT", "GOTO", "BREAK", "CONTINUE", "COMMIT", "ROLLBACK", "SAVE",
            "READTEXT", "WRITETEXT", "UPDATETEXT", "ELSE", "LINENO");

    private static final Set<String> TSQL_CTE_MAIN = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");

    private static final Set<String> TSQL_PRIVILEGE_LEADS = Set.of("GRANT", "REVOKE", "DENY");

    private static final Set<String> TSQL_SET_OPERATORS = Set.of("UNION", "ALL", "EXCEPT", "INTERSECT");

    /** Objects of {@code DROP|CREATE … IF [NOT] EXISTS}. */
    private static final Set<String> TSQL_IF_OBJECTS = Set.of("TABLE", "INDEX", "VIEW", "COLUMN", "CONSTRAINT",
            "SCHEMA", "SEQUENCE", "TRIGGER", "PROCEDURE", "FUNCTION");

    /** Words after {@code WITH} that make it an option or hint rather than a common table expression. */
    private static final Set<String> TSQL_WITH_OPTIONS = Set.of("TIES", "CHECK", "NOCHECK", "GRANT", "VALUES",
            "ROLLUP", "CUBE", "SCHEMABINDING", "ENCRYPTION", "VIEW_METADATA");

    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");

    private SqlTokenizer() {
    }

    static List<Token> tokenize(String sql, SqlLexer.Mode mode) {
        List<Token> raw = new ArrayList<>();
        for (SqlLexer.Span span : SqlLexer.spans(sql, mode)) {
            switch (span.kind()) {
                case COMMENT -> { }
                case CODE -> splitCode(sql, span.start(), span.end(), raw, mode);
                case STRING -> raw.add(stringToken(sql, span, mode));
                case QUOTED_IDENT -> raw.add(new Token(Type.QUOTED, sql.substring(span.start(), span.end()),
                        span.content(), span.start(), span.end(), 0));
                case DOLLAR_BODY -> raw.add(new Token(Type.BODY, sql.substring(span.start(), span.end()),
                        span.content(), span.start(), span.end(), 0));
            }
        }
        List<Token> tokens = mode == SqlLexer.Mode.POSTGRES ? postgresUnicodeEscapes(raw, sql) : raw;
        return withDepths(tokens);
    }

    /** Tokenizes routine body text; a body that does not tokenize is rejected rather than skipped. */
    static List<Token> tokenizeBody(String body, SqlLexer.Mode mode, String sql) {
        try {
            return tokenize(body, mode);
        } catch (IllegalArgumentException unlexable) {
            throw new IllegalArgumentException("routine body could not be tokenized (" + unlexable.getMessage()
                    + "): " + SqlLexer.summarize(sql), unlexable);
        }
    }

    private static List<Token> withDepths(List<Token> tokens) {
        List<Token> result = new ArrayList<>(tokens.size());
        int depth = 0;
        for (Token token : tokens) {
            if (token.punct("(")) {
                result.add(token.atDepth(depth));
                depth++;
            } else if (token.punct(")")) {
                depth = Math.max(0, depth - 1);
                result.add(token.atDepth(depth));
            } else {
                result.add(token.atDepth(depth));
            }
        }
        return result;
    }

    private static void splitCode(String sql, int from, int to, List<Token> tokens, SqlLexer.Mode mode) {
        int index = from;
        while (index < to) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            int start = index;
            int sqlServerNumber = mode == SqlLexer.Mode.SQLSERVER ? sqlServerNumberEnd(sql, index, to) : -1;
            if (sqlServerNumber > index) {
                index = sqlServerNumber;
                String number = sql.substring(start, index);
                tokens.add(new Token(Type.NUMBER, number, number, start, index, 0));
                continue;
            }
            if (mode != SqlLexer.Mode.MYSQL && mode != SqlLexer.Mode.SQLSERVER && isAsciiDigit(current)) {
                index = numberEnd(sql, index, to, mode);
                String number = sql.substring(start, index);
                tokens.add(new Token(Type.NUMBER, number, number, start, index, 0));
                continue;
            }
            if (isWordChar(current)) {
                while (index < to && isWordChar(sql.charAt(index))) {
                    index++;
                }
                String text = sql.substring(start, index);
                if (text.chars().allMatch(Character::isDigit)) {
                    if (index + 1 < to && sql.charAt(index) == '.' && Character.isDigit(sql.charAt(index + 1))) {
                        index++;
                        while (index < to && Character.isDigit(sql.charAt(index))) {
                            index++;
                        }
                    }
                    tokens.add(new Token(Type.NUMBER, sql.substring(start, index), sql.substring(start, index),
                            start, index, 0));
                } else {
                    tokens.add(new Token(Type.WORD, text, text, start, index, 0));
                }
                continue;
            }
            if (current == '@' && index + 1 < to && (isWordChar(sql.charAt(index + 1)) || sql.charAt(index + 1) == '@')) {
                index++;
                while (index < to && (isWordChar(sql.charAt(index)) || sql.charAt(index) == '@')) {
                    index++;
                }
                String text = sql.substring(start, index);
                tokens.add(new Token(Type.VARIABLE, text, text, start, index, 0));
                continue;
            }
            String symbol = twoCharSymbol(sql, index, to);
            index += symbol.length();
            tokens.add(new Token(Type.PUNCT, symbol, symbol, start, index, 0));
        }
    }

    /**
     * End of a T-SQL numeric literal at {@code index}, or -1. Letters after the literal start a
     * new token, and SQL Server runs {@code SELECT 1EPRINT 'x'} as two statements:
     * <ul>
     *   <li>binary: {@code 0x} and zero or more hex digits;</li>
     *   <li>integer, decimal, float: {@code 1}, {@code 1.}, {@code .5}, {@code 1.5}, then an
     *       optional {@code E}/{@code e} with an optional sign and zero or more digits
     *       ({@code 1E}, {@code 1.E}, {@code 1e-} are complete floats);</li>
     *   <li>money: a currency symbol, optional spaces, an optional sign, then digits with an
     *       optional decimal part and no exponent ({@code $1}, {@code £-1}, {@code $.5}).
     *       {@code $} followed by a letter is a pseudocolumn name, not money.</li>
     * </ul>
     */
    static int sqlServerNumberEnd(String sql, int index, int to) {
        char current = sql.charAt(index);
        if (current == '0' && index + 1 < to && (sql.charAt(index + 1) == 'x' || sql.charAt(index + 1) == 'X')) {
            int end = index + 2;
            while (end < to && Character.digit(sql.charAt(end), 16) >= 0 && sql.charAt(end) < 128) {
                end++;
            }
            return end;
        }
        if (Character.getType(current) == Character.CURRENCY_SYMBOL) {
            int at = index + 1;
            while (at < to && Character.isWhitespace(sql.charAt(at))) {
                at++;
            }
            if (at < to && (sql.charAt(at) == '+' || sql.charAt(at) == '-')) {
                at++;
            }
            int end = decimalEnd(sql, at, to);
            return end > at ? end : -1;
        }
        int end = decimalEnd(sql, index, to);
        if (end <= index) {
            return -1;
        }
        if (end < to && (sql.charAt(end) == 'e' || sql.charAt(end) == 'E')) {
            end++;
            if (end < to && (sql.charAt(end) == '+' || sql.charAt(end) == '-')) {
                end++;
            }
            end = digitsEnd(sql, end, to);
        }
        return end;
    }

    /** {@code digits [. digits]} or {@code . digits}, at least one digit; returns {@code index} when none. */
    private static int decimalEnd(String sql, int index, int to) {
        int end = digitsEnd(sql, index, to);
        boolean integer = end > index;
        if (end < to && sql.charAt(end) == '.' && (integer || (end + 1 < to && isAsciiDigit(sql.charAt(end + 1))))) {
            end = digitsEnd(sql, end + 1, to);
        }
        return integer || end > index + 1 ? end : index;
    }

    /**
     * End of the numeric literal at {@code index} on PostgreSQL and Oracle, whose identifiers
     * cannot start with a digit. MySQL/MariaDB are excluded because theirs may.
     */
    private static int numberEnd(String sql, int index, int to, SqlLexer.Mode mode) {
        int end = index;
        if (mode == SqlLexer.Mode.POSTGRES && sql.charAt(end) == '0' && end + 1 < to
                && (sql.charAt(end + 1) == 'x' || sql.charAt(end + 1) == 'X')) {
            end += 2;
            while (end < to && Character.digit(sql.charAt(end), 16) >= 0) {
                end++;
            }
            return end;
        }
        end = digitsEnd(sql, end, to);
        // Oracle's 1..3 range: a dot followed by another dot is not a decimal point.
        if (end + 1 < to && sql.charAt(end) == '.' && isAsciiDigit(sql.charAt(end + 1))) {
            end = digitsEnd(sql, end + 1, to);
        }
        if (end < to && (sql.charAt(end) == 'e' || sql.charAt(end) == 'E')) {
            int exponent = end + 1;
            if (exponent < to && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < to && isAsciiDigit(sql.charAt(exponent))) {
                end = digitsEnd(sql, exponent, to);
            }
        }
        return end;
    }

    private static int digitsEnd(String sql, int index, int to) {
        int end = index;
        while (end < to && isAsciiDigit(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }

    private static String twoCharSymbol(String sql, int index, int to) {
        if (index + 1 < to) {
            String pair = sql.substring(index, index + 2);
            if (Set.of(":=", "::", "=>", "<<", ">>", "<>", "!=", "<=", ">=", "||").contains(pair)) {
                return pair;
            }
        }
        return String.valueOf(sql.charAt(index));
    }

    private static Token stringToken(String sql, SqlLexer.Span span, SqlLexer.Mode mode) {
        String text = sql.substring(span.start(), span.end());
        String value = span.content();
        if (mode == SqlLexer.Mode.POSTGRES) {
            int quote = text.indexOf('\'');
            String prefix = text.substring(0, quote).toUpperCase(Locale.ROOT);
            String inner = text.substring(quote + 1, text.length() - 1);
            if (prefix.equals("E")) {
                value = decodeEscapeString(inner, sql);
            } else if (!prefix.equals("B") && !prefix.equals("X") && oddBackslashesBeforeQuote(inner + "'")) {
                // With standard_conforming_strings off, \' is an escaped quote and the literal ends elsewhere.
                throw new IllegalArgumentException("backslash before a quote in a PostgreSQL string literal is "
                        + "ambiguous across standard_conforming_strings settings; use E'…' or double the quote: "
                        + SqlLexer.summarize(sql));
            }
        }
        return new Token(Type.STRING, text, value, span.start(), span.end(), 0);
    }

    private static boolean oddBackslashesBeforeQuote(String text) {
        int backslashes = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && backslashes % 2 == 1) {
                return true;
            }
            backslashes = current == '\\' ? backslashes + 1 : 0;
        }
        return false;
    }

    /** PostgreSQL {@code E'…'} escapes: backslash b, f, n, r, t, octal, x (hex), u and U (Unicode). */
    static String decodeEscapeString(String inner, String sql) {
        StringBuilder result = new StringBuilder(inner.length());
        int index = 0;
        while (index < inner.length()) {
            char current = inner.charAt(index);
            if (current == '\'' && index + 1 < inner.length() && inner.charAt(index + 1) == '\'') {
                result.append('\'');
                index += 2;
                continue;
            }
            if (current != '\\' || index + 1 >= inner.length()) {
                result.append(current);
                index++;
                continue;
            }
            char next = inner.charAt(index + 1);
            index += 2;
            switch (next) {
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'x' -> {
                    int end = hexRun(inner, index, 2);
                    if (end == index) {
                        result.append('x');
                    } else {
                        result.append((char) Integer.parseInt(inner.substring(index, end), 16));
                        index = end;
                    }
                }
                case 'u', 'U' -> {
                    int digits = next == 'u' ? 4 : 8;
                    int end = hexRun(inner, index, digits);
                    if (end - index != digits) {
                        throw new IllegalArgumentException("invalid Unicode escape in PostgreSQL E'…' literal: "
                                + SqlLexer.summarize(sql));
                    }
                    appendCodePoint(result, Long.parseLong(inner.substring(index, end), 16), sql);
                    index = end;
                }
                default -> {
                    if (next >= '0' && next <= '7') {
                        int end = index;
                        while (end < inner.length() && end < index + 2 && inner.charAt(end) >= '0'
                                && inner.charAt(end) <= '7') {
                            end++;
                        }
                        result.append((char) (Integer.parseInt(inner.substring(index - 1, end), 8) & 0xFF));
                        index = end;
                    } else {
                        result.append(next);
                    }
                }
            }
        }
        return result.toString();
    }

    private static int hexRun(String text, int from, int max) {
        int end = from;
        while (end < text.length() && end < from + max && HEX.matcher(String.valueOf(text.charAt(end))).matches()) {
            end++;
        }
        return end;
    }

    private static void appendCodePoint(StringBuilder result, long codePoint, String sql) {
        if (codePoint > Character.MAX_CODE_POINT || codePoint == 0) {
            throw new IllegalArgumentException("invalid Unicode escape value in PostgreSQL literal: "
                    + SqlLexer.summarize(sql));
        }
        result.appendCodePoint((int) codePoint);
    }

    /**
     * Folds {@code U&'…'} strings and {@code U&"…"} identifiers (with an optional
     * {@code UESCAPE 'c'}) into single tokens holding the decoded value.
     */
    private static List<Token> postgresUnicodeEscapes(List<Token> tokens, String sql) {
        List<Token> result = new ArrayList<>(tokens.size());
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token unicode = null;
            if (token.type() == Type.STRING && token.text().length() > 2
                    && Character.toUpperCase(token.text().charAt(0)) == 'U' && token.text().charAt(1) == '&') {
                unicode = token;
            } else if (token.keyword("U") && index + 2 < tokens.size() && tokens.get(index + 1).punct("&")
                    && tokens.get(index + 2).quoted() && token.end() == tokens.get(index + 1).start()
                    && tokens.get(index + 1).end() == tokens.get(index + 2).start()) {
                Token quoted = tokens.get(index + 2);
                unicode = new Token(Type.QUOTED, sql.substring(token.start(), quoted.end()), quoted.value(),
                        token.start(), quoted.end(), 0);
                index += 2;
            }
            if (unicode == null) {
                result.add(token);
                continue;
            }
            char escape = '\\';
            if (index + 2 < tokens.size() && tokens.get(index + 1).keyword("UESCAPE")
                    && tokens.get(index + 2).type() == Type.STRING) {
                String escapeValue = tokens.get(index + 2).value();
                if (escapeValue.length() != 1 || "0123456789abcdefABCDEF+'\" \t\r\n".indexOf(escapeValue.charAt(0)) >= 0) {
                    throw new IllegalArgumentException("invalid UESCAPE character in PostgreSQL literal: "
                            + SqlLexer.summarize(sql));
                }
                escape = escapeValue.charAt(0);
                index += 2;
            }
            result.add(new Token(unicode.type(), unicode.text(), decodeUnicodeEscapes(unicode.value(), escape, sql),
                    unicode.start(), unicode.end(), 0));
        }
        return result;
    }

    static String decodeUnicodeEscapes(String value, char escape, String sql) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != escape) {
                result.append(current);
                index++;
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == escape) {
                result.append(escape);
                index += 2;
                continue;
            }
            boolean six = index + 1 < value.length() && value.charAt(index + 1) == '+';
            int from = index + (six ? 2 : 1);
            int digits = six ? 6 : 4;
            int end = hexRun(value, from, digits);
            if (end - from != digits) {
                throw new IllegalArgumentException("invalid Unicode escape in PostgreSQL U& literal: "
                        + SqlLexer.summarize(sql));
            }
            appendCodePoint(result, Long.parseLong(value.substring(from, end), 16), sql);
            index = end;
        }
        return result.toString();
    }

    /**
     * Splits tokens into statements. Each statement keeps its terminating {@code ;}, so a
     * second statement (even a lone {@code ;}) is visible to single-statement checks.
     */
    static List<List<Token>> statements(List<Token> tokens, SqlLexer.Mode mode, String sql) {
        List<List<Token>> result = new ArrayList<>();
        int start = 0;
        while (start < tokens.size()) {
            Routine routine = routine(tokens.subList(start, tokens.size()), mode);
            int end;
            if (routine != null && mode == SqlLexer.Mode.SQLSERVER) {
                end = tokens.size();
            } else if (mode == SqlLexer.Mode.SQLSERVER) {
                end = sqlServerStatementEnd(tokens, start);
            } else if (routine != null && mode == SqlLexer.Mode.ORACLE) {
                requireBalanced(tokens, start, routine, mode, sql);
                end = tokens.size();
            } else {
                end = statementEnd(tokens, start, routine, mode, sql);
            }
            result.add(tokens.subList(start, end));
            start = end;
        }
        return result;
    }

    /**
     * Recognizes {@code CREATE [OR REPLACE | OR ALTER] [DEFINER = …] [EDITIONABLE …]
     * FUNCTION|PROCEDURE|TRIGGER|…} and, on Oracle, anonymous {@code BEGIN}/{@code DECLARE} blocks.
     */
    static Routine routine(List<Token> tokens, SqlLexer.Mode mode) {
        if (tokens.isEmpty()) {
            return null;
        }
        if (mode == SqlLexer.Mode.ORACLE && (tokens.getFirst().keyword("BEGIN") || tokens.getFirst().keyword("DECLARE"))) {
            return new Routine(0, "BLOCK");
        }
        if (!tokens.getFirst().keyword("CREATE")) {
            return null;
        }
        int index = 1;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.keyword(ROUTINE_KINDS)) {
                String kind = token.text().toUpperCase(Locale.ROOT);
                boolean dialectKind = switch (kind) {
                    case "PACKAGE", "TYPE" -> mode == SqlLexer.Mode.ORACLE;
                    case "EVENT" -> mode == SqlLexer.Mode.MYSQL;
                    case "PROC" -> mode == SqlLexer.Mode.SQLSERVER;
                    default -> true;
                };
                return dialectKind ? new Routine(index, kind) : null;
            }
            if (token.keyword("DEFINER")) {
                index = skipDefiner(tokens, index + 1);
                continue;
            }
            if (token.keyword(ROUTINE_HEAD_MODIFIERS)
                    || (token.keyword("SQL") && index + 2 < tokens.size() && tokens.get(index + 1).keyword("SECURITY"))) {
                index += token.keyword("SQL") ? 3 : 1;
                continue;
            }
            return null;
        }
        return null;
    }

    /** Skips {@code = principal}, where the principal is a name, string, or {@code user@host} form. */
    private static int skipDefiner(List<Token> tokens, int index) {
        if (index < tokens.size() && tokens.get(index).punct("=")) {
            index++;
        }
        while (index < tokens.size() && !tokens.get(index).keyword(ROUTINE_KINDS)
                && !tokens.get(index).keyword("SQL") && !tokens.get(index).keyword(ROUTINE_HEAD_MODIFIERS)) {
            Token token = tokens.get(index);
            if (!(token.name() || token.type() == Type.STRING || token.type() == Type.VARIABLE
                    || token.punct("@") || token.punct("(") || token.punct(")"))) {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * SQL Server runs a batch of statements without {@code ;} separators, so a reserved
     * statement word at parenthesis depth 0 that cannot continue the current statement starts
     * the next one. Words inside a CASE expression never split: a batch that would split
     * there does not compile.
     */
    private static int sqlServerStatementEnd(List<Token> tokens, int start) {
        Token lead = tokens.get(start);
        String main = lead.keyword("WITH") ? null : lead.text().toUpperCase(Locale.ROOT);
        boolean rowSource = false;
        // UPDATE STATISTICS has no SET clause, so a SET after it starts a statement.
        boolean updateSet = lead.keyword("UPDATE") && start + 1 < tokens.size()
                && tokens.get(start + 1).keyword("STATISTICS");
        boolean privilegeTarget = false;
        int cases = 0;
        for (int index = start; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.punct(";")) {
                return index + 1;
            }
            if (index == start || token.type() != Type.WORD || token.depth() != 0) {
                continue;
            }
            if (token.keyword("CASE")) {
                cases++;
                continue;
            }
            if (cases > 0) {
                if (token.keyword("END")) {
                    cases--;
                }
                continue;
            }
            Token previous = tokens.get(index - 1);
            Token next = next(tokens, index);
            if (main == null && token.keyword(TSQL_CTE_MAIN)) {
                main = token.text().toUpperCase(Locale.ROOT);
                continue;
            }
            boolean granting = main != null && TSQL_PRIVILEGE_LEADS.contains(main);
            boolean privilegeList = granting && !privilegeTarget
                    && (index - 1 == start || previous.punct(",") || previous.keyword("FOR"));
            if (granting && token.keyword("ON")) {
                privilegeTarget = true;
            }
            if ("INSERT".equals(main) && (token.keyword("VALUES") || token.keyword("DEFAULT"))) {
                rowSource = true;
            }
            if (!startsTsqlStatement(tokens, index)) {
                continue;
            }
            String word = token.text().toUpperCase(Locale.ROOT);
            boolean continues = switch (word) {
                case "SELECT" -> previous.keyword(TSQL_SET_OPERATORS) || privilegeList
                        || ("INSERT".equals(main) && !rowSource);
                case "INSERT", "UPDATE", "DELETE" -> privilegeList
                        || (word.equals("INSERT") && index - 1 == start && lead.keyword("BULK"))
                        || ("MERGE".equals(main) && previous.keyword("THEN"))
                        || (previous.keyword("ON") && (lead.keyword("CREATE") || lead.keyword("ALTER")));
                case "SET" -> ("UPDATE".equals(main) && !updateSet)
                        || ("MERGE".equals(main) && previous.keyword("UPDATE"))
                        || ((previous.keyword("DELETE") || previous.keyword("UPDATE")) && index - 2 > start
                        && tokens.get(index - 2).keyword("ON"));
                case "ALTER" -> privilegeList || (next != null && next.keyword("COLUMN"));
                case "CREATE", "EXEC", "EXECUTE" -> privilegeList;
                case "GRANT" -> (previous.keyword("WITH") && next != null && next.keyword("OPTION"))
                        || (index - 1 == start && lead.keyword("REVOKE"));
                case "WITH" -> !cteShaped(tokens, index);
                case "IF" -> previous.keyword(TSQL_IF_OBJECTS);
                case "FETCH" -> previous.keyword("ROWS") || previous.keyword("ROW");
                default -> false;
            };
            if (!continues) {
                return index;
            }
            rowSource |= word.equals("SELECT") && "INSERT".equals(main);
            updateSet |= word.equals("SET") && "UPDATE".equals(main);
        }
        return tokens.size();
    }

    /**
     * Whether the word at {@code index} can begin a T-SQL statement. Unreserved leads count only in a shape a
     * column name or clause cannot take. {@code RECEIVE}, {@code SEND}, {@code GET CONVERSATION},
     * {@code MOVE CONVERSATION}, {@code THROW} and {@code ENABLE}/{@code DISABLE TRIGGER} start a statement
     * only after a semicolon; the first four are left out because a column and its alias can take their
     * shape ({@code SELECT get conversation}, {@code JOIN u send ON …}).
     */
    private static boolean startsTsqlStatement(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (token.keyword(TSQL_STATEMENT_WORDS)) {
            return true;
        }
        Token next = next(tokens, index);
        if (next == null) {
            return false;
        }
        Token after = next(tokens, index + 1);
        if (token.keyword("ADD")) {
            // ADD SIGNATURE and ADD SENSITIVITY CLASSIFICATION run without a semicolon; a column named
            // signature is followed by its type, never by TO.
            return ((next.keyword("SIGNATURE") || next.keyword("COUNTERSIGNATURE")) && after != null
                    && after.keyword("TO"))
                    || (next.keyword("SENSITIVITY") && after != null && after.keyword("CLASSIFICATION"));
        }
        return ((token.keyword("ENABLE") || token.keyword("DISABLE")) && next.keyword("TRIGGER"))
                || (token.keyword("THROW") && (next.type() == Type.NUMBER || next.type() == Type.VARIABLE));
    }

    /** {@code WITH name AS (…)} or {@code WITH name (cols) AS (…)}, as opposed to hints and options. */
    private static boolean cteShaped(List<Token> tokens, int index) {
        if (index + 2 >= tokens.size()) {
            return false;
        }
        Token name = tokens.get(index + 1);
        Token after = tokens.get(index + 2);
        return name.name() && !name.keyword(TSQL_WITH_OPTIONS) && (after.keyword("AS") || after.punct("("));
    }

    private static int statementEnd(List<Token> tokens, int start, Routine routine, SqlLexer.Mode mode, String sql) {
        boolean blocks = routine != null && (mode == SqlLexer.Mode.MYSQL || mode == SqlLexer.Mode.POSTGRES);
        Deque<Boolean> open = new ArrayDeque<>();
        for (int index = start; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (blocks) {
                index = trackBlock(tokens, index, start, routine, mode, open, sql);
            }
            if (token.punct(";") && open.isEmpty()) {
                return index + 1;
            }
        }
        if (!open.isEmpty()) {
            throw unterminatedBody(sql);
        }
        return tokens.size();
    }

    private static void requireBalanced(List<Token> tokens, int start, Routine routine, SqlLexer.Mode mode,
                                        String sql) {
        Deque<Boolean> open = new ArrayDeque<>();
        for (int index = start; index < tokens.size(); index++) {
            index = trackBlock(tokens, index, start, routine, mode, open, sql);
        }
        if (!open.isEmpty()) {
            throw unterminatedBody(sql);
        }
    }

    /**
     * Opens or closes the block at {@code index}; {@code open} holds one entry per open block,
     * true for a CASE expression. Returns the index of the last token consumed: the END of a
     * statement block also consumes its {@code IF}/{@code LOOP}/{@code CASE}/… suffix, while
     * the END of a CASE expression never does ({@code … ELSE 1 END LOOP} opens a loop body).
     */
    private static int trackBlock(List<Token> tokens, int index, int start, Routine routine, SqlLexer.Mode mode,
                                  Deque<Boolean> open, String sql) {
        Token token = tokens.get(index);
        boolean inExpression = !open.isEmpty() && open.peek();
        if (opensBlock(tokens, index, start, routine, mode, inExpression)) {
            open.push(token.keyword("CASE") && (mode == SqlLexer.Mode.POSTGRES
                    || !statementPosition(tokens, index, start, mode, inExpression)));
            return index;
        }
        if (!token.keyword("END")) {
            return index;
        }
        if (open.isEmpty()) {
            throw unbalanced(sql);
        }
        boolean expression = open.pop();
        if (!expression && index + 1 < tokens.size() && tokens.get(index + 1).keyword(BLOCK_SUFFIXES)) {
            return index + 1;
        }
        return index;
    }

    private static boolean opensBlock(List<Token> tokens, int index, int start, Routine routine, SqlLexer.Mode mode,
                                      boolean inExpression) {
        Token token = tokens.get(index);
        if (token.keyword("CASE")) {
            return true;
        }
        if (inExpression) {
            return false;
        }
        if (token.keyword("BEGIN")) {
            return mode != SqlLexer.Mode.POSTGRES
                    || (index + 1 < tokens.size() && tokens.get(index + 1).keyword("ATOMIC"));
        }
        if (mode == SqlLexer.Mode.ORACLE) {
            if (token.keyword("LOOP")) {
                return true;
            }
            if (token.keyword("TRIGGER") && index > start && tokens.get(index - 1).keyword("COMPOUND")) {
                return true;
            }
            if ((token.keyword("IS") || token.keyword("AS")) && token.depth() == 0 && oraclePackageHeader(tokens,
                    index, start, routine)) {
                return true;
            }
            return token.keyword("IF") && statementPosition(tokens, index, start, mode, false);
        }
        if (mode == SqlLexer.Mode.MYSQL) {
            if (token.keyword("LOOP")) {
                return true;
            }
            return (token.keyword("IF") || token.keyword("WHILE") || token.keyword("REPEAT"))
                    && statementPosition(tokens, index, start, mode, false);
        }
        return false;
    }

    /** The header {@code IS}/{@code AS} of an Oracle package, package body, or type body opens its block. */
    private static boolean oraclePackageHeader(List<Token> tokens, int index, int start, Routine routine) {
        int kindIndex = start + routine.kindIndex();
        boolean container = routine.kind().equals("PACKAGE")
                || (routine.kind().equals("TYPE") && kindIndex + 1 < tokens.size()
                && tokens.get(kindIndex + 1).keyword("BODY"));
        if (!container || index <= kindIndex) {
            return false;
        }
        for (int earlier = kindIndex + 1; earlier < index; earlier++) {
            if ((tokens.get(earlier).keyword("IS") || tokens.get(earlier).keyword("AS"))
                    && tokens.get(earlier).depth() == 0) {
                return false;
            }
        }
        return true;
    }

    /** Whether the token at {@code index} starts a statement inside a MySQL or PL/SQL routine; never inside a CASE expression. */
    private static boolean statementPosition(List<Token> tokens, int index, int start, SqlLexer.Mode mode,
                                             boolean inExpression) {
        if (inExpression) {
            return false;
        }
        if (index == start) {
            return true;
        }
        Token previous = tokens.get(index - 1);
        if (previous.punct(";") || previous.punct(":") || previous.punct(">>")
                || previous.keyword(STATEMENT_BOUNDARY_WORDS) || eachRow(tokens, index - 1, start)) {
            return true;
        }
        return mode == SqlLexer.Mode.MYSQL
                && (previous.punct(")") || previous.type() == Type.STRING
                || previous.keyword(MYSQL_ROUTINE_CHARACTERISTICS));
    }

    /** {@code FOR EACH ROW}, after which a trigger's body statement starts. */
    static boolean eachRow(List<Token> tokens, int index, int floor) {
        return index - 1 >= floor && tokens.get(index).keyword("ROW") && tokens.get(index - 1).keyword("EACH");
    }

    /**
     * PostgreSQL routine bodies given as literals ({@code AS $$…$$}, {@code AS '…'},
     * {@code AS E'…'}, {@code AS U&'…'}), each tokenized as code, plus nested dollar bodies
     * inside them. Other literals in the statement (defaults, trigger arguments) are data.
     */
    static List<Body> postgresBodies(List<Token> tokens, SqlLexer.Mode mode, String sql) {
        List<Body> bodies = new ArrayList<>();
        if (mode != SqlLexer.Mode.POSTGRES) {
            return bodies;
        }
        for (int index = 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if ((token.type() == Type.STRING || token.type() == Type.BODY) && token.depth() == 0
                    && tokens.get(index - 1).keyword("AS")) {
                addBody(bodies, token.value(), tokenizeBody(token.value(), mode, sql), mode);
            }
        }
        return bodies;
    }

    private static void addBody(List<Body> bodies, String text, List<Token> tokens, SqlLexer.Mode mode) {
        bodies.add(new Body(text, tokens));
        for (Token token : tokens) {
            if (token.type() == Type.BODY) {
                List<Token> nested;
                try {
                    nested = tokenize(token.value(), mode);
                } catch (IllegalArgumentException unlexable) {
                    nested = words(token.value());
                }
                addBody(bodies, token.value(), nested, mode);
            }
        }
    }

    /** Word and punctuation tokens of text that does not lex as SQL (for example a message with an apostrophe). */
    static List<Token> words(String text) {
        List<Token> result = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            int start = index;
            if (isWordChar(current)) {
                while (index < text.length() && isWordChar(text.charAt(index))) {
                    index++;
                }
                String word = text.substring(start, index);
                result.add(new Token(word.chars().allMatch(Character::isDigit) ? Type.NUMBER : Type.WORD,
                        word, word, start, index, 0));
            } else {
                index++;
                if (!Character.isWhitespace(current)) {
                    result.add(new Token(Type.PUNCT, String.valueOf(current), String.valueOf(current), start, index, 0));
                }
            }
        }
        return withDepths(result);
    }

    /** The PostgreSQL {@code LANGUAGE} of a routine statement, folded; null when absent. */
    static String postgresLanguage(List<Token> tokens) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).keyword("LANGUAGE") && tokens.get(index).depth() == 0) {
                Token language = tokens.get(index + 1);
                if (language.type() == Type.WORD) {
                    return language.text().toLowerCase(Locale.ROOT);
                }
                if (language.quoted() || language.type() == Type.STRING) {
                    return language.value();
                }
            }
        }
        return null;
    }

    /**
     * Whether an identifier token names {@code canonicalUpper} (a pattern over upper-case
     * names) under the engine's resolution rules. PostgreSQL folds unquoted names to lower
     * case and Oracle to upper case, and quoted names are exact, so a quoted name only
     * matches in the engine's folded case. MySQL function names are not case-sensitive, and
     * SQL Server resolves names through the database collation, which is case-insensitive by
     * default, so both compare case-insensitively.
     */
    static boolean nameMatches(Token token, Pattern canonicalUpper, SqlLexer.Mode mode) {
        if (!token.name()) {
            return false;
        }
        String value = token.quoted() ? token.value() : token.text();
        if (token.quoted()) {
            if (mode == SqlLexer.Mode.POSTGRES && !value.equals(value.toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (mode == SqlLexer.Mode.ORACLE && !value.equals(value.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return canonicalUpper.matcher(value.toUpperCase(Locale.ROOT)).matches();
    }

    static Token next(List<Token> tokens, int index) {
        return index + 1 < tokens.size() ? tokens.get(index + 1) : null;
    }

    static Token previous(List<Token> tokens, int index) {
        return index > 0 ? tokens.get(index - 1) : null;
    }

    private static IllegalArgumentException unbalanced(String sql) {
        return new IllegalArgumentException("unbalanced END in routine body of schema change SQL: "
                + SqlLexer.summarize(sql));
    }

    private static IllegalArgumentException unterminatedBody(String sql) {
        return new IllegalArgumentException("unterminated routine body (missing END) in schema change SQL: "
                + SqlLexer.summarize(sql));
    }
}
