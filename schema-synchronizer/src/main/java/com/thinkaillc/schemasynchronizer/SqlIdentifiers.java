// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The library's identifier model. Rules, per the vendor documentation:
 * <pre>
 * backend     quoted form   unquoted names       catalog stores         names compare
 * PostgreSQL  "x"           fold to lower case   exact spelling         case-sensitively
 * Oracle      "X"           fold to upper case   exact spelling         case-sensitively
 * MySQL       `x`           kept as written      tables: as written or  tables: by lower_case_table_names
 * MariaDB     `x`           kept as written        lower (l_c_t_n=1)    (0 = sensitive); columns/indexes:
 *                                                                        insensitively
 * SQL Server  [x]           kept as written      exact spelling         by the database collation
 * </pre>
 * Declared tables, columns, and indexes are {@code [A-Za-z_][A-Za-z0-9_]*} and are folded the way
 * 1.x folded them ({@link #storedForm}: upper case on Oracle, lower case elsewhere). Every
 * identifier in SQL the library emits is quoted ({@link #quote}, {@link #quoteExact}), so reserved
 * words work; live objects are addressed by their exact catalog spelling.
 */
final class SqlIdentifiers {
    /** PostgreSQL / portable default. */
    static final int DEFAULT_MAX_LENGTH = 63;
    /** MySQL / MariaDB table, column, and index name limit. */
    static final int MYSQL_MAX_LENGTH = 64;
    /** SQL Server and Oracle 12.2+ unquoted identifier limit. */
    static final int EXTENDED_MAX_LENGTH = 128;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private SqlIdentifiers() {}

    /** Whether {@code value} is a plain identifier of any length (the declarable shape). */
    static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    static String requireIdentifier(String value, String label) {
        return requireIdentifierPreservingCase(value, label, DEFAULT_MAX_LENGTH).toLowerCase(Locale.ROOT);
    }

    static String requireIdentifier(String value, String label, int maxLength) {
        return requireIdentifierPreservingCase(value, label, maxLength).toLowerCase(Locale.ROOT);
    }

    static String requireIdentifierPreservingCase(String value, String label) {
        return requireIdentifierPreservingCase(value, label, DEFAULT_MAX_LENGTH);
    }

    static String requireIdentifierPreservingCase(String value, String label, int maxLength) {
        if (value == null || value.length() > maxLength || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return value;
    }

    /** Catalog spelling of a declared identifier: what 1.x's unquoted DDL produced. */
    static String storedForm(DatabaseDialect dialect, String declared) {
        String valid = requireIdentifierPreservingCase(declared, "identifier", Integer.MAX_VALUE);
        return dialect == DatabaseDialect.ORACLE ? valid.toUpperCase(Locale.ROOT) : valid.toLowerCase(Locale.ROOT);
    }

    /**
     * Catalog spelling of the configured namespace: folded on PostgreSQL and Oracle; kept as
     * configured on SQL Server and MySQL/MariaDB, where it is checked against the server exactly.
     */
    static String storedNamespace(DatabaseDialect dialect, String namespace) {
        return ChangeSetSchemaScope.canonical(
                requireIdentifierPreservingCase(namespace, "schema", Integer.MAX_VALUE), false, dialect);
    }

    /** A declared identifier as emitted SQL: its {@link #storedForm}, quoted in the dialect's style. */
    static String quote(DatabaseDialect dialect, String declared) {
        return quoteExact(dialect, storedForm(dialect, declared));
    }

    /** The configured namespace as emitted SQL. */
    static String quoteNamespace(DatabaseDialect dialect, String namespace) {
        return quoteExact(dialect, storedNamespace(dialect, namespace));
    }

    /** A catalog name exactly as spelled, quoted in the dialect's style. */
    static String quoteExact(DatabaseDialect dialect, String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid identifier: " + name);
        }
        if (dialect == DatabaseDialect.ORACLE && name.indexOf('"') >= 0) {
            throw new IllegalArgumentException("invalid identifier: " + name
                    + " (Oracle identifiers cannot contain a double quote)");
        }
        return switch (dialect) {
            case MYSQL, MARIADB -> "`" + name.replace("`", "``") + "`";
            case SQLSERVER -> "[" + name.replace("]", "]]") + "]";
            case POSTGRESQL, ORACLE -> "\"" + name.replace("\"", "\"\"") + "\"";
        };
    }

    /**
     * A table as emitted in DDL. SQL Server qualifies it with the schema; PostgreSQL (search_path),
     * Oracle (connected user), and MySQL/MariaDB (current database) resolve it in the namespace the
     * synchronizer has bound and verified.
     */
    static String tableReference(DatabaseDialect dialect, String namespace, String exactTable) {
        String table = quoteExact(dialect, exactTable);
        return dialect == DatabaseDialect.SQLSERVER ? quoteNamespace(dialect, namespace) + "." + table : table;
    }

    /** A declared table as emitted in DDL (see {@link #tableReference}). */
    static String declaredTableReference(DatabaseDialect dialect, String namespace, String declaredTable) {
        return tableReference(dialect, namespace, storedForm(dialect, declaredTable));
    }

    static String qualified(String schema, String name) {
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(name, "identifier");
    }

    static String qualified(String schema, String name, int maxLength) {
        return requireIdentifier(schema, "schema", maxLength) + "."
                + requireIdentifier(name, "identifier", maxLength);
    }

    /** One identifier token: bare, or quoted in the dialect's style ({@code value} is unescaped). */
    record Token(String value, boolean quoted, int start, int end) {}

    /** The identifier token at {@code offset} after optional whitespace, or null when there is none. */
    static Token readToken(String sql, int offset, DatabaseDialect dialect) {
        int index = offset;
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
            index++;
        }
        if (index >= sql.length()) {
            return null;
        }
        char open = openQuote(dialect);
        char close = open == '[' ? ']' : open;
        if (sql.charAt(index) == open) {
            StringBuilder value = new StringBuilder();
            for (int at = index + 1; at < sql.length(); at++) {
                char current = sql.charAt(at);
                if (current == close) {
                    if (at + 1 < sql.length() && sql.charAt(at + 1) == close) {
                        value.append(close);
                        at++;
                        continue;
                    }
                    return new Token(value.toString(), true, index, at + 1);
                }
                value.append(current);
            }
            return null;
        }
        int end = index;
        while (end < sql.length() && SqlLexer.isIdentifierChar(sql.charAt(end))) {
            end++;
        }
        return end == index ? null : new Token(sql.substring(index, end), false, index, end);
    }

    /** The opening quote character of the dialect's identifier style. */
    static char openQuote(DatabaseDialect dialect) {
        return switch (dialect) {
            case MYSQL, MARIADB -> '`';
            case SQLSERVER -> '[';
            case POSTGRESQL, ORACLE -> '"';
        };
    }

    /**
     * The declared (lower-case) name a createSql or index token denotes. A bare token is folded; a
     * quoted token must be spelled exactly as the folded name is stored, since any other spelling
     * names a different object on a case-sensitive backend.
     */
    static String declaredName(Token token, DatabaseDialect dialect, String label, int maxLength) {
        if (token == null) {
            throw new IllegalArgumentException("missing " + label);
        }
        if (!token.quoted()) {
            return requireIdentifier(token.value(), label, maxLength);
        }
        String content = requireIdentifierPreservingCase(token.value(), label, maxLength);
        if (!content.equals(storedForm(dialect, content))) {
            throw new IllegalArgumentException(label + " " + quoteExact(dialect, content)
                    + " is quoted in a spelling other than its folded name " + quote(dialect, content)
                    + "; declared names are folded, so write it unquoted or as " + quote(dialect, content));
        }
        return content.toLowerCase(Locale.ROOT);
    }

    /**
     * The live catalog name a declared identifier denotes, or null when no live object has it.
     * The stored form always matches. A spelling that differs only by case matches when the
     * backend compares these names case-insensitively; on a case-sensitive backend the declared
     * name would not resolve to it, so the conflict is reported instead of emitting DDL against a
     * name that does not exist.
     *
     * @param caseSensitive whether the backend compares names of this kind case-sensitively
     */
    static String resolveLive(DatabaseDialect dialect, String kind, String declared, Collection<String> liveNames,
                              boolean caseSensitive) {
        String stored = storedForm(dialect, declared);
        List<String> variants = new ArrayList<>();
        for (String live : liveNames) {
            if (live.equals(stored)) {
                return live;
            }
            if (asciiCaseVariant(live, stored)) {
                variants.add(live);
            }
        }
        if (variants.isEmpty()) {
            return null;
        }
        if (!caseSensitive && variants.size() == 1) {
            return variants.get(0);
        }
        throw new IllegalStateException("live " + kind + " " + String.join(", ", variants.stream()
                .map(name -> quoteExact(dialect, name)).toList()) + " differs from declared " + kind + " "
                + quote(dialect, declared) + " only by case, and " + dialect.id() + " compares these names "
                + (caseSensitive ? "case-sensitively" : "case-insensitively but reports several")
                + ", so the declaration does not denote it; rename the live object to "
                + quote(dialect, declared) + " or declare a matching name");
    }

    /**
     * Like {@link #resolveLive} but never fails: on a case-sensitive backend a case variant is a
     * different object (for indexes, an orphan beside the declared one), so only the stored form matches.
     */
    static String matchLive(DatabaseDialect dialect, String declared, Collection<String> liveNames,
                            boolean caseSensitive) {
        String stored = storedForm(dialect, declared);
        String variant = null;
        int variants = 0;
        for (String live : liveNames) {
            if (live.equals(stored)) {
                return live;
            }
            if (asciiCaseVariant(live, stored)) {
                variant = live;
                variants++;
            }
        }
        return !caseSensitive && variants == 1 ? variant : null;
    }

    /**
     * Whether {@code live} spells the ASCII identifier {@code stored} in other letter case. Only ASCII
     * letters fold: {@link String#equalsIgnoreCase} would also equate {@code ı} or {@code K} (Kelvin)
     * with ASCII letters, which no backend's identifier folding does for these names.
     */
    static boolean asciiCaseVariant(String live, String stored) {
        if (live.length() != stored.length()) {
            return false;
        }
        for (int i = 0; i < live.length(); i++) {
            char a = live.charAt(i);
            char b = stored.charAt(i);
            if (a == b) {
                continue;
            }
            if (a >= 0x80 || b >= 0x80 || Character.toLowerCase(a) != Character.toLowerCase(b)) {
                return false;
            }
        }
        return true;
    }
}
