// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialect-aware lexical splitter used by the change-set guardrails. It classifies text
 * into code, comments, string literals, quoted identifiers, and PostgreSQL dollar bodies
 * using each engine's own quoting and comment rules, and rejects constructs whose
 * boundaries are ambiguous or executable (MySQL {@code /*! ... *}{@code /}).
 *
 * <p>This is a guardrail against accidental unsafe change sets, not a SQL parser and not a
 * security boundary against hostile SQL authors.
 */
final class SqlLexer {

    enum Mode { POSTGRES, ORACLE, SQLSERVER, MYSQL }

    enum Kind { CODE, COMMENT, STRING, QUOTED_IDENT, DOLLAR_BODY }

    /** {@code content} is the unescaped inner text for strings, identifiers, and bodies. */
    record Span(Kind kind, int start, int end, String content) {}

    private SqlLexer() {
    }

    static Mode mode(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> Mode.POSTGRES;
            case ORACLE -> Mode.ORACLE;
            case SQLSERVER -> Mode.SQLSERVER;
            case MYSQL, MARIADB -> Mode.MYSQL;
        };
    }

    static List<Span> spans(String sql, Mode mode) {
        List<Span> spans = new ArrayList<>();
        int length = sql.length();
        int codeStart = 0;
        int index = 0;
        while (index < length) {
            char current = sql.charAt(index);
            char next = index + 1 < length ? sql.charAt(index + 1) : '\0';
            int end = -1;
            Kind kind = null;
            String content = null;
            if (isLineCommentStart(sql, index, mode)) {
                int lineEnd = lineCommentEnd(sql, index, mode);
                end = lineEnd < 0 ? length : lineEnd + 1;
                kind = Kind.COMMENT;
            } else if (current == '/' && next == '*') {
                if (mode == Mode.MYSQL && (sql.startsWith("/*!", index) || sql.startsWith("/*M!", index))) {
                    throw new IllegalArgumentException(
                            "MySQL/MariaDB executable comments (/*! ... */) are not allowed in schema change SQL");
                }
                if (mode == Mode.MYSQL && sql.startsWith("/*+", index)) {
                    throw new IllegalArgumentException(
                            "MySQL/MariaDB optimizer hints (/*+ ... */) are not allowed in schema change SQL");
                }
                end = blockCommentEnd(sql, index, mode == Mode.POSTGRES || mode == Mode.SQLSERVER);
                kind = Kind.COMMENT;
            } else if (mode == Mode.ORACLE && oracleQQuoteStart(sql, index) >= 0) {
                int open = oracleQQuoteStart(sql, index);
                char delimiter = sql.charAt(open + 2);
                char close = closingDelimiter(delimiter);
                int closeAt = sql.indexOf(String.valueOf(close) + "'", open + 3);
                if (closeAt < 0) {
                    throw unterminated("q-quoted string", sql);
                }
                content = sql.substring(open + 3, closeAt);
                end = closeAt + 2;
                kind = Kind.STRING;
            } else if (current == '\'') {
                boolean backslashEscapes = mode == Mode.MYSQL
                        || (mode == Mode.POSTGRES && isEscapeStringPrefix(sql, index));
                StringBuilder inner = new StringBuilder();
                end = quotedEnd(sql, index, '\'', '\'', backslashEscapes, mode == Mode.MYSQL, inner, "string literal");
                content = inner.toString();
                kind = Kind.STRING;
            } else if (current == '"') {
                StringBuilder inner = new StringBuilder();
                end = quotedEnd(sql, index, '"', '"', mode == Mode.MYSQL, mode == Mode.MYSQL, inner,
                        "double-quoted identifier");
                content = inner.toString();
                kind = Kind.QUOTED_IDENT;
            } else if (current == '[' && mode == Mode.SQLSERVER) {
                StringBuilder inner = new StringBuilder();
                end = quotedEnd(sql, index, '[', ']', false, false, inner, "bracket identifier");
                content = inner.toString();
                kind = Kind.QUOTED_IDENT;
            } else if (current == '`' && mode == Mode.MYSQL) {
                StringBuilder inner = new StringBuilder();
                end = quotedEnd(sql, index, '`', '`', false, false, inner, "backtick identifier");
                content = inner.toString();
                kind = Kind.QUOTED_IDENT;
            } else if (current == '$' && mode == Mode.POSTGRES && !precededByIdentifierChar(sql, index)) {
                String tag = dollarTag(sql, index);
                if (tag != null) {
                    int closeAt = sql.indexOf(tag, index + tag.length());
                    if (closeAt < 0) {
                        throw unterminated("dollar-quoted body", sql);
                    }
                    content = sql.substring(index + tag.length(), closeAt);
                    end = closeAt + tag.length();
                    kind = Kind.DOLLAR_BODY;
                }
            }
            if (kind == null) {
                index++;
                continue;
            }
            int start = kind == Kind.STRING ? literalPrefixStart(sql, index, codeStart, mode) : index;
            if (start > codeStart) {
                spans.add(new Span(Kind.CODE, codeStart, start, sql.substring(codeStart, start)));
            }
            spans.add(new Span(kind, start, end, content));
            codeStart = end;
            index = end;
        }
        if (codeStart < length) {
            spans.add(new Span(Kind.CODE, codeStart, length, sql.substring(codeStart)));
        }
        return spans;
    }

    /**
     * Rebuilds {@code sql} with comments and string literals blanked to spaces. Quoted
     * identifiers are kept verbatim when {@code keepIdentifiers}, otherwise their inner text
     * is replaced by {@code X}; dollar bodies are kept verbatim when {@code keepDollarBodies}.
     * Offsets are preserved.
     */
    static String mask(String sql, Mode mode, boolean keepIdentifiers, boolean keepDollarBodies) {
        StringBuilder result = new StringBuilder(sql.length());
        for (Span span : spans(sql, mode)) {
            String text = sql.substring(span.start(), span.end());
            switch (span.kind()) {
                case CODE -> result.append(text);
                case COMMENT, STRING -> result.append(blank(text));
                case QUOTED_IDENT -> result.append(keepIdentifiers ? text : placeholder(text));
                case DOLLAR_BODY -> result.append(keepDollarBodies ? text : blank(text));
            }
        }
        return result.toString();
    }

    /**
     * Like {@code mask(sql, mode, true, true)}, except that MySQL double-quoted spans are
     * blanked unless dot-qualified: without {@code ANSI_QUOTES} they are string literals,
     * and with it a qualified {@code "db"."t"} must stay visible to the namespace check.
     */
    static String maskForScope(String sql, Mode mode, boolean keepDollarBodies) {
        StringBuilder result = new StringBuilder(sql.length());
        for (Span span : spans(sql, mode)) {
            String text = sql.substring(span.start(), span.end());
            switch (span.kind()) {
                case CODE -> result.append(text);
                case DOLLAR_BODY -> result.append(keepDollarBodies ? text : blank(text));
                case COMMENT, STRING -> result.append(blank(text));
                case QUOTED_IDENT -> result.append(mode == Mode.MYSQL && text.startsWith("\"")
                        && !dotAdjacent(sql, span.start(), span.end()) ? blank(text) : text);
            }
        }
        return result.toString();
    }

    private static boolean dotAdjacent(String sql, int start, int end) {
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(sql.charAt(before))) {
            before--;
        }
        int after = end;
        while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) {
            after++;
        }
        return (before >= 0 && sql.charAt(before) == '.') || (after < sql.length() && sql.charAt(after) == '.');
    }

    private static boolean isLineCommentStart(String sql, int index, Mode mode) {
        char current = sql.charAt(index);
        char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
        if (mode == Mode.MYSQL) {
            if (current == '#') {
                return true;
            }
            if (current == '-' && next == '-') {
                char after = index + 2 < sql.length() ? sql.charAt(index + 2) : ' ';
                return Character.isWhitespace(after) || Character.isISOControl(after);
            }
            return false;
        }
        return current == '-' && next == '-';
    }

    /**
     * Index of the character that ends the line comment at {@code start}, or -1. PostgreSQL and
     * SQL Server also end it at a bare {@code \r}; Oracle, MySQL, and MariaDB end it only at
     * {@code \n}. Ending it earlier than the engine does is not safer: a quote after the
     * {@code \r} would then hide the engine's next line inside a string.
     */
    static int lineCommentEnd(String sql, int start, Mode mode) {
        for (int index = start; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\n' || (current == '\r' && (mode == Mode.POSTGRES || mode == Mode.SQLSERVER))) {
                return index;
            }
        }
        return -1;
    }

    private static int blockCommentEnd(String sql, int start, boolean nested) {
        int depth = 0;
        int index = start;
        while (index < sql.length() - 1) {
            if (sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                if (depth == 0 || nested) {
                    depth++;
                }
                index += 2;
            } else if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                depth--;
                index += 2;
                if (depth == 0) {
                    return index;
                }
            } else {
                index++;
            }
        }
        throw unterminated("block comment", sql);
    }

    /**
     * Returns the index just past the closing delimiter. Doubled closing delimiters are
     * escapes. With {@code backslashEscapes}, {@code \x} consumes two characters; when
     * {@code rejectEscapedQuote}, a backslash before the closing delimiter is rejected
     * because its meaning depends on server mode (NO_BACKSLASH_ESCAPES / ANSI_QUOTES).
     */
    private static int quotedEnd(String sql, int open, char openChar, char closeChar, boolean backslashEscapes,
                                 boolean rejectEscapedQuote, StringBuilder inner, String what) {
        int index = open + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (backslashEscapes && current == '\\') {
                if (next == closeChar && rejectEscapedQuote) {
                    throw new IllegalArgumentException("backslash-escaped quote in " + what
                            + " is ambiguous across server modes; double the quote instead: " + summarize(sql));
                }
                if (next != '\0') {
                    inner.append(next);
                }
                index += 2;
                continue;
            }
            if (current == closeChar) {
                if (next == closeChar) {
                    inner.append(closeChar);
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            inner.append(current);
            index++;
        }
        throw unterminated(what, sql);
    }

    /** {@code E'…'} / {@code e'…'} not preceded by an identifier character. */
    private static boolean isEscapeStringPrefix(String sql, int quote) {
        return quote > 0 && (sql.charAt(quote - 1) == 'E' || sql.charAt(quote - 1) == 'e')
                && !precededByIdentifierChar(sql, quote - 1);
    }

    /** Index of the {@code q} in {@code q'} / {@code nq'} (any case) when a q-quote starts at {@code index}. */
    private static int oracleQQuoteStart(String sql, int index) {
        int qAt = index;
        char current = sql.charAt(index);
        if ((current == 'n' || current == 'N') && index + 1 < sql.length()
                && (sql.charAt(index + 1) == 'q' || sql.charAt(index + 1) == 'Q')) {
            qAt = index + 1;
        } else if (current != 'q' && current != 'Q') {
            return -1;
        }
        if (precededByIdentifierChar(sql, index) || qAt + 2 >= sql.length() || sql.charAt(qAt + 1) != '\'') {
            return -1;
        }
        char delimiter = sql.charAt(qAt + 2);
        if (Character.isWhitespace(delimiter) || delimiter == '\'') {
            return -1;
        }
        return qAt;
    }

    private static char closingDelimiter(char delimiter) {
        return switch (delimiter) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            default -> delimiter;
        };
    }

    /** Includes {@code E}, {@code N}, {@code U&}, {@code B}, {@code X}, {@code q}/{@code nq} prefixes. */
    private static int literalPrefixStart(String sql, int quoteOrPrefix, int floor, Mode mode) {
        int start = quoteOrPrefix;
        if (sql.charAt(start) != '\'') {
            return start;
        }
        if (start - 2 >= floor && sql.charAt(start - 1) == '&'
                && (sql.charAt(start - 2) == 'U' || sql.charAt(start - 2) == 'u')
                && !precededByIdentifierChar(sql, start - 2)) {
            return start - 2;
        }
        if (start - 1 >= floor && "EeNnBbXx".indexOf(sql.charAt(start - 1)) >= 0
                && !precededByIdentifierChar(sql, start - 1)) {
            return start - 1;
        }
        return start;
    }

    private static String dollarTag(String sql, int index) {
        int end = sql.indexOf('$', index + 1);
        if (end < 0) {
            return null;
        }
        String candidate = sql.substring(index, end + 1);
        return candidate.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$") ? candidate : null;
    }

    static boolean isIdentifierChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#' || value == '@';
    }

    private static boolean precededByIdentifierChar(String sql, int index) {
        return index > 0 && isIdentifierChar(sql.charAt(index - 1));
    }

    private static String blank(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            result.append(current == '\n' || current == '\r' ? current : ' ');
        }
        return result.toString();
    }

    private static String placeholder(String text) {
        if (text.length() <= 2) {
            return text;
        }
        return text.charAt(0) + "X".repeat(text.length() - 2) + text.charAt(text.length() - 1);
    }

    private static IllegalArgumentException unterminated(String what, String sql) {
        return new IllegalArgumentException("unterminated " + what + " in schema change SQL: " + summarize(sql));
    }

    static String summarize(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
    }
}
