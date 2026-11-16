// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a plain SQL script into executable statements, using the same tokenizer the
 * synchronizer applies to change-set SQL. Routines (functions, procedures, triggers) stay
 * intact — on Oracle a block or routine ends at the {@code END} closing its outermost block,
 * so the script continues with the statements after it — strings and quoted identifiers never
 * end a statement, and comments between statements are dropped. Statements are returned as the
 * original script slices: whitespace inside strings and the newlines after {@code --} comments
 * are part of the statement's meaning and must not change.
 *
 * <p>Used by adoption baselines: a versioned SQL snapshot of an existing database is applied
 * statement by statement before the schema is synchronized.
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    /**
     * Splits {@code script} into statements terminated by semicolons. The dialect selects the
     * statement-boundary rules; {@code h2} and unknown ids use the PostgreSQL rules, which is
     * what H2 in {@code MODE=PostgreSQL} expects.
     *
     * @throws IllegalArgumentException when the dialect id is known but unsupported here
     */
    public static List<String> split(String script, String dialectId) {
        if (script == null || script.isBlank()) {
            return List.of();
        }
        SqlLexer.Mode mode = modeFor(dialectId);
        List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(script, mode);
        List<String> statements = new ArrayList<>();
        for (List<SqlTokenizer.Token> group : SqlTokenizer.scriptStatements(tokens, mode, script)) {
            if (group.isEmpty()) {
                continue;
            }
            // The statement is the original slice: collapsing its whitespace would change string
            // contents and would join a -- comment with the code it precedes.
            String statement = script.substring(group.get(0).start(), group.get(group.size() - 1).end()).trim();
            if (statement.isEmpty() || statement.startsWith("--")) {
                continue;
            }
            statements.add(statement.endsWith(";") ? statement : statement + ";");
        }
        return List.copyOf(statements);
    }

    private static SqlLexer.Mode modeFor(String dialectId) {
        if (dialectId == null || dialectId.isBlank() || "h2".equalsIgnoreCase(dialectId)
                || "postgresql".equalsIgnoreCase(dialectId) || "postgres".equalsIgnoreCase(dialectId)) {
            return SqlLexer.Mode.POSTGRES;
        }
        return DatabaseDialect.parse(dialectId).lexerMode();
    }
}
