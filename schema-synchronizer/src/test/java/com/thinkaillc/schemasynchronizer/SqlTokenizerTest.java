// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.thinkaillc.schemasynchronizer.SqlLexer.Mode;
import com.thinkaillc.schemasynchronizer.SqlTokenizer.Token;
import com.thinkaillc.schemasynchronizer.SqlTokenizer.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTokenizerTest {

    private static int statementCount(String sql, Mode mode) {
        return SqlTokenizer.statements(SqlTokenizer.tokenize(sql, mode), mode, sql).size();
    }

    private static void assertUnsplittable(String sql, Mode mode) {
        assertThatThrownBy(() -> statementCount(sql, mode))
                .as("%s: %s", mode, sql)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokensCarryTypeValueAndParenthesisDepth() {
        List<Token> tokens = SqlTokenizer.tokenize(
                "SELECT [a]]b], N'it''s', 1.5, @v FROM t /* DROP */ WHERE (x = 1) -- EXEC", Mode.SQLSERVER);

        assertThat(tokens).extracting(Token::type).containsExactly(Type.WORD, Type.QUOTED, Type.PUNCT, Type.STRING,
                Type.PUNCT, Type.NUMBER, Type.PUNCT, Type.VARIABLE, Type.WORD, Type.WORD, Type.WORD, Type.PUNCT,
                Type.WORD, Type.PUNCT, Type.NUMBER, Type.PUNCT);
        assertThat(tokens.get(1).value()).isEqualTo("a]b");
        assertThat(tokens.get(3).value()).isEqualTo("it's");
        assertThat(tokens.get(14).depth()).isEqualTo(1);
        assertThat(tokens.get(15).depth()).isZero();
        assertThat(tokens).noneMatch(token -> token.keyword("DROP") || token.keyword("EXEC"));
    }

    @Test
    void postgresEscapeStringsAreDecoded() {
        assertThat(SqlTokenizer.decodeEscapeString("\\x44ROP\\n\\101\\u0042\\U00000043\\'x''y", "sql"))
                .isEqualTo("DROP\nABC'x'y");
        assertThat(SqlTokenizer.tokenize("SELECT E'\\x44ROP'", Mode.POSTGRES).get(1).value()).isEqualTo("DROP");
        assertThatThrownBy(() -> SqlTokenizer.decodeEscapeString("\\u12", "sql"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postgresUnicodeEscapesFoldIntoOneToken() {
        List<Token> string = SqlTokenizer.tokenize("SELECT U&'!0044ROP' UESCAPE '!'", Mode.POSTGRES);
        assertThat(string).hasSize(2);
        assertThat(string.get(1).type()).isEqualTo(Type.STRING);
        assertThat(string.get(1).value()).isEqualTo("DROP");

        List<Token> identifier = SqlTokenizer.tokenize("SELECT U&\"\\0070g_read_file\"('x')", Mode.POSTGRES);
        assertThat(identifier.get(1).type()).isEqualTo(Type.QUOTED);
        assertThat(identifier.get(1).value()).isEqualTo("pg_read_file");

        assertThat(SqlTokenizer.tokenize("SELECT U&'\\+000044'", Mode.POSTGRES).get(1).value()).isEqualTo("D");
        assertThatThrownBy(() -> SqlTokenizer.tokenize("SELECT U&'+0044' UESCAPE '+'", Mode.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlTokenizer.tokenize("SELECT U&'\\00'", Mode.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postgresPlainStringWithBackslashBeforeQuoteIsAmbiguous() {
        assertThatThrownBy(() -> SqlTokenizer.tokenize("SELECT 'a\\'", Mode.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("standard_conforming_strings");
        assertThat(SqlTokenizer.tokenize("SELECT 'a\\\\'", Mode.POSTGRES).get(1).value()).isEqualTo("a\\\\");
        assertThat(SqlTokenizer.tokenize("SELECT 'C:\\dir'", Mode.POSTGRES)).hasSize(2);
    }

    @Test
    void terminatorsAndTrailingSemicolons() {
        assertThat(statementCount("SELECT 1", Mode.POSTGRES)).isEqualTo(1);
        assertThat(statementCount("SELECT 1;", Mode.POSTGRES)).isEqualTo(1);
        assertThat(statementCount("SELECT 1; SELECT 2", Mode.POSTGRES)).isEqualTo(2);
        assertThat(statementCount("SELECT 1;;", Mode.POSTGRES)).isEqualTo(2);
        assertThat(statementCount("SELECT ';' -- ;", Mode.MYSQL)).isEqualTo(1);
    }

    @Test
    void postgresBeginAtomicBodyIsOneStatement() {
        String atomic = "CREATE FUNCTION f(x integer) RETURNS integer LANGUAGE sql BEGIN ATOMIC "
                + "UPDATE items SET qty = x; SELECT CASE WHEN x > 0 THEN 1 ELSE 0 END; END";
        assertThat(statementCount(atomic, Mode.POSTGRES)).isEqualTo(1);
        assertThat(statementCount(atomic + "; SELECT 1", Mode.POSTGRES)).isEqualTo(2);
        assertThat(statementCount("CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                Mode.POSTGRES)).isEqualTo(1);
        assertUnsplittable("CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1;", Mode.POSTGRES);
    }

    @Test
    void mysqlCompoundStatementsAreOneStatement() {
        String trigger = "CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW lbl: BEGIN "
                + "IF NEW.qty IS NULL THEN SET NEW.qty = 0; ELSEIF NEW.qty < 0 THEN SET NEW.qty = 1; END IF; "
                + "WHILE NEW.qty > 10 DO SET NEW.qty = NEW.qty - 1; END WHILE; "
                + "REPEAT SET NEW.qty = NEW.qty + 1; UNTIL NEW.qty > 0 END REPEAT; "
                + "CASE NEW.note WHEN 'a' THEN SET NEW.note = 'b'; ELSE SET NEW.note = IF(NEW.qty > 1, 'c', 'd'); "
                + "END CASE; END lbl";
        assertThat(statementCount(trigger, Mode.MYSQL)).isEqualTo(1);
        assertThat(statementCount(trigger + "; SELECT 1", Mode.MYSQL)).isEqualTo(2);
        assertThat(statementCount("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW "
                + "IF NEW.qty IS NULL THEN SET NEW.qty = 0; END IF", Mode.MYSQL)).isEqualTo(1);
        assertThat(statementCount("SELECT IF(1, 2, 3); SELECT 2", Mode.MYSQL)).isEqualTo(2);
        assertUnsplittable("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN SET NEW.qty = 0;",
                Mode.MYSQL);
        assertThat(statementCount("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN SET NEW.qty = 0; "
                + "END; END", Mode.MYSQL)).isEqualTo(2);
    }

    @Test
    void oraclePlSqlBlocksAreOneStatement() {
        String trigger = "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW DECLARE n NUMBER; BEGIN "
                + "IF :NEW.qty IS NULL THEN :NEW.qty := 0; ELSIF :NEW.qty < 0 THEN :NEW.qty := 1; END IF; "
                + "FOR i IN 1..3 LOOP n := i; END LOOP; "
                + "CASE WHEN n > 1 THEN :NEW.note := 'a'; ELSE :NEW.note := 'b'; END CASE; END;";
        assertThat(statementCount(trigger, Mode.ORACLE)).isEqualTo(1);
        assertThat(statementCount("CREATE OR REPLACE PACKAGE BODY p AS PROCEDURE a IS BEGIN NULL; END; END p;",
                Mode.ORACLE)).isEqualTo(1);
        assertUnsplittable("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + ":NEW.qty := 0;", Mode.ORACLE);
        assertUnsplittable("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + ":NEW.qty := 0; END; END;", Mode.ORACLE);
        assertThat(statementCount("UPDATE items SET qty = 1; UPDATE items SET qty = 2", Mode.ORACLE)).isEqualTo(2);
    }

    @Test
    void sqlServerRoutineExtendsToTheEndOfTheBatch() {
        assertThat(statementCount("CREATE TRIGGER trg ON items AFTER INSERT AS BEGIN SET NOCOUNT ON; "
                + "UPDATE items SET qty = 0 WHERE id IN (SELECT id FROM inserted); END", Mode.SQLSERVER)).isEqualTo(1);
        assertThat(statementCount("UPDATE items SET qty = 1; UPDATE items SET qty = 2", Mode.SQLSERVER)).isEqualTo(2);
    }

    @Test
    void sqlServerStatementWithoutSemicolonStartsANewStatement() {
        for (String batch : List.of(
                "INSERT INTO dbo.t (a) VALUES (1) ALTER ROLE db_owner ADD MEMBER bob",
                "UPDATE dbo.t SET a = 1 GRANT CONTROL TO bob",
                "ALTER TABLE dbo.t ADD c int ALTER ROLE db_owner ADD MEMBER bob",
                "CREATE TABLE dbo.x (id int) CREATE LOGIN evil WITH PASSWORD = 'P@ssw0rd!'",
                "INSERT INTO dbo.t (a) VALUES (1) REVOKE SELECT ON dbo.t TO public",
                "UPDATE dbo.t SET a = 1 SELECT * INTO dbo.copy FROM dbo.t",
                "SELECT 1 GRANT CONTROL TO bob",
                "SELECT 1 SHUTDOWN",
                "SELECT CASE WHEN 1 = 1 THEN 1 END GRANT CONTROL TO bob",
                "INSERT INTO dbo.t (a) SELECT 1 SELECT * INTO dbo.copy FROM dbo.t",
                "UPDATE dbo.t SET a = 1 SET NOCOUNT ON",
                "UPDATE dbo.t SET a = 1 WITH c AS (SELECT 1 AS a) SELECT a FROM c",
                "GRANT SELECT ON dbo.t TO bob GRANT CONTROL TO bob",
                "ALTER TABLE dbo.t ADD c int DISABLE TRIGGER ALL ON DATABASE",
                "SELECT 1 END CONVERSATION @h",
                "SELECT 1 THROW 50000, 'x', 1",
                "UPDATE STATISTICS dbo.t SET NOCOUNT ON",
                "UPDATE dbo.t SET a = 1 WITH GRANT CONTROL TO bob",
                "ALTER TABLE dbo.t ADD c int IF 1 = 1 PRINT 'x'")) {
            assertThat(statementCount(batch, Mode.SQLSERVER)).as(batch).isGreaterThan(1);
        }
        for (String single : List.of(
                "ALTER TABLE dbo.t ADD c int",
                "ALTER TABLE dbo.t ALTER COLUMN c bigint",
                "ALTER TABLE dbo.t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES dbo.u (id) "
                        + "ON DELETE SET NULL ON UPDATE CASCADE",
                "MERGE dbo.t AS t USING dbo.s AS s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a "
                        + "WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a) "
                        + "WHEN NOT MATCHED BY SOURCE THEN DELETE",
                "INSERT INTO dbo.t (a) SELECT a FROM dbo.s UNION ALL SELECT a FROM dbo.u",
                "INSERT INTO dbo.t DEFAULT VALUES",
                "CREATE TABLE dbo.x (id int, note nvarchar(10)) WITH (DATA_COMPRESSION = PAGE)",
                "WITH c AS (SELECT id FROM dbo.s) UPDATE dbo.t SET a = 1 OUTPUT inserted.a WHERE id IN (SELECT id FROM c)",
                "WITH c (id) AS (SELECT 1) INSERT INTO dbo.t (a) SELECT id FROM c",
                "UPDATE dbo.t SET a = CASE WHEN b > 0 THEN 1 ELSE 2 END OUTPUT inserted.a",
                "DELETE FROM dbo.t FROM dbo.t JOIN dbo.s ON t.id = s.id",
                "GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON dbo.t TO app WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR SELECT ON dbo.t FROM app",
                "SELECT TOP 1 WITH TIES a FROM dbo.t WITH (NOLOCK) ORDER BY a",
                "SELECT a FROM dbo.t ORDER BY a OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY",
                "SELECT a FROM dbo.t GROUP BY a WITH ROLLUP",
                "SELECT 1 EXCEPT SELECT 2 INTERSECT SELECT 3",
                "CREATE INDEX ix ON dbo.t (a) INCLUDE (b) WHERE a > 0 WITH (ONLINE = ON)",
                "SELECT [select], [grant] FROM dbo.t",
                "BULK INSERT dbo.t FROM 'C:\\x.csv'")) {
            assertThat(statementCount(single, Mode.SQLSERVER)).as(single).isEqualTo(1);
        }
    }

    @Test
    void numbersEndWhereTheEngineEndsThem() {
        for (String batch : List.of("SELECT 1GRANT CONTROL TO bob", "UPDATE dbo.t SET a = 1GRANT CONTROL TO bob",
                "SELECT 0x1FPRINT 'x'", "SELECT 1e1PRINT 'x'", "SELECT 1.e5PRINT 'x'", "SELECT $1PRINT 'x'",
                "SELECT .5PRINT 'x'")) {
            assertThat(statementCount(batch, Mode.SQLSERVER)).as(batch).isEqualTo(2);
        }
        assertThat(SqlTokenizer.tokenize("SELECT 1.5e-3x", Mode.SQLSERVER)).extracting(Token::text)
                .containsExactly("SELECT", "1.5e-3", "x");
        assertThat(SqlTokenizer.tokenize("FOR i IN 1..3 LOOP", Mode.ORACLE)).extracting(Token::text)
                .containsExactly("FOR", "i", "IN", "1", ".", ".", "3", "LOOP");
        assertThat(SqlTokenizer.tokenize("SELECT 1abc", Mode.MYSQL).get(1).type()).isEqualTo(Type.WORD);
    }

    /** Each form was run on SQL Server 2019 as {@code SELECT <form>PRINT 'x'}; the count is what the engine ran. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "1EGRANT CONTROL TO bob | 2",
            "1.EGRANT CONTROL TO bob | 2",
            "1.5EGRANT CONTROL TO bob | 2",
            "1E+GRANT CONTROL TO bob | 2",
            "1e-GRANT CONTROL TO bob | 2",
            ".5EGRANT CONTROL TO bob | 2",
            "1.GRANT CONTROL TO bob | 2",
            "1.5e+10GRANT CONTROL TO bob | 2",
            "0xGRANT CONTROL TO bob | 2",
            "0x1FGRANT CONTROL TO bob | 2",
            "0X1fGRANT CONTROL TO bob | 2",
            "$1GRANT CONTROL TO bob | 2",
            "$1.GRANT CONTROL TO bob | 2",
            "$.5GRANT CONTROL TO bob | 2",
            "$-1GRANT CONTROL TO bob | 2",
            "$ 1GRANT CONTROL TO bob | 2",
            "£1GRANT CONTROL TO bob | 2",
            "€1GRANT CONTROL TO bob | 2",
            "¥1GRANT CONTROL TO bob | 2",
            "₩1GRANT CONTROL TO bob | 2",
            "＄1GRANT CONTROL TO bob | 2",
            "$GRANT | 1",
    })
    void sqlServerNumericLiteralsEndWhereTheEngineEndsThem(String tail, int statements) {
        for (String prefix : List.of("SELECT ", "UPDATE dbo.t SET a = ")) {
            String sql = prefix + tail;
            assertThat(statementCount(sql, Mode.SQLSERVER)).as(sql).isEqualTo(statements);
        }
    }

    /** Every T-SQL statement form, each run after a complete statement with no semicolon. */
    static List<String> tsqlStatements() {
        return List.of(
                "SELECT 2", "INSERT INTO dbo.t (a) VALUES (2)", "UPDATE dbo.t SET a = 2", "DELETE FROM dbo.t",
                "MERGE dbo.t AS x USING dbo.s AS y ON x.id = y.id WHEN MATCHED THEN UPDATE SET a = 1;",
                "WITH c AS (SELECT 1 AS a) SELECT a FROM c", "BULK INSERT dbo.t FROM 'C:\\x.csv'",
                "TRUNCATE TABLE dbo.t", "UPDATE STATISTICS dbo.t", "READTEXT dbo.t.a @p 0 1",
                "WRITETEXT dbo.t.a @p 'x'", "UPDATETEXT dbo.t.a @p 0 0 'x'",
                "CREATE TABLE dbo.z (id int)", "ALTER TABLE dbo.t ADD c int", "DROP TABLE dbo.t",
                "DROP SIGNATURE FROM dbo.p BY CERTIFICATE c", "DROP SENSITIVITY CLASSIFICATION FROM dbo.t.a",
                "GRANT CONTROL TO bob", "DENY SELECT ON dbo.t TO bob", "REVOKE SELECT ON dbo.t FROM bob",
                "ADD SIGNATURE TO dbo.p BY CERTIFICATE c", "ADD COUNTERSIGNATURE TO dbo.p BY CERTIFICATE c",
                "ADD SENSITIVITY CLASSIFICATION TO dbo.t.a WITH (LABEL = 'x')",
                "EXECUTE AS LOGIN = 'sa'", "EXEC AS USER = 'dbo'", "REVERT", "SETUSER 'dbo'",
                "EXEC dbo.p", "EXECUTE dbo.p",
                "OPEN MASTER KEY DECRYPTION BY PASSWORD = 'x'", "CLOSE MASTER KEY",
                "OPEN SYMMETRIC KEY k DECRYPTION BY CERTIFICATE c", "CLOSE SYMMETRIC KEY k",
                "CLOSE ALL SYMMETRIC KEYS",
                "BEGIN DIALOG CONVERSATION @h FROM SERVICE a TO SERVICE 'b'", "BEGIN CONVERSATION TIMER (@h) TIMEOUT = 5",
                "END CONVERSATION @h", "SET NOCOUNT ON", "SET ROWCOUNT 1", "SET @v = 1", "SET CONTEXT_INFO 0x01",
                "BEGIN TRANSACTION", "BEGIN TRAN", "BEGIN DISTRIBUTED TRANSACTION", "SAVE TRANSACTION s",
                "COMMIT", "COMMIT WORK", "ROLLBACK", "ROLLBACK WORK",
                "KILL 52", "KILL QUERY NOTIFICATION SUBSCRIPTION ALL", "KILL STATS JOB 1", "SHUTDOWN",
                "CHECKPOINT", "DBCC CHECKDB", "BACKUP DATABASE d TO DISK = 'x'",
                "BACKUP CERTIFICATE c TO FILE = 'x'", "RESTORE DATABASE d FROM DISK = 'x'",
                "RESTORE MASTER KEY FROM FILE = 'x' DECRYPTION BY PASSWORD = 'y' ENCRYPTION BY PASSWORD = 'z'",
                "RECONFIGURE", "USE master", "PRINT 'x'", "RAISERROR('x', 16, 1)", "THROW 50000, 'x', 1",
                "THROW @n, @m, 1", "WAITFOR DELAY '00:00:01'", "GOTO lbl", "RETURN", "BREAK", "CONTINUE",
                "IF 1 = 1 PRINT 'x'", "ELSE PRINT 'x'", "WHILE 1 = 1 BREAK", "BEGIN PRINT 'x' END",
                "BEGIN TRY PRINT 'x' END TRY BEGIN CATCH PRINT 'y' END CATCH",
                "DECLARE @v int", "DECLARE c CURSOR FOR SELECT 1", "OPEN c", "FETCH NEXT FROM c", "CLOSE c",
                "DEALLOCATE c", "ENABLE TRIGGER t ON dbo.tab", "DISABLE TRIGGER t ON dbo.tab",
                "LINENO 1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tsqlStatements")
    void everyTsqlStatementStartsANewStatementAfterAnotherStatement(String statement) {
        for (String prefix : List.of("UPDATE dbo.t SET a = 1 ", "SELECT 1 ", "INSERT INTO dbo.t (a) VALUES (1) ",
                "ALTER TABLE dbo.t ADD c int ", "SELECT 1 AS x ")) {
            String sql = prefix + statement;
            List<List<Token>> statements =
                    SqlTokenizer.statements(SqlTokenizer.tokenize(sql, Mode.SQLSERVER), Mode.SQLSERVER, sql);
            assertThat(statements).as(sql).hasSizeGreaterThan(1);
            assertThat(statements.get(1).get(0).start()).as(sql).isEqualTo(prefix.length());
        }
    }

    @Test
    void addStartsAStatementOnlyForSignaturesAndClassifications() {
        for (String single : List.of("ALTER TABLE dbo.t ADD signature varbinary(100)",
                "ALTER TABLE dbo.t ADD countersignature int, sensitivity int",
                "ALTER TABLE dbo.t WITH CHECK ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES dbo.u (id)",
                "ALTER TABLE dbo.t WITH NOCHECK ADD CONSTRAINT ck CHECK (a > 0)",
                "ALTER TABLE dbo.t ADD sensitivity int")) {
            assertThat(statementCount(single, Mode.SQLSERVER)).as(single).isEqualTo(1);
        }
    }

    /** SQL Server reports a syntax error for these after a statement unless a semicolon comes first. */
    @Test
    void serviceBrokerStatementsStartOnlyAfterASemicolon() {
        for (String single : List.of("SELECT TOP 1 receive x FROM dbo.t", "SELECT receive * 2 FROM dbo.t",
                "SELECT 1 receive", "UPDATE dbo.t SET receive = 1",
                "SELECT get conversation, move conversation FROM dbo.t",
                "SELECT a FROM dbo.t JOIN dbo.u send ON send.id = t.id")) {
            assertThat(statementCount(single, Mode.SQLSERVER)).as(single).isEqualTo(1);
        }
        for (String batch : List.of("SELECT 1; RECEIVE TOP (1) * FROM dbo.q", "SELECT 1; MOVE CONVERSATION @h TO @g",
                "SELECT 1; GET CONVERSATION GROUP @g FROM dbo.q", "SELECT 1; SEND ON CONVERSATION @h MESSAGE TYPE t")) {
            assertThat(statementCount(batch, Mode.SQLSERVER)).as(batch).isEqualTo(2);
        }
    }

    /** Checked on SQL Server: every statement word is reserved except these, which are column names. */
    @Test
    void unreservedWordsAreColumnsNotStatements() {
        for (String single : List.of("UPDATE dbo.t SET load = 1, dump = 2 WHERE id = 1",
                "ALTER TABLE dbo.t ADD load INT NULL", "SELECT id, load FROM dbo.t",
                "INSERT INTO dbo.t (id, load) SELECT id, load FROM dbo.u", "SELECT dump FROM dbo.t")) {
            assertThat(statementCount(single, Mode.SQLSERVER)).as(single).isEqualTo(1);
        }
    }

    @Test
    void lineCommentsEndWhereTheEngineEndsThem() {
        for (Mode mode : List.of(Mode.POSTGRES, Mode.SQLSERVER)) {
            assertThat(SqlTokenizer.tokenize("SELECT 1 --\r, secret", mode)).extracting(Token::text)
                    .as("%s", mode).containsExactly("SELECT", "1", ",", "secret");
            assertThat(SqlTokenizer.tokenize("SELECT 1 --\r\n, secret", mode)).extracting(Token::text)
                    .as("%s", mode).containsExactly("SELECT", "1", ",", "secret");
            assertThat(SqlTokenizer.tokenize("SELECT 1 -- note\n, secret", mode)).extracting(Token::text)
                    .as("%s", mode).containsExactly("SELECT", "1", ",", "secret");
        }
        for (Mode mode : List.of(Mode.MYSQL, Mode.ORACLE)) {
            assertThat(SqlTokenizer.tokenize("SELECT 1 --\r, secret", mode)).extracting(Token::text)
                    .as("%s", mode).containsExactly("SELECT", "1");
            assertThat(SqlTokenizer.tokenize("SELECT 1 --\r\n, secret", mode)).extracting(Token::text)
                    .as("%s", mode).containsExactly("SELECT", "1", ",", "secret");
        }
        assertThat(SqlTokenizer.tokenize("SELECT 1 #\r, secret", Mode.MYSQL)).extracting(Token::text)
                .containsExactly("SELECT", "1");
        assertThat(SqlTokenizer.tokenize("SELECT 1 --\r\n, secret", Mode.MYSQL)).extracting(Token::text)
                .containsExactly("SELECT", "1", ",", "secret");
        assertThat(SqlLexer.mask("SELECT 1 --x\rCREATE", Mode.POSTGRES, true, true)).isEqualTo("SELECT 1    \rCREATE");
        assertThat(statementCount("SELECT 1 AS x --\r CREATE TABLE dbo.p (x int)", Mode.SQLSERVER)).isEqualTo(2);
        assertThat(statementCount("SELECT 1 --\r; CREATE TABLE p (x int)", Mode.POSTGRES)).isEqualTo(2);
    }

    @Test
    void caseExpressionEndIsNotABlockEnd() {
        assertThat(statementCount("CREATE FUNCTION f(a INT) RETURNS INT DETERMINISTIC "
                + "RETURN CASE WHEN a > 0 THEN IF(a > 10, 2, 1) ELSE 0 END", Mode.MYSQL)).isEqualTo(1);
        assertThat(statementCount("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW "
                + "SET NEW.note = CASE WHEN NEW.qty > 0 THEN REPEAT('x', 2) ELSE '' END", Mode.MYSQL)).isEqualTo(1);
        assertThat(statementCount("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + "SET NEW.qty = CASE WHEN NEW.qty > 0 THEN IF(NEW.qty > 1, 1, 2) ELSE 0 END; END", Mode.MYSQL))
                .isEqualTo(1);
        assertThat(statementCount("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + "IF NEW.qty > 0 THEN SET NEW.note = 'a'; ELSE IF NEW.qty < 0 THEN SET NEW.note = 'b'; END IF; "
                + "END IF; lbl: REPEAT SET NEW.qty = NEW.qty - 1; UNTIL NEW.qty <= 0 END REPEAT lbl; "
                + "CASE WHEN NEW.qty > 0 THEN SET NEW.note = CASE WHEN NEW.qty > 5 THEN 'big' ELSE 'small' END; "
                + "ELSE SET NEW.note = 'none'; END CASE; END", Mode.MYSQL)).isEqualTo(1);
        assertUnsplittable("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + "IF NEW.qty > 0 THEN SET NEW.note = CASE WHEN 1 THEN 'a' ELSE 'b' END; END", Mode.MYSQL);

        assertThat(statementCount("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + "FOR i IN 1 .. CASE WHEN :NEW.qty > 0 THEN :NEW.qty ELSE 1 END LOOP :NEW.note := 'x'; END LOOP; "
                + "END;", Mode.ORACLE)).isEqualTo(1);
        assertUnsplittable("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN "
                + "FOR i IN 1 .. CASE WHEN :NEW.qty > 0 THEN :NEW.qty ELSE 1 END LOOP :NEW.note := 'x'; END;",
                Mode.ORACLE);
    }

    @Test
    void routineKindsAreRecognizedPerDialect() {
        assertThat(SqlTokenizer.routine(SqlTokenizer.tokenize("CREATE OR ALTER PROC p AS SELECT 1", Mode.SQLSERVER),
                Mode.SQLSERVER)).isNotNull();
        assertThat(SqlTokenizer.routine(SqlTokenizer.tokenize(
                "CREATE DEFINER = 'app'@'%' TRIGGER t BEFORE INSERT ON items FOR EACH ROW SET NEW.qty = 1",
                Mode.MYSQL), Mode.MYSQL)).isNotNull();
        assertThat(SqlTokenizer.routine(SqlTokenizer.tokenize("CREATE TYPE mood AS ENUM ('a')", Mode.POSTGRES),
                Mode.POSTGRES)).isNull();
        assertThat(SqlTokenizer.routine(SqlTokenizer.tokenize("CREATE TABLE t (id INT)", Mode.ORACLE),
                Mode.ORACLE)).isNull();
    }

    @Test
    void namesMatchUnderEachBackendsFolding() {
        Pattern name = Pattern.compile("PG_READ_FILE|DBMS_SQL|XP_CMDSHELL|LOAD_FILE");
        assertThat(nameMatches("SELECT \"pg_read_file\"", Mode.POSTGRES, name)).isTrue();
        assertThat(nameMatches("SELECT PG_Read_File", Mode.POSTGRES, name)).isTrue();
        assertThat(nameMatches("SELECT \"PG_READ_FILE\"", Mode.POSTGRES, name)).isFalse();
        assertThat(nameMatches("SELECT \"DBMS_SQL\"", Mode.ORACLE, name)).isTrue();
        assertThat(nameMatches("SELECT \"dbms_sql\"", Mode.ORACLE, name)).isFalse();
        assertThat(nameMatches("SELECT [Xp_CmdShell]", Mode.SQLSERVER, name)).isTrue();
        assertThat(nameMatches("SELECT `Load_File`", Mode.MYSQL, name)).isTrue();
        assertThat(nameMatches("SELECT 'pg_read_file'", Mode.POSTGRES, name)).isFalse();
    }

    private static boolean nameMatches(String sql, Mode mode, Pattern name) {
        return SqlTokenizer.nameMatches(SqlTokenizer.tokenize(sql, mode).get(1), name, mode);
    }

    @Test
    void postgresBodiesAreOnlyTheLiteralAfterAs() {
        String sql = "CREATE FUNCTION f(a text DEFAULT 'DROP') RETURNS int AS 'SELECT 1' LANGUAGE sql";
        List<SqlTokenizer.Body> bodies = SqlTokenizer.postgresBodies(SqlTokenizer.tokenize(sql, Mode.POSTGRES),
                Mode.POSTGRES, sql);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst().tokens()).extracting(Token::text).containsExactly("SELECT", "1");

        String nested = "CREATE FUNCTION f() RETURNS void AS $a$ BEGIN PERFORM $b$it's$b$; END $a$ LANGUAGE plpgsql";
        assertThat(SqlTokenizer.postgresBodies(SqlTokenizer.tokenize(nested, Mode.POSTGRES), Mode.POSTGRES, nested))
                .hasSize(2);

        String unlexable = "CREATE FUNCTION f() RETURNS text AS 'SELECT ''open' LANGUAGE sql";
        assertThatThrownBy(() -> SqlTokenizer.postgresBodies(SqlTokenizer.tokenize(unlexable, Mode.POSTGRES),
                Mode.POSTGRES, unlexable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routine body could not be tokenized");
        assertThat(SqlTokenizer.postgresLanguage(SqlTokenizer.tokenize(sql, Mode.POSTGRES))).isEqualTo("sql");
    }
}
