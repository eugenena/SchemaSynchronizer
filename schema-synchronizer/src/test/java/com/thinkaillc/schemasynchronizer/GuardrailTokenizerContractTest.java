// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract cells for the tokenizer-based guardrails, per dialect: the forbidden form is
 * rejected, look-alikes in literals, identifiers, and comments are accepted, and one
 * statement's context does not leak into another.
 */
class GuardrailTokenizerContractTest {

    private static final DatabaseDialect PG = DatabaseDialect.POSTGRESQL;
    private static final DatabaseDialect MYSQL = DatabaseDialect.MYSQL;
    private static final DatabaseDialect MARIADB = DatabaseDialect.MARIADB;
    private static final DatabaseDialect MSSQL = DatabaseDialect.SQLSERVER;
    private static final DatabaseDialect ORACLE = DatabaseDialect.ORACLE;

    private static String namespace(DatabaseDialect dialect) {
        return switch (dialect) {
            case POSTGRESQL -> "public";
            case MYSQL, MARIADB -> "appdb";
            case SQLSERVER -> "dbo";
            case ORACLE -> "APP";
        };
    }

    private static void accepted(DatabaseDialect dialect, String sql) {
        assertThatCode(() -> {
            NonDestructiveSqlPolicy.requireSafe(sql, dialect);
            ChangeSetSchemaScope.requireScoped(sql, namespace(dialect), dialect);
        }).as("%s: %s", dialect, sql).doesNotThrowAnyException();
    }

    private static void rejected(DatabaseDialect dialect, String sql) {
        assertThatThrownBy(() -> {
            NonDestructiveSqlPolicy.requireSafe(sql, dialect);
            ChangeSetSchemaScope.requireScoped(sql, namespace(dialect), dialect);
        }).as("%s: %s", dialect, sql).isInstanceOf(IllegalArgumentException.class);
    }

    private static void policyRejects(DatabaseDialect dialect, String sql, String message) {
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(sql, dialect))
                .as("%s: %s", dialect, sql)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static void scopeRejects(DatabaseDialect dialect, String sql, String message) {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(sql, namespace(dialect), dialect))
                .as("%s: %s", dialect, sql)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static void scopeAccepts(DatabaseDialect dialect, String sql) {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(sql, namespace(dialect), dialect))
                .as("%s: %s", dialect, sql)
                .doesNotThrowAnyException();
    }

    private static void verificationAccepted(DatabaseDialect dialect, String sql) {
        assertThatCode(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(sql, dialect))
                .as("%s: %s", dialect, sql)
                .doesNotThrowAnyException();
    }

    private static void verificationRejected(DatabaseDialect dialect, String sql) {
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(sql, dialect))
                .as("%s: %s", dialect, sql)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String DESTRUCTIVE = "destructive or manually-reviewed SQL";
    private static final String BODY_STATEMENT = "must not change roles, privileges, or triggers";
    private static final String BODY_DDL = "must not run DDL";
    private static final String CATALOG = "system catalog";
    private static final String THREE_PART = "database.schema.object";

    private static final String PG_FUNCTION = "CREATE FUNCTION f() RETURNS void AS $$ BEGIN ";
    private static final String PG_FUNCTION_END = " END $$ LANGUAGE plpgsql";
    private static final String MYSQL_TRIGGER = "CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN ";
    private static final String MSSQL_TRIGGER = "CREATE TRIGGER trg ON items AFTER INSERT AS BEGIN ";
    private static final String ORACLE_TRIGGER =
            "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW BEGIN ";

    /** Admin and dynamic-SQL functions named through quoted, bracket, backtick, or Unicode-escaped identifiers. */
    @Nested
    class QuotedAdminFunctionNames {

        @Test
        void postgres() {
            policyRejects(PG, "UPDATE items SET note = \"pg_read_file\"('/etc/passwd')", DESTRUCTIVE);
            policyRejects(PG, "UPDATE items SET note = pg_catalog.\"pg_read_file\"('/etc/passwd')", DESTRUCTIVE);
            policyRejects(PG, "UPDATE items SET note = U&\"\\0070g_read_file\"('/etc/passwd')", DESTRUCTIVE);
            policyRejects(PG, "UPDATE items SET note = \"dblink_exec\"('c', 'x')", DESTRUCTIVE);
            policyRejects(PG, "UPDATE items SET note = \"lo_import\" ('/etc/passwd')", DESTRUCTIVE);
            policyRejects(PG, PG_FUNCTION + "PERFORM \"pg_terminate_backend\"(1);" + PG_FUNCTION_END, DESTRUCTIVE);

            accepted(PG, "UPDATE items SET note = 'pg_read_file(''/etc/passwd'')'");
            accepted(PG, "UPDATE items SET pg_read_file_count = 1");
            accepted(PG, "UPDATE items SET note = 'x' -- pg_read_file('/etc/passwd')");
            // Quoted upper case is a different (user) function on PostgreSQL, which folds to lower case.
            accepted(PG, "UPDATE items SET note = \"PG_READ_FILE\"('x')");
        }

        @Test
        void mysql() {
            policyRejects(MYSQL, "UPDATE items SET note = `load_file`('/etc/passwd')", DESTRUCTIVE);
            policyRejects(MYSQL, "UPDATE items SET note = `LOAD_File` ('/etc/passwd')", DESTRUCTIVE);
            accepted(MYSQL, "UPDATE items SET note = 'load_file(x)'");
            accepted(MYSQL, "UPDATE items SET `load_file` = 1");
            accepted(MYSQL, "UPDATE items SET note = 'x' -- load_file('/etc/passwd')");
        }

        @Test
        void mariadb() {
            policyRejects(MARIADB, "UPDATE items SET note = `load_file`('/etc/passwd')", DESTRUCTIVE);
            accepted(MARIADB, "UPDATE items SET load_file_note = 'load_file'");
            accepted(MARIADB, "UPDATE items SET note = 'x' /* load_file('/etc/passwd') */");
        }

        @Test
        void sqlServer() {
            policyRejects(MSSQL, "UPDATE items SET note = (SELECT TOP 1 x FROM [OpenRowSet](N'a', N'b', N'c'))",
                    DESTRUCTIVE);
            policyRejects(MSSQL, "UPDATE items SET note = [dbo].[XP_CMDSHELL]", DESTRUCTIVE);
            policyRejects(MSSQL, "UPDATE items SET note = [sp_executesql]", DESTRUCTIVE);
            accepted(MSSQL, "UPDATE items SET note = N'xp_cmdshell'");
            accepted(MSSQL, "UPDATE items SET [xp_cmdshell_note] = 1");
            accepted(MSSQL, "UPDATE items SET note = 'x' /* xp_cmdshell */");
        }

        @Test
        void oracle() {
            policyRejects(ORACLE, "UPDATE items SET note = \"DBMS_SQL\".OPEN_CURSOR()", DESTRUCTIVE);
            policyRejects(ORACLE, "UPDATE items SET note = SYS.\"DBMS_SQL\".OPEN_CURSOR()", DESTRUCTIVE);
            policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.note := \"DBMS_SCHEDULER\".GENERATE_JOB_NAME; END;",
                    DESTRUCTIVE);
            accepted(ORACLE, "UPDATE items SET note = q'[DBMS_SQL]'");
            // Quoted lower case is a different identifier on Oracle, which folds to upper case.
            accepted(ORACLE, "UPDATE items SET \"dbms_sql\" = 1");
            accepted(ORACLE, "UPDATE items SET note = 'x' -- DBMS_SQL");
        }
    }

    /** Role, privilege, trigger, and SELECT INTO statements inside routine and trigger bodies. */
    @Nested
    class ForbiddenStatementsInsideBodies {

        @Test
        void postgres() {
            policyRejects(PG, PG_FUNCTION + "SET ROLE admin;" + PG_FUNCTION_END, BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "SET LOCAL ROLE admin;" + PG_FUNCTION_END, BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "SET SESSION AUTHORIZATION admin;" + PG_FUNCTION_END, BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "RESET ROLE;" + PG_FUNCTION_END, BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "SET \"role\" = admin;" + PG_FUNCTION_END, BODY_STATEMENT);
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void LANGUAGE sql AS 'SET ROLE admin'", BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "ALTER TABLE items DISABLE TRIGGER ALL;" + PG_FUNCTION_END, BODY_DDL);
            policyRejects(PG, PG_FUNCTION + "SELECT * INTO TEMP copy FROM items;" + PG_FUNCTION_END, BODY_DDL);
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void AS $$ SELECT * INTO copy FROM items $$ LANGUAGE sql",
                    BODY_DDL);
            policyRejects(PG, PG_FUNCTION + "UPDATE items SET role = 1; SET ROLE admin;" + PG_FUNCTION_END,
                    BODY_STATEMENT);

            accepted(PG, PG_FUNCTION + "RAISE NOTICE 'SET ROLE admin';" + PG_FUNCTION_END);
            accepted(PG, PG_FUNCTION + "UPDATE items SET role = 'admin';" + PG_FUNCTION_END);
            accepted(PG, PG_FUNCTION + "-- SET ROLE admin\n NULL;" + PG_FUNCTION_END);
            accepted(PG, "CREATE FUNCTION f() RETURNS integer AS $$ DECLARE n integer; BEGIN "
                    + "SELECT count(*) INTO n FROM items; RETURN n; END $$ LANGUAGE plpgsql");
        }

        @Test
        void mysql() {
            policyRejects(MYSQL, MYSQL_TRIGGER + "SET ROLE admin; END", BODY_STATEMENT);
            policyRejects(MYSQL, MYSQL_TRIGGER + "SET DEFAULT ROLE admin TO app; END", BODY_STATEMENT);
            policyRejects(MYSQL, MYSQL_TRIGGER + "LOCK TABLES items WRITE; END", BODY_STATEMENT);
            policyRejects(MYSQL, MYSQL_TRIGGER + "UPDATE item_log SET role = 1; SET ROLE admin; END", BODY_STATEMENT);
            policyRejects(MYSQL, MYSQL_TRIGGER + "SELECT note INTO OUTFILE '/tmp/x' FROM items; END", DESTRUCTIVE);

            accepted(MYSQL, MYSQL_TRIGGER + "SET NEW.note = 'SET ROLE admin'; END");
            accepted(MYSQL, MYSQL_TRIGGER + "SET NEW.role = 'admin'; END");
            accepted(MYSQL, MYSQL_TRIGGER + "SELECT COUNT(*) INTO @n FROM items; END");
            accepted(MYSQL, MYSQL_TRIGGER + "# SET ROLE admin\n SET NEW.qty = 1; END");
        }

        @Test
        void mariadb() {
            policyRejects(MARIADB, MYSQL_TRIGGER + "SET ROLE admin; END", BODY_STATEMENT);
            policyRejects(MARIADB, MYSQL_TRIGGER + "IF NEW.qty > 1 THEN SET ROLE NONE; END IF; END", BODY_STATEMENT);
            policyRejects(MARIADB, MYSQL_TRIGGER + "FLUSH PRIVILEGES; END", BODY_STATEMENT);
            accepted(MARIADB, MYSQL_TRIGGER + "SET NEW.note = 'FLUSH PRIVILEGES'; END");
            accepted(MARIADB, MYSQL_TRIGGER + "SET NEW.`role` = 'x'; END");
        }

        @Test
        void sqlServer() {
            policyRejects(MSSQL, MSSQL_TRIGGER + "DENY SELECT ON items TO app; END", BODY_STATEMENT);
            policyRejects(MSSQL, MSSQL_TRIGGER + "DISABLE TRIGGER other_trg ON items; END", BODY_STATEMENT);
            policyRejects(MSSQL, MSSQL_TRIGGER + "SETUSER 'admin'; END", BODY_STATEMENT);
            policyRejects(MSSQL, MSSQL_TRIGGER + "EXECUTE AS USER = 'admin'; END", DESTRUCTIVE);
            policyRejects(MSSQL, MSSQL_TRIGGER + "SELECT * INTO copy FROM inserted; END", BODY_DDL);
            policyRejects(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET note = 'x'; DENY SELECT ON items TO app; END",
                    BODY_STATEMENT);

            accepted(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET note = N'DENY SELECT'; END");
            accepted(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET [deny] = 1; END");
            accepted(MSSQL, MSSQL_TRIGGER + "DECLARE @n INT; SELECT @n = COUNT(*) FROM inserted; "
                    + "INSERT INTO item_log (note) SELECT note FROM inserted; END");
            accepted(MSSQL, MSSQL_TRIGGER + "-- DISABLE TRIGGER other_trg ON items\n SET NOCOUNT ON; END");
        }

        @Test
        void oracle() {
            policyRejects(ORACLE, ORACLE_TRIGGER + "DBMS_SESSION.SET_ROLE('admin'); END;", DESTRUCTIVE);
            policyRejects(ORACLE, ORACLE_TRIGGER + "EXECUTE IMMEDIATE 'SET ROLE admin'; END;", DESTRUCTIVE);
            policyRejects(ORACLE, ORACLE_TRIGGER + "LOCK TABLE items IN EXCLUSIVE MODE; END;", BODY_STATEMENT);
            policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.qty := 1; ALTER TRIGGER other_trg DISABLE; END;",
                    BODY_DDL);

            accepted(ORACLE, ORACLE_TRIGGER + ":NEW.note := 'SET ROLE admin'; END;");
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS n NUMBER; BEGIN "
                    + "SELECT COUNT(*) INTO n FROM items; RETURN n; END;");
            accepted(ORACLE, ORACLE_TRIGGER + "-- DBMS_SESSION.SET_ROLE('admin')\n :NEW.qty := 1; END;");
        }
    }

    /** {@code TOP (…)} and aliased write targets resolve to the table they write. */
    @Nested
    class WriteTargetsThroughTopAndAliases {

        @Test
        void postgres() {
            scopeRejects(PG, "WITH c AS (SELECT * FROM pg_catalog.pg_class) UPDATE c SET relname = 'x'", CATALOG);
            scopeRejects(PG, "UPDATE ONLY pg_catalog.pg_class SET relname = 'x'", CATALOG);

            accepted(PG, "UPDATE items AS i SET note = 'x' FROM pg_catalog.pg_class AS c WHERE relname = note");
            scopeAccepts(PG, "WITH c AS (SELECT oid FROM pg_catalog.pg_class) "
                    + "UPDATE items SET note = 'x' WHERE id IN (SELECT oid FROM c)");
            accepted(PG, "CREATE FUNCTION f() RETURNS void LANGUAGE sql BEGIN ATOMIC UPDATE items SET note = 'x'; "
                    + "SELECT relname FROM pg_catalog.pg_class items; END");
        }

        @Test
        void mysql() {
            scopeRejects(MYSQL, "UPDATE items JOIN information_schema.tables ON table_name = note SET note = 'y'",
                    CATALOG);
            scopeRejects(MYSQL, "UPDATE items AS i, information_schema.tables AS t SET note = 'x'", CATALOG);
            scopeRejects(MYSQL, "WITH t AS (SELECT * FROM information_schema.tables) UPDATE t SET table_name = 'x'",
                    CATALOG);

            accepted(MYSQL, "UPDATE items SET note = (SELECT MAX(table_name) FROM information_schema.tables)");
            accepted(MYSQL, "UPDATE LOW_PRIORITY IGNORE items SET note = 'x'");
            accepted(MYSQL, MYSQL_TRIGGER + "UPDATE item_log a SET note = 'x'; "
                    + "SELECT COUNT(*) INTO @n FROM information_schema.tables a; END");
        }

        @Test
        void mariadb() {
            scopeRejects(MARIADB, "UPDATE items i JOIN information_schema.tables t ON table_name = note SET note = 'y'",
                    CATALOG);
            accepted(MARIADB, "UPDATE items i SET note = 'x' WHERE note IN (SELECT table_name FROM "
                    + "information_schema.tables t)");
        }

        @Test
        void sqlServer() {
            scopeRejects(MSSQL, "UPDATE TOP (@n) sys.objects SET name = 'x'", CATALOG);
            scopeRejects(MSSQL, "UPDATE TOP(5) sys.objects SET name = 'x'", CATALOG);
            scopeRejects(MSSQL, "UPDATE TOP (5) otherdb.dbo.items SET note = 'x'", THREE_PART);
            scopeRejects(MSSQL, "UPDATE c SET name = 'x' FROM sys.objects c", CATALOG);
            scopeRejects(MSSQL, "UPDATE c SET name = 'x' FROM sys.objects AS c", CATALOG);
            scopeRejects(MSSQL, "UPDATE TOP (1) c SET name = 'x' FROM dbo.items i JOIN sys.objects c ON 1 = 1",
                    CATALOG);
            scopeRejects(MSSQL, "DELETE a FROM sys.objects a", CATALOG);
            scopeRejects(MSSQL, "MERGE INTO c USING dbo.items s ON (1 = 0) WHEN MATCHED THEN UPDATE SET name = 'x' "
                    + "FROM sys.objects c", CATALOG);

            accepted(MSSQL, "UPDATE TOP (@n) items SET note = 'x'");
            accepted(MSSQL, "UPDATE TOP (10) PERCENT dbo.items SET note = 'x'");
            accepted(MSSQL, "UPDATE c SET note = 'x' FROM dbo.items c WHERE EXISTS "
                    + "(SELECT 1 FROM sys.objects WHERE name = 'items')");
            accepted(MSSQL, MSSQL_TRIGGER + "UPDATE c SET note = 'x' FROM item_log c; "
                    + "SELECT name FROM sys.objects c; END");
        }

        @Test
        void oracle() {
            rejected(ORACLE, "UPDATE (SELECT note FROM OTHER.items) v SET note = 'x'");
            rejected(ORACLE, "UPDATE OTHER.items i SET note = 'x'");
            accepted(ORACLE, "UPDATE APP.items i SET note = 'x'");
            accepted(ORACLE, "UPDATE items i SET note = 'x' WHERE EXISTS (SELECT 1 FROM APP.item_log a)");
        }
    }

    /** Three-part names are cross-database only where an object belongs, not as schema.table.column. */
    @Nested
    class ThreePartNames {

        @Test
        void postgres() {
            accepted(PG, "UPDATE public.items SET note = 'x' WHERE public.items.id = 1");
            scopeRejects(PG, "UPDATE public.items SET note = (SELECT s FROM otherdb.public.accounts LIMIT 1)",
                    THREE_PART);
            scopeRejects(PG, "UPDATE items SET note = otherdb.public.f(1)", THREE_PART);
            scopeRejects(PG, "UPDATE items SET note = 'x' WHERE other.items.id = 1", "other");
        }

        @Test
        void mysql() {
            accepted(MYSQL, "UPDATE appdb.items SET note = 'x' WHERE appdb.items.id = 1");
            scopeRejects(MYSQL, "UPDATE appdb.items SET note = 'x' WHERE otherdb.items.id = 1", "otherdb");
        }

        @Test
        void mariadb() {
            accepted(MARIADB, "UPDATE appdb.items SET note = appdb.items.note WHERE appdb.items.id = 1");
            scopeRejects(MARIADB, "UPDATE items SET note = (SELECT otherdb.t.note FROM otherdb.t LIMIT 1)", "otherdb");
        }

        @Test
        void sqlServer() {
            accepted(MSSQL, "UPDATE dbo.items SET note = 'x' WHERE dbo.items.id = 1");
            accepted(MSSQL, "UPDATE dbo.items SET note = 'x' FROM dbo.items JOIN dbo.item_log "
                    + "ON dbo.item_log.id = dbo.items.id");
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = (SELECT TOP 1 name FROM otherdb.dbo.accounts)",
                    THREE_PART);
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = 'x' FROM dbo.items JOIN dbo.item_log "
                    + "ON dbo.item_log.id = dbo.items.id, otherdb.dbo.accounts", THREE_PART);
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = 'x' FROM dbo.items JOIN otherdb.dbo.accounts ON 1 = 1",
                    THREE_PART);
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = otherdb.dbo.f(1)", THREE_PART);
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = srv.otherdb.dbo.t.note", THREE_PART);
            scopeRejects(MSSQL, "UPDATE dbo.items SET note = 'x' WHERE other.items.id = 1", "other");
        }

        @Test
        void oracle() {
            accepted(ORACLE, "UPDATE APP.items SET note = 'x' WHERE APP.items.id = 1");
            scopeRejects(ORACLE, "UPDATE APP.items SET note = 'x' WHERE OTHER.items.id = 1", "OTHER");
        }
    }

    /** The verification side-effect check reads tokens, not text inside literals or identifiers. */
    @Nested
    class VerificationSideEffects {

        @Test
        void postgres() {
            verificationAccepted(PG, "SELECT 1 AS \"into\" FROM items");
            verificationAccepted(PG, "SELECT count(*) = 0 FROM items WHERE note = 'FOR UPDATE'");
            verificationAccepted(PG, "SELECT note FROM items WHERE note <> 'nextval(''s'')' -- FOR UPDATE");
            verificationAccepted(PG, "SELECT \"nextval_note\" FROM items");
            verificationRejected(PG, "SELECT 1 INTO copy");
            verificationRejected(PG, "SELECT * FROM items FOR UPDATE");
            verificationRejected(PG, "SELECT * FROM items FOR NO KEY UPDATE");
            verificationRejected(PG, "SELECT \"nextval\"('s')");
            verificationRejected(PG, "SELECT \"pg_advisory_lock\"(1)");
        }

        @Test
        void mysql() {
            verificationAccepted(MYSQL, "SELECT `into`, `lock` FROM items");
            verificationAccepted(MYSQL, "SELECT COUNT(*) FROM items WHERE note = 'get_lock(1)'");
            verificationRejected(MYSQL, "SELECT GET_LOCK('x', 1)");
            verificationRejected(MYSQL, "SELECT `get_lock`('x', 1)");
            verificationRejected(MYSQL, "SELECT * FROM items LOCK IN SHARE MODE");
            verificationRejected(MYSQL, "SELECT 1 INTO @x");
        }

        @Test
        void mariadb() {
            verificationAccepted(MARIADB, "SELECT note AS nextval_note FROM items");
            verificationAccepted(MARIADB, "SELECT 'NEXT VALUE FOR s' AS note");
            verificationRejected(MARIADB, "SELECT NEXT VALUE FOR s");
            verificationRejected(MARIADB, "SELECT NEXTVAL(s)");
        }

        @Test
        void sqlServer() {
            verificationAccepted(MSSQL, "SELECT [updlock], [into] FROM items");
            verificationAccepted(MSSQL, "SELECT N'WITH (UPDLOCK)' AS note");
            verificationRejected(MSSQL, "SELECT * FROM items WITH (UPDLOCK)");
            verificationRejected(MSSQL, "SELECT NEXT VALUE FOR dbo.s");
            verificationRejected(MSSQL, "SELECT * INTO #copy FROM items");
        }

        @Test
        void oracle() {
            verificationAccepted(ORACLE, "SELECT \"INTO\" FROM items");
            verificationAccepted(ORACLE, "SELECT 's.NEXTVAL' FROM dual");
            verificationAccepted(ORACLE, "SELECT \"nextval\" FROM items");
            verificationRejected(ORACLE, "SELECT s.NEXTVAL FROM dual");
            verificationRejected(ORACLE, "SELECT s.\"NEXTVAL\" FROM dual");
            verificationRejected(ORACLE, "SELECT * FROM items FOR UPDATE");
        }
    }

    /** Multi-statement routine bodies are one statement, and their inner statements are still scanned. */
    @Nested
    class MultiStatementBodies {

        @Test
        void postgres() {
            String atomic = "CREATE FUNCTION bump(x integer) RETURNS integer LANGUAGE sql BEGIN ATOMIC "
                    + "UPDATE items SET qty = x; SELECT CASE WHEN x > 0 THEN 1 ELSE 0 END; END";
            accepted(PG, atomic);
            policyRejects(PG, atomic + "; UPDATE items SET qty = 0", "exactly one statement");
            scopeRejects(PG, "CREATE FUNCTION f() RETURNS void LANGUAGE sql BEGIN ATOMIC UPDATE items SET qty = 1; "
                    + "UPDATE other.items SET qty = 2; END", "other");
            accepted(PG, PG_FUNCTION + "UPDATE items SET qty = 1; UPDATE items SET note = 'a;b';" + PG_FUNCTION_END);
            scopeRejects(PG, PG_FUNCTION + "UPDATE items SET qty = 1; UPDATE other.items SET qty = 2;"
                    + PG_FUNCTION_END, "other");
        }

        @Test
        void mysql() {
            String trigger = MYSQL_TRIGGER + "IF NEW.note IS NULL THEN SET NEW.note = 'x'; END IF; "
                    + "SET NEW.qty = 1; END";
            accepted(MYSQL, trigger);
            policyRejects(MYSQL, trigger + "; UPDATE items SET qty = 0", "exactly one statement");
            scopeRejects(MYSQL, MYSQL_TRIGGER + "SET NEW.qty = 1; "
                    + "SET NEW.note = (SELECT note FROM otherdb.t LIMIT 1); END", "otherdb");
            rejected(MYSQL, MYSQL_TRIGGER + "SET NEW.qty = 1;");
        }

        @Test
        void mariadb() {
            String trigger = MYSQL_TRIGGER + "WHILE NEW.qty > 10 DO SET NEW.qty = NEW.qty - 1; END WHILE; END";
            accepted(MARIADB, trigger);
            scopeRejects(MARIADB, MYSQL_TRIGGER + "SET NEW.qty = 1; UPDATE otherdb.t SET qty = 2; END", "otherdb");
            rejected(MARIADB, MYSQL_TRIGGER + "SET NEW.qty = 1; END; END");
        }

        @Test
        void sqlServer() {
            String trigger = MSSQL_TRIGGER + "SET NOCOUNT ON; "
                    + "UPDATE items SET note = 'x' WHERE id IN (SELECT id FROM inserted); END";
            accepted(MSSQL, trigger);
            scopeRejects(MSSQL, MSSQL_TRIGGER + "SET NOCOUNT ON; UPDATE other.items SET note = 'x'; END", "other");
            policyRejects(MSSQL, MSSQL_TRIGGER + "SET NOCOUNT ON; END; GRANT SELECT ON items TO app", BODY_DDL);
        }

        @Test
        void oracle() {
            String trigger = ORACLE_TRIGGER + "IF :NEW.note IS NULL THEN :NEW.note := 'x'; END IF; "
                    + ":NEW.qty := 1; END;";
            accepted(ORACLE, trigger);
            scopeRejects(ORACLE, ORACLE_TRIGGER + ":NEW.qty := 1; UPDATE OTHER.items SET qty = 2; END;", "OTHER");
            rejected(ORACLE, ORACLE_TRIGGER + ":NEW.qty := 1;");
            rejected(ORACLE, ORACLE_TRIGGER + ":NEW.qty := 1; END; END;");
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS BEGIN "
                    + "FOR i IN 1..3 LOOP NULL; END LOOP; RETURN 1; END;");
        }

        @Test
        void changeSetListsValidateEachStatementOnItsOwn() {
            SchemaDefinition.ChangeSet mixed = new SchemaDefinition.ChangeSet("mixed", "trigger and backfill",
                    List.of(MSSQL_TRIGGER + "UPDATE c SET note = 'x' FROM item_log c; END",
                            "UPDATE items SET note = 'y' FROM dbo.items c"));
            assertThat(new ChangeSetExecutor().validate(List.of(mixed), options("dbo"), MSSQL)).hasSize(1);

            SchemaDefinition.ChangeSet leaking = new SchemaDefinition.ChangeSet("leak", "alias in second statement",
                    List.of("UPDATE items SET note = 'x'", "UPDATE c SET name = 'x' FROM sys.objects c"));
            assertThatThrownBy(() -> new ChangeSetExecutor().validate(List.of(leaking), options("dbo"), MSSQL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CATALOG);

            SchemaDefinition.ChangeSet oracle = new SchemaDefinition.ChangeSet("plsql", "trigger then update",
                    List.of(ORACLE_TRIGGER + ":NEW.qty := 1; :NEW.note := 'a;b'; END;", "UPDATE items SET qty = 1"));
            assertThat(new ChangeSetExecutor().validate(List.of(oracle), options("APP"), ORACLE)).hasSize(1);
        }

        private SchemaSynchronizerOptions options(String schema) {
            return new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L, false, true,
                    true);
        }
    }

    /** Escape forms neither hide forbidden text nor create false positives. */
    @Nested
    class EscapeForms {

        @Test
        void postgres() {
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void AS E'BEGIN \\x44ROP TABLE items; END' "
                    + "LANGUAGE plpgsql", DESTRUCTIVE);
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void AS U&'BEGIN \\0044ROP TABLE items; END' "
                    + "LANGUAGE plpgsql", DESTRUCTIVE);
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void AS U&'BEGIN !0044ROP TABLE items; END' UESCAPE '!' "
                    + "LANGUAGE plpgsql", DESTRUCTIVE);
            policyRejects(PG, "CREATE FUNCTION f() RETURNS void AS E'BEGIN SET \\162OLE admin; END' "
                    + "LANGUAGE plpgsql", BODY_STATEMENT);
            rejected(PG, "UPDATE items SET note = 'a\\' , qty = (SELECT 1 FROM other.t) --'");
            rejected(PG, "UPDATE items SET note = 'a\\'");

            accepted(PG, "UPDATE items SET note = E'\\x44ROP TABLE items'");
            accepted(PG, "UPDATE items SET note = U&'\\0044ROP TABLE items'");
            accepted(PG, "UPDATE items SET note = 'a\\\\'");
            accepted(PG, "UPDATE items SET U&\"n\\006Fte\" = 'x'");
        }

        @Test
        void mysql() {
            rejected(MYSQL, "UPDATE items SET note = 'C:\\'");
            rejected(MYSQL, "UPDATE items SET note = 'a\\' , qty = (SELECT 1 FROM otherdb.t) -- '");
            accepted(MYSQL, "UPDATE items SET note = 'C:\\\\'");
            accepted(MYSQL, "UPDATE items SET note = 'DROP TABLE items; SET ROLE admin'");
            accepted(MYSQL, "UPDATE items SET note = 'it''s'");
            accepted(MYSQL, MYSQL_TRIGGER + "SET NEW.note = 'DROP TABLE items'; END");
        }

        @Test
        void mariadb() {
            rejected(MARIADB, "UPDATE items SET note = 'a\\'b'");
            accepted(MARIADB, "UPDATE items SET note = 'a\\\\b', qty = 1");
            accepted(MARIADB, "UPDATE items SET note = 'EXEC xp_cmdshell'");
        }

        @Test
        void sqlServer() {
            accepted(MSSQL, "UPDATE items SET note = N'DROP TABLE items; EXEC xp_cmdshell ''dir'''");
            accepted(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET note = N'DENY; DROP TABLE x'; END");
            rejected(MSSQL, "UPDATE items SET note = N'x'; DROP TABLE items");
            rejected(MSSQL, "UPDATE items SET note = N'x''; DROP TABLE items");
        }

        @Test
        void oracle() {
            accepted(ORACLE, "UPDATE items SET note = q'{DROP TABLE items}'");
            accepted(ORACLE, ORACLE_TRIGGER + ":NEW.note := q'[EXECUTE IMMEDIATE 'DROP TABLE x']'; END;");
            rejected(ORACLE, "UPDATE items SET note = q'!it's!' || (SELECT s FROM OTHER.t)");
            rejected(ORACLE, "UPDATE items SET note = q'[open");
        }
    }

    /** SQL Server runs every statement of a batch, with or without {@code ;} between them. */
    @Nested
    class SqlServerBatchesWithoutSemicolons {

        private static final String ONE_STATEMENT = "must contain exactly one statement";
        private static final String SIGNATURE = "must not sign modules or change sensitivity classifications";

        @Test
        void secondStatementIsRejected() {
            policyRejects(MSSQL, "INSERT INTO items (qty) VALUES (1) ALTER ROLE db_owner ADD MEMBER bob", ONE_STATEMENT);
            policyRejects(MSSQL, "UPDATE items SET qty = 1 GRANT CONTROL TO bob", ONE_STATEMENT);
            policyRejects(MSSQL, "ALTER TABLE items ADD c int ALTER ROLE db_owner ADD MEMBER bob", ONE_STATEMENT);
            policyRejects(MSSQL, "CREATE TABLE dbo.x (id int) CREATE LOGIN evil WITH PASSWORD = 'P@ssw0rd!'",
                    ONE_STATEMENT);
            policyRejects(MSSQL, "INSERT INTO items (qty) VALUES (1) REVOKE SELECT ON dbo.items TO public",
                    ONE_STATEMENT);
            policyRejects(MSSQL, "UPDATE items SET qty = 1 SELECT * INTO dbo.copy FROM items", ONE_STATEMENT);
            policyRejects(MSSQL, "GRANT SELECT ON dbo.items TO app GRANT CONTROL TO app", ONE_STATEMENT);
            policyRejects(MSSQL, "ALTER TABLE items ADD c int DISABLE TRIGGER ALL ON DATABASE", ONE_STATEMENT);
            policyRejects(MSSQL, "UPDATE items SET qty = 1GRANT CONTROL TO bob", ONE_STATEMENT);
            policyRejects(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET qty = 1DENY SELECT ON dbo.items TO public; END",
                    BODY_STATEMENT);
            for (String number : List.of("1E", "1.E", "1.5E", "1E+", "1e-", ".5E", "0x", "0x1F", "$1", "£1")) {
                policyRejects(MSSQL, "UPDATE items SET qty = " + number + "GRANT CONTROL TO bob", ONE_STATEMENT);
                verificationRejected(MSSQL, "SELECT " + number + "GRANT CONTROL TO bob");
            }
            policyRejects(MSSQL, "UPDATE items SET qty = 1 LINENO 5", ONE_STATEMENT);
        }

        @Test
        void moduleSignaturesAreRejected() {
            policyRejects(MSSQL, "UPDATE items SET qty = 1 ADD SIGNATURE TO dbo.p BY CERTIFICATE c", ONE_STATEMENT);
            policyRejects(MSSQL, "INSERT INTO items (qty) VALUES (1) ADD SIGNATURE TO dbo.p BY CERTIFICATE c",
                    ONE_STATEMENT);
            policyRejects(MSSQL, "ALTER TABLE items ADD c int ADD COUNTERSIGNATURE TO dbo.p BY CERTIFICATE c",
                    ONE_STATEMENT);
            policyRejects(MSSQL, "CREATE TRIGGER trg ON items AFTER INSERT AS ADD SIGNATURE TO dbo.p "
                    + "BY CERTIFICATE c", SIGNATURE);
            policyRejects(MSSQL, MSSQL_TRIGGER + "UPDATE item_log SET qty = 1; ADD SIGNATURE TO dbo.p "
                    + "BY CERTIFICATE c WITH PASSWORD = 'x'; END", SIGNATURE);
            policyRejects(MSSQL, MSSQL_TRIGGER + "ADD SENSITIVITY CLASSIFICATION TO dbo.items.qty "
                    + "WITH (LABEL = 'x'); END", SIGNATURE);
            policyRejects(MSSQL, "ADD SIGNATURE TO dbo.p BY CERTIFICATE c", SIGNATURE);
            verificationRejected(MSSQL, "SELECT 1 AS x ADD SIGNATURE TO dbo.p BY CERTIFICATE c");
        }

        @Test
        void signatureLookAlikesAreAccepted() {
            accepted(MSSQL, "ALTER TABLE items ADD signature varbinary(100)");
            accepted(MSSQL, "ALTER TABLE items ADD countersignature varbinary(100)");
            accepted(MSSQL, "ALTER TABLE items ADD sensitivity int");
            accepted(MSSQL, "ALTER TABLE items WITH CHECK ADD CONSTRAINT ck_qty CHECK (qty > 0)");
            accepted(MSSQL, "UPDATE items SET note = N'ADD SIGNATURE TO dbo.p'");
            verificationAccepted(MSSQL, "SELECT signature, sensitivity FROM dbo.items");
        }

        @Test
        void secondStatementInVerificationIsRejected() {
            verificationRejected(MSSQL, "SELECT 1 GRANT CONTROL TO bob");
            verificationRejected(MSSQL, "SELECT 1 ALTER ROLE db_owner ADD MEMBER bob");
            verificationRejected(MSSQL, "SELECT 1 SHUTDOWN");
            verificationRejected(MSSQL, "WITH c AS (SELECT 1 AS a) SELECT a FROM c GRANT CONTROL TO bob");
            verificationRejected(MSSQL, "SELECT CASE WHEN 1 = 1 THEN 1 END KILL 52");
        }

        @Test
        void singleStatementsWithStatementWordsInsideAreAccepted() {
            accepted(MSSQL, "ALTER TABLE items ADD c int");
            accepted(MSSQL, "ALTER TABLE items ADD CONSTRAINT fk FOREIGN KEY (qty) REFERENCES dbo.other_items (id) "
                    + "ON DELETE SET NULL ON UPDATE CASCADE");
            accepted(MSSQL, "INSERT INTO items (qty) SELECT qty FROM dbo.other_items UNION ALL SELECT 2");
            accepted(MSSQL, "CREATE TABLE dbo.x (id int, note nvarchar(10))");
            assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                    "UPDATE items SET qty = 1 OUTPUT inserted.qty WHERE qty IS NULL", MSSQL))
                    .doesNotThrowAnyException();
            accepted(MSSQL, "UPDATE items SET note = CASE WHEN qty > 0 THEN N'a' ELSE N'b' END");
            accepted(MSSQL, "GRANT SELECT, INSERT, UPDATE ON dbo.items TO app WITH GRANT OPTION");
            accepted(MSSQL, "CREATE INDEX ix ON dbo.items (qty) WITH (ONLINE = ON)");
            scopeAccepts(MSSQL, "WITH c AS (SELECT id FROM dbo.items) UPDATE items SET qty = 1 "
                    + "WHERE id IN (SELECT id FROM c)");

            verificationAccepted(MSSQL, "SELECT TOP 1 WITH TIES qty FROM dbo.items WITH (NOLOCK) ORDER BY qty");
            verificationAccepted(MSSQL, "SELECT qty FROM dbo.items ORDER BY qty OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY");
            verificationAccepted(MSSQL, "WITH c AS (SELECT qty FROM dbo.items) SELECT qty FROM c");
            verificationAccepted(MSSQL, "SELECT qty FROM dbo.items GROUP BY qty WITH ROLLUP");
            verificationAccepted(MSSQL, "SELECT 1 UNION SELECT 2 EXCEPT SELECT 3");
            verificationAccepted(MSSQL, "SELECT [grant], [select] FROM dbo.items");
        }
    }

    /** SQL Server bulk loads read server-side files. */
    @Nested
    class BulkLoads {

        @Test
        void bulkLoadsAreRejected() {
            policyRejects(MSSQL, "BULK INSERT dbo.items FROM 'C:\\data\\items.csv'", DESTRUCTIVE);
            policyRejects(MSSQL, MSSQL_TRIGGER + "BULK INSERT dbo.item_log FROM 'C:\\x.csv'; END", DESTRUCTIVE);
            policyRejects(MSSQL, "INSERT INTO items (note) SELECT BulkColumn "
                    + "FROM OPENROWSET(BULK 'C:\\x.txt', SINGLE_CLOB) AS b", DESTRUCTIVE);
            policyRejects(MSSQL, "INSERT INTO items (note) SELECT note "
                    + "FROM OPENDATASOURCE('MSOLEDBSQL', 'Server=x').db.dbo.t", DESTRUCTIVE);
            verificationRejected(MSSQL, "SELECT BulkColumn FROM OPENROWSET(BULK 'C:\\x.txt', SINGLE_CLOB) AS b");
        }

        @Test
        void bulkLookAlikesAreAccepted() {
            accepted(MSSQL, "UPDATE items SET note = N'BULK INSERT'");
            accepted(MSSQL, "UPDATE items SET [bulk] = 1");
            accepted(MYSQL, "UPDATE items SET bulk = 1");
        }
    }

    /** PostgreSQL routine languages, extensions, and server-file and WAL functions. */
    @Nested
    class PostgresLanguagesAndServerFunctions {

        private static final String LANGUAGE = "only LANGUAGE sql and plpgsql bodies can be checked";

        @Test
        void uncheckableLanguagesAreRejected() {
            for (String language : List.of("plperlu", "plpython3u", "plv8", "c", "internal", "'plperlu'",
                    "\"plpython3u\"", "PLV8")) {
                policyRejects(PG, "CREATE FUNCTION f() RETURNS int LANGUAGE " + language + " AS $$ return 1 $$",
                        LANGUAGE);
            }
            policyRejects(PG, "CREATE FUNCTION f() RETURNS text AS $$ return os.popen('id').read() $$ "
                    + "LANGUAGE plpython3u", LANGUAGE);
        }

        @Test
        void sqlAndPlpgsqlBodiesAreAccepted() {
            accepted(PG, "CREATE FUNCTION f() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
            accepted(PG, "CREATE FUNCTION f() RETURNS int LANGUAGE SQL AS $$ SELECT 1 $$");
            accepted(PG, "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql");
            accepted(PG, "CREATE FUNCTION f() RETURNS int RETURN 1");
            accepted(PG, "CREATE FUNCTION f() RETURNS text LANGUAGE sql AS $$ SELECT 'LANGUAGE plperlu' $$");
        }

        @Test
        void createExtensionIsRejected() {
            policyRejects(PG, "CREATE EXTENSION IF NOT EXISTS pgcrypto", "unsupported schema change SQL");
            policyRejects(PG, "CREATE EXTENSION file_fdw SCHEMA public", "unsupported schema change SQL");
            accepted(PG, "UPDATE items SET note = 'CREATE EXTENSION pgcrypto'");
        }

        @Test
        void serverFileLargeObjectAndWalFunctionsAreRejected() {
            for (String call : List.of("pg_ls_dir('.')", "pg_ls_waldir()", "pg_stat_file('pg_hba.conf')",
                    "pg_read_binary_file('x')", "lo_import('/etc/passwd')", "lo_export(1, '/tmp/x')",
                    "lo_put(1, 0, '\\x00')", "lo_from_bytea(0, '\\x00')", "pg_file_write('x', 'y', false)",
                    "pg_switch_wal()", "pg_create_restore_point('x')", "pg_logical_emit_message(true, 'p', 'm')",
                    "pg_backup_start('x')", "\"pg_ls_dir\"('.')", "pg_catalog.pg_stat_file('x')")) {
                policyRejects(PG, "SELECT " + call, DESTRUCTIVE);
                policyRejects(PG, PG_FUNCTION + "PERFORM " + call + ";" + PG_FUNCTION_END, DESTRUCTIVE);
            }
        }

        @Test
        void serverFunctionLookAlikesAreAccepted() {
            accepted(PG, "UPDATE items SET note = 'pg_ls_dir(''.'')'");
            accepted(PG, "UPDATE items SET lo_note = 1");
            accepted(PG, "UPDATE items SET \"PG_LS_DIR\" = 1");
        }
    }

    /** Settings that switch roles or change the server for every session. */
    @Nested
    class RoleAndGlobalSettings {

        private static final String GLOBAL = "must not change GLOBAL or PERSIST server variables";
        private static final String SETTINGS = "must not change logging, auditing, role, or library settings";

        @Test
        void postgresRoutineSetClauseCannotSwitchRole() {
            for (String clause : List.of("SET role = postgres", "SET ROLE TO postgres", "SET \"role\" = postgres",
                    "SET \"ROLE\" = postgres", "SET session_authorization = postgres",
                    "SET session_replication_role = replica")) {
                policyRejects(PG, "CREATE FUNCTION app_f() RETURNS int LANGUAGE sql " + clause + " AS 'SELECT 1'",
                        BODY_STATEMENT);
            }
            policyRejects(PG, "CREATE FUNCTION f() RETURNS int SECURITY DEFINER SET search_path = public "
                    + "SET role = postgres AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql", BODY_STATEMENT);
        }

        @Test
        void postgresLoggingAuditAndLibrarySettingsAreRejected() {
            for (String parameter : List.of("log_statement", "LOG_STATEMENT", "\"log_statement\"",
                    "log_min_duration_statement", "log_min_messages", "log_connections", "row_security",
                    "session_preload_libraries", "local_preload_libraries", "pgaudit.log", "pgaudit.log_level")) {
                policyRejects(PG, "CREATE FUNCTION app_f() RETURNS int LANGUAGE sql SET " + parameter
                        + " = 'none' AS 'SELECT 1'", BODY_STATEMENT);
                policyRejects(PG, PG_FUNCTION + "SET " + parameter + " = 'none'; RETURN;" + PG_FUNCTION_END,
                        BODY_STATEMENT);
                policyRejects(PG, PG_FUNCTION + "SET LOCAL " + parameter + " TO 'none'; RETURN;" + PG_FUNCTION_END,
                        BODY_STATEMENT);
                policyRejects(PG, "SET " + parameter + " = 'none'", "unsupported schema change SQL");
            }
            policyRejects(PG, "SELECT set_config('log_statement', 'none', false)", SETTINGS);
            policyRejects(PG, PG_FUNCTION + "PERFORM set_config('pgaudit.log', 'none', true);" + PG_FUNCTION_END,
                    SETTINGS);
            policyRejects(PG, PG_FUNCTION + "PERFORM set_config(p, 'none', true);" + PG_FUNCTION_END, SETTINGS);
            verificationRejected(PG, "SELECT set_config('log_statement', 'none', false)");
        }

        @Test
        void postgresOrdinarySettingsAndLookAlikesAreAccepted() {
            accepted(PG, PG_FUNCTION + "SET LOCAL work_mem = '64MB'; RETURN;" + PG_FUNCTION_END);
            assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                    PG_FUNCTION + "PERFORM set_config('work_mem', '64MB', true);" + PG_FUNCTION_END, PG))
                    .doesNotThrowAnyException();
            verificationAccepted(PG, "SELECT current_setting('log_statement')");
            for (String verification : List.of("SELECT set_config('search_path', 'other', false)",
                    "SELECT set_config('work_mem', '64MB', false)",
                    "SELECT pg_catalog.\"set_config\"('work_mem', '64MB', false)")) {
                assertThatThrownBy(() -> ChangeSetSchemaScope.requireNoSessionNamespaceChange(verification, PG))
                        .as(verification).hasMessageContaining("session namespace");
            }
            scopeRejects(PG, PG_FUNCTION + "PERFORM set_config('work_mem', '64MB', true);" + PG_FUNCTION_END,
                    "session namespace");
            scopeRejects(PG, "SELECT set_config('work_mem', '64MB', false)", "session namespace");
            accepted(PG, "CREATE FUNCTION f(r text) RETURNS void LANGUAGE sql AS 'UPDATE items SET log_note = r'");
            accepted(PG, "UPDATE items SET log_statement = 'none'");
            accepted(PG, "UPDATE items SET note = 'SET log_statement = none'");
        }

        @Test
        void postgresRoutineSetClauseForOtherParametersIsAccepted() {
            accepted(PG, "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET work_mem = '64MB' AS 'SELECT 1'");
            accepted(PG, "CREATE FUNCTION f() RETURNS int SECURITY DEFINER SET statement_timeout TO 0 "
                    + "SET \"work_mem\" = '64MB' AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql");
            accepted(PG, "CREATE FUNCTION f() RETURNS SETOF items LANGUAGE sql AS 'SELECT * FROM items'");
            accepted(PG, "CREATE FUNCTION f(r text) RETURNS void LANGUAGE sql AS 'UPDATE items SET role = r'");
        }

        @Test
        void mysqlGlobalAndPersistedSettingsAreRejected() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                policyRejects(dialect, "SET GLOBAL max_connections = 1000", GLOBAL);
                policyRejects(dialect, "SET @@global.max_connections = 1000", GLOBAL);
                policyRejects(dialect, MYSQL_TRIGGER + "SET GLOBAL event_scheduler = ON; END", GLOBAL);
                policyRejects(dialect, MYSQL_TRIGGER + "SET @@GLOBAL.sql_mode := ''; END", GLOBAL);
                policyRejects(dialect, MYSQL_TRIGGER + "SET @a = 1, GLOBAL wait_timeout = 5; END", GLOBAL);
                policyRejects(dialect, MYSQL_TRIGGER + "SET GLOBAL TRANSACTION ISOLATION LEVEL SERIALIZABLE; END",
                        GLOBAL);
            }
            policyRejects(MYSQL, "SET PERSIST max_connections = 1000", GLOBAL);
            policyRejects(MYSQL, "SET PERSIST_ONLY max_connections = 1000", GLOBAL);
            policyRejects(MYSQL, MYSQL_TRIGGER + "SET @@persist.max_connections = 10; END", GLOBAL);
        }

        @Test
        void mysqlSessionSettingsAndGlobalLookAlikesAreAccepted() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                accepted(dialect, "UPDATE items SET global = 1");
                accepted(dialect, "UPDATE items SET qty = 1, persist = 2");
                accepted(dialect, MYSQL_TRIGGER + "SET NEW.qty = @@global.max_connections; END");
                accepted(dialect, MYSQL_TRIGGER + "SET @limit = @@global.max_connections, NEW.qty = 1; END");
                accepted(dialect, MYSQL_TRIGGER + "SET NEW.note = 'SET GLOBAL x = 1'; END");
                verificationAccepted(dialect, "SELECT @@global.max_connections, @@global.wait_timeout");
            }
        }
    }

    /** Additional verification side effects: WAL, notifications, transaction ids, page locks, and Oracle I/O. */
    @Nested
    class MoreVerificationSideEffects {

        @Test
        void postgres() {
            for (String call : List.of("pg_switch_wal()", "pg_create_restore_point('x')",
                    "pg_logical_emit_message(true, 'p', 'm')", "pg_notify('c', 'm')", "txid_current()",
                    "pg_current_xact_id()", "\"pg_notify\"('c', 'm')")) {
                verificationRejected(PG, "SELECT " + call);
            }
            verificationAccepted(PG, "SELECT pg_current_xact_id_if_assigned()");
            verificationAccepted(PG, "SELECT txid_current_snapshot()");
            verificationAccepted(PG, "SELECT 'pg_notify(1)', txid_current FROM items");
        }

        @Test
        void sqlServer() {
            verificationRejected(MSSQL, "SELECT qty FROM dbo.items WITH (PAGLOCK)");
            verificationAccepted(MSSQL, "SELECT [paglock] FROM dbo.items");
        }

        @Test
        void oracle() {
            for (String call : List.of("UTL_HTTP.REQUEST('http://x')", "UTL_TCP.GET_LINE(c)",
                    "UTL_SMTP.HELO(c, 'x')", "UTL_FILE.FOPEN('D', 'f', 'r')",
                    "DBMS_XMLGEN.GETXML('SELECT 1 FROM dual')", "DBMS_PIPE.RECEIVE_MESSAGE('p')",
                    "DBMS_LOCK.REQUEST(1)", "HTTPURITYPE('http://x').GETCLOB()", "\"UTL_HTTP\".REQUEST('http://x')",
                    "SYS.UTL_INADDR.GET_HOST_ADDRESS('x')")) {
                verificationRejected(ORACLE, "SELECT " + call + " FROM dual");
            }
            policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.note := UTL_HTTP.REQUEST('http://x'); END;", DESTRUCTIVE);
            verificationAccepted(ORACLE, "SELECT 'UTL_HTTP' FROM dual");
            verificationAccepted(ORACLE, "SELECT \"utl_http\" FROM items");
            verificationAccepted(PG, "SELECT utl_http FROM items");
        }

        @Test
        void oracleServerFiles() {
            for (String call : List.of("XMLTYPE(BFILENAME('D', 'f'), 1)", "BFILENAME('D', 'f')",
                    "SYS.\"BFILENAME\"('D', 'f')", "DBMS_LOB.FILEEXISTS(BFILENAME('D', 'f'))",
                    "DBMS_LOB.GETLENGTH(BFILENAME('D', 'f'))")) {
                verificationRejected(ORACLE, "SELECT " + call + " FROM dual");
                policyRejects(ORACLE, "UPDATE items SET note = " + call, DESTRUCTIVE);
            }
            for (String call : List.of("DBMS_LOB.FILEOPEN(f)", "DBMS_LOB.LOADFROMFILE(d, f, 10)",
                    "DBMS_LOB.LOADCLOBFROMFILE(d, f, 10, o1, o2, c, l, w)", "DBMS_LOB.LOADBLOBFROMFILE(d, f, 10, o1, o2)",
                    "SYS.DBMS_LOB.FILECLOSEALL()")) {
                policyRejects(ORACLE, ORACLE_TRIGGER + call + "; END;", DESTRUCTIVE);
            }
            verificationAccepted(ORACLE, "SELECT DBMS_LOB.GETLENGTH(note) FROM items");
            verificationAccepted(ORACLE, "SELECT 'BFILENAME' FROM dual");
            verificationAccepted(ORACLE, "SELECT \"bfilename\" FROM items");
        }
    }

    /** A CASE expression's END and THEN/ELSE are not block ends or statement starts. */
    @Nested
    class CaseExpressionsInsideBlocks {

        @Test
        void mysqlAndMariaDb() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                accepted(dialect, "CREATE FUNCTION f(a INT) RETURNS INT DETERMINISTIC "
                        + "RETURN CASE WHEN a > 0 THEN IF(a > 10, 2, 1) ELSE 0 END");
                accepted(dialect, "CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW "
                        + "SET NEW.note = CASE WHEN NEW.qty > 0 THEN REPEAT('x', 2) ELSE '' END");
                accepted(dialect, MYSQL_TRIGGER
                        + "SET NEW.qty = CASE WHEN NEW.qty > 0 THEN IF(NEW.qty > 1, 1, 2) ELSE 0 END; END");
                accepted(dialect, MYSQL_TRIGGER + "IF NEW.qty > 0 THEN SET NEW.note = 'a'; "
                        + "ELSE IF NEW.qty < 0 THEN SET NEW.note = 'b'; END IF; END IF; "
                        + "lbl: REPEAT SET NEW.qty = NEW.qty - 1; UNTIL NEW.qty <= 0 END REPEAT lbl; END");
                rejected(dialect, MYSQL_TRIGGER
                        + "IF NEW.qty > 0 THEN SET NEW.note = CASE WHEN 1 THEN 'a' ELSE 'b' END; END");
                rejected(dialect, MYSQL_TRIGGER
                        + "SET NEW.qty = CASE WHEN 1 THEN IF(1, 1, 2) ELSE 0 END; END; GRANT ALL ON *.* TO x");
            }
        }

        @Test
        void oracleForLoopBoundedByCase() {
            accepted(ORACLE, ORACLE_TRIGGER + "FOR i IN 1 .. CASE WHEN :NEW.qty > 0 THEN :NEW.qty ELSE 1 END "
                    + "LOOP :NEW.note := 'x'; END LOOP; END;");
            accepted(ORACLE, ORACLE_TRIGGER + "CASE WHEN :NEW.qty > 0 THEN :NEW.note := 'a'; "
                    + "ELSE :NEW.note := 'b'; END CASE; END;");
            rejected(ORACLE, ORACLE_TRIGGER + "FOR i IN 1 .. CASE WHEN :NEW.qty > 0 THEN :NEW.qty ELSE 1 END "
                    + "LOOP :NEW.note := 'x'; END;");
        }

        @Test
        void postgresAtomicBody() {
            accepted(PG, "CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql BEGIN ATOMIC "
                    + "SELECT CASE WHEN a > 0 THEN 1 ELSE 0 END; END");
        }
    }

    /** Long column lists stay linear in the scope and policy checks. */
    @Nested
    class LongLists {

        @Test
        void twentyThousandThreePartColumnsAreCheckedQuickly() {
            StringBuilder sql = new StringBuilder("SELECT dbo.items.c0");
            for (int column = 1; column < 20_000; column++) {
                sql.append(", dbo.items.c").append(column);
            }
            sql.append(" FROM dbo.items");
            long started = System.nanoTime();
            accepted(MSSQL, sql.toString());
            verificationAccepted(MSSQL, sql.toString());
            assertThat((System.nanoTime() - started) / 1_000_000).as("milliseconds").isLessThan(2_000);
        }
    }

    /** PostgreSQL and SQL Server end a {@code --} comment at a bare carriage return; Oracle, MySQL, and MariaDB do not. */
    @Nested
    class CarriageReturnLineComments {

        @Test
        void codeAfterACarriageReturnIsChecked() {
            rejected(PG, "SELECT 1 --\r; CREATE TABLE cr_probe (x int)");
            rejected(PG, "UPDATE items SET qty = 1 --\r; GRANT ALL ON items TO bob");
            policyRejects(PG, "SELECT 1 --\r, pg_read_file('/etc/passwd')", DESTRUCTIVE);
            verificationRejected(PG, "SELECT 1 --\r, pg_read_file('/etc/passwd')");
            scopeRejects(PG, "UPDATE items SET qty = 1 --\r WHERE id IN (SELECT id FROM other.t)", "other");
            policyRejects(MSSQL, "SELECT 1 AS x --\r CREATE TABLE tempdb.dbo.cr_probe (x int)",
                    "must contain exactly one statement");
            verificationRejected(MSSQL, "SELECT 1 AS x --\r CREATE TABLE tempdb.dbo.cr_probe (x int)");
            policyRejects(MSSQL, "UPDATE items SET qty = 1 --\r GRANT CONTROL TO bob",
                    "must contain exactly one statement");
            scopeRejects(MSSQL, "UPDATE items SET qty = 1 --\r WHERE id IN (SELECT id FROM other.t)", "other");
        }

        @Test
        void oracleCommentsContinuePastACarriageReturnSoAQuoteCannotHideTheNextLine() {
            rejected(ORACLE, "CREATE OR REPLACE TRIGGER cr_trg BEFORE INSERT ON cr_t FOR EACH ROW BEGIN NULL; --\r'\n"
                    + "EXECUTE IMMEDIATE chr(67)||chr(82); --'\nEND;");
            scopeRejects(ORACLE, "UPDATE items SET qty = 1 --\r'\nWHERE 0 < (SELECT count(*) FROM OTHER.t) --'",
                    "OTHER");
            verificationRejected(ORACLE, "SELECT 1 AS a --\r'\n, UTL_HTTP.REQUEST('http://x') AS b --'\nFROM dual");
            accepted(ORACLE, "UPDATE items SET qty = 1 -- note\r, still comment\nWHERE qty IS NULL");
        }

        @Test
        void commentsEndingInLineBreaksAreAccepted() {
            for (DatabaseDialect dialect : List.of(PG, MSSQL, ORACLE, MYSQL, MARIADB)) {
                accepted(dialect, "UPDATE items SET qty = 1 -- note\r\nWHERE qty IS NULL");
                accepted(dialect, "UPDATE items SET qty = 1 -- note\nWHERE qty IS NULL");
            }
            accepted(PG, "UPDATE items SET qty = 1 -- note\rWHERE qty IS NULL");
        }

        @Test
        void mysqlCommentsContinuePastACarriageReturn() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                accepted(dialect, "UPDATE items SET qty = 1 -- note\r, GRANT ALL ON *.* TO bob");
                accepted(dialect, "UPDATE items SET qty = 1 # note\r, GRANT ALL ON *.* TO bob");
                rejected(dialect, "UPDATE items SET qty = 1 -- note\n; GRANT ALL ON *.* TO bob");
            }
        }
    }

    /** MySQL and MariaDB apply {@code /*+ … *}{@code /} hints, including {@code SET_VAR}; elsewhere they are comments or plan hints. */
    @Nested
    class OptimizerHints {

        @Test
        void mysqlHintsAreRejected() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                policyRejects(dialect, "UPDATE /*+ SET_VAR(foreign_key_checks=0) */ items SET qty = 1",
                        "optimizer hints");
                policyRejects(dialect, "INSERT /*+ SET_VAR(sql_mode='') */ INTO items (qty) VALUES (1)",
                        "optimizer hints");
                verificationRejected(dialect, "SELECT /*+ SET_VAR(sql_safe_updates=0) */ qty FROM items");
                accepted(dialect, "UPDATE /* + not a hint */ items SET qty = 1");
                accepted(dialect, "UPDATE items SET note = '/*+ SET_VAR(x=1) */'");
            }
        }

        @Test
        void hintsElsewhereAreAccepted() {
            accepted(ORACLE, "UPDATE /*+ INDEX(items ix_qty) */ items SET qty = 1");
            verificationAccepted(ORACLE, "SELECT /*+ FULL(items) */ qty FROM items");
            accepted(PG, "UPDATE /*+ SeqScan(items) */ items SET qty = 1");
            accepted(MSSQL, "UPDATE /*+ x */ items SET qty = 1");
        }
    }

    /** More PostgreSQL server functions, settings, and routine languages. */
    @Nested
    class PostgresStatisticsReplicationAndLanguages {

        @Test
        void statisticsReplicationAndCollationFunctionsAreRejected() {
            for (String call : List.of("pg_stat_reset()", "pg_stat_reset_shared('bgwriter')",
                    "pg_stat_reset_single_table_counters(1)", "pg_catalog.pg_stat_reset()",
                    "\"pg_stat_reset\"()", "pg_replication_origin_create('o')",
                    "pg_replication_origin_advance('o', '0/0')", "pg_import_system_collations('pg_catalog')")) {
                policyRejects(PG, "SELECT " + call, DESTRUCTIVE);
                verificationRejected(PG, "SELECT " + call);
                policyRejects(PG, PG_FUNCTION + "PERFORM " + call + ";" + PG_FUNCTION_END, DESTRUCTIVE);
            }
            verificationAccepted(PG, "SELECT stats_reset FROM pg_stat_database");
            accepted(PG, "UPDATE items SET note = 'pg_stat_reset()'");
        }

        @Test
        void dynamicLibraryPathIsRejected() {
            policyRejects(PG, "CREATE FUNCTION app_f() RETURNS int LANGUAGE sql SET dynamic_library_path = '/tmp' "
                    + "AS 'SELECT 1'", BODY_STATEMENT);
            policyRejects(PG, PG_FUNCTION + "SET dynamic_library_path = '/tmp'; RETURN;" + PG_FUNCTION_END,
                    BODY_STATEMENT);
        }

        @Test
        void aLiteralBodyNeedsALanguage() {
            policyRejects(PG, "CREATE FUNCTION f() RETURNS int AS 'SELECT 1'", "must declare LANGUAGE");
            policyRejects(PG, "CREATE FUNCTION f() RETURNS int AS $$ SELECT 1 $$", "must declare LANGUAGE");
            policyRejects(PG, "CREATE PROCEDURE p() AS $$ UPDATE items SET qty = 1 $$", "must declare LANGUAGE");
            accepted(PG, "CREATE FUNCTION f() RETURNS int RETURN 1");
            accepted(PG, "CREATE FUNCTION f() RETURNS int BEGIN ATOMIC SELECT 1; END");
            accepted(PG, "CREATE FUNCTION f() RETURNS int AS $$ SELECT 1 $$ LANGUAGE sql");
        }
    }
}
