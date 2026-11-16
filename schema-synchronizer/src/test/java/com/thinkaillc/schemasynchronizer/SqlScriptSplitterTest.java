// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptSplitterTest {

    @Test
    void oracleBlockDoesNotSwallowFollowingStatements() {
        List<String> statements = SqlScriptSplitter.split(
                "BEGIN NULL; END;\nCREATE TABLE items (id INT);", "oracle");

        assertThat(statements).containsExactly("BEGIN NULL; END;", "CREATE TABLE items (id INT);");
    }

    @Test
    void oracleRoutineWithNamedEndDoesNotSwallowFollowingStatements() {
        List<String> statements = SqlScriptSplitter.split(
                "CREATE PROCEDURE p IS BEGIN NULL; END p;\nCREATE TABLE items (id INT);", "oracle");

        assertThat(statements).containsExactly(
                "CREATE PROCEDURE p IS BEGIN NULL; END p;", "CREATE TABLE items (id INT);");
    }

    @Test
    void oracleNestedBlocksStayIntact() {
        List<String> statements = SqlScriptSplitter.split(
                "DECLARE n NUMBER;\nBEGIN\n  FOR i IN 1..3 LOOP\n    NULL;\n  END LOOP;\nEND;\n"
                        + "CREATE TABLE items (id INT);",
                "oracle");

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).startsWith("DECLARE n NUMBER;").endsWith("END;");
        assertThat(statements.get(1)).isEqualTo("CREATE TABLE items (id INT);");
    }

    @Test
    void stringContentsSurviveSplitting() {
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO notes VALUES ('a  b');", "postgresql");

        assertThat(statements).containsExactly("INSERT INTO notes VALUES ('a  b');");
    }

    @Test
    void lineCommentsInsideAStatementKeepTheirNewline() {
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO notes -- keep reading\nVALUES ('a b');", "postgresql");

        assertThat(statements).containsExactly("INSERT INTO notes -- keep reading\nVALUES ('a b');");
    }

    @Test
    void postgresRoutineBodiesStayIntact() {
        List<String> statements = SqlScriptSplitter.split(
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; END $$ LANGUAGE plpgsql;\nSELECT 1;",
                "postgresql");

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("$$ BEGIN PERFORM 1; END $$");
        assertThat(statements.get(1)).isEqualTo("SELECT 1;");
    }

    @Test
    void aLoneRoutineStillEndsAtItsBlock() {
        List<String> statements = SqlScriptSplitter.split("BEGIN NULL; END;", "oracle");

        assertThat(statements).containsExactly("BEGIN NULL; END;");
    }
}