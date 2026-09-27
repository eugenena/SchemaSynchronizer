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
        void postgresRoutineSearchPathMayNameOnlyTheConfiguredSchema() {
            String create = "CREATE FUNCTION f() RETURNS int SECURITY DEFINER ";
            String body = " AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql";
            for (String value : List.of("= public, pg_temp", "TO pg_catalog, public, pg_temp", "= public",
                    "= \"public\", \"pg_temp\"", "= 'public', 'pg_catalog'", "= ''", "TO PUBLIC, PG_TEMP",
                    "= pg_temp, public")) {
                accepted(PG, create + "SET search_path " + value + body);
            }
            accepted(PG, "CREATE OR REPLACE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql "
                    + "SET search_path = public, pg_temp");
            scopeAccepts(PG, "CREATE PROCEDURE p() LANGUAGE plpgsql SET search_path = public AS $$ BEGIN NULL; END $$");
            accepted(PG, "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET search_path = public SET work_mem = '64MB' "
                    + "AS 'SELECT 1'");
            for (String alter : List.of("ALTER FUNCTION f() SET search_path = public, pg_temp",
                    "ALTER PROCEDURE p() SET search_path TO public", "ALTER ROUTINE f(int) SET search_path = ''")) {
                scopeAccepts(PG, alter);
            }
            for (String value : List.of("= public, other", "= other", "= \"PUBLIC\"", "= 'public, pg_temp'",
                    "FROM CURRENT", "TO DEFAULT", "= \"$user\", public", "= pg_temp_3", "= public.other",
                    "= public, 'Other'", "= public, E'other'")) {
                scopeRejects(PG, create + "SET search_path " + value + body, "session namespace");
            }
            for (String sql : List.of(
                    "SET search_path = public",
                    "SET search_path TO public, pg_temp",
                    "SET LOCAL search_path = public",
                    "SET SESSION search_path = public",
                    "SET \"search_path\" = public",
                    "ALTER FUNCTION f() SET search_path FROM CURRENT",
                    "ALTER FUNCTION f() SET search_path = other",
                    "ALTER ROLE bob SET search_path = public",
                    "ALTER DATABASE d SET search_path = public",
                    "CREATE FUNCTION f() RETURNS int SET LOCAL search_path = public" + body,
                    "CREATE FUNCTION f() RETURNS int" + body + "; SET search_path = public",
                    PG_FUNCTION + "SET search_path = public; RETURN;" + PG_FUNCTION_END,
                    PG_FUNCTION + "SET LOCAL search_path TO public; RETURN;" + PG_FUNCTION_END)) {
                scopeRejects(PG, sql, "session namespace");
            }
            for (String keywordSchema : List.of("default", "from")) {
                assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                        create + "SET search_path = \"" + keywordSchema + "\", pg_temp" + body, keywordSchema, PG))
                        .doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    create + "SET search_path TO DEFAULT" + body, "default", PG))
                    .hasMessageContaining("session namespace");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    create + "SET search_path FROM CURRENT" + body, "from", PG))
                    .hasMessageContaining("session namespace");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireNoSessionNamespaceChange(
                    "SET search_path = public", PG)).hasMessageContaining("session namespace");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireNoSessionNamespaceChange(
                    create + "SET search_path = public" + body, PG)).hasMessageContaining("session namespace");
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

    /**
     * {@code q.column} in an expression is a column when {@code q} is a correlation the same
     * statement declares in scope (or a pseudo-row); calls, object positions and undeclared
     * qualifiers stay schema-checked.
     */
    @Nested
    class AliasQualifiedColumns {

        private static final String REJECTED = "targets namespace";

        @Test
        void postgresCorrelationsAreColumns() {
            for (String sql : List.of(
                    "UPDATE items AS i SET note = i.note",
                    "UPDATE items AS I SET note = i.note",
                    "UPDATE items SET note = 'x' WHERE items.id = 1",
                    "UPDATE items i SET note = t.name FROM tags t WHERE t.id = i.tag_id",
                    "UPDATE items u SET note = 'x' FROM tags t JOIN labels l ON l.id = t.label_id "
                            + "WHERE u.id = t.item_id",
                    "INSERT INTO items (id, note) SELECT s.id, s.note FROM staged s",
                    "UPDATE items SET note = s.note FROM (SELECT id, note FROM staged) AS s WHERE s.id = items.id",
                    "UPDATE items i SET note = (SELECT t.name FROM tags t WHERE t.id = i.tag_id)",
                    "INSERT INTO items AS i (id, note) VALUES (1, 'x') "
                            + "ON CONFLICT (id) DO UPDATE SET note = i.note || EXCLUDED.note",
                    "CREATE FUNCTION trg_f() RETURNS trigger AS $$ BEGIN NEW.note := OLD.note; RETURN NEW; END $$ "
                            + "LANGUAGE plpgsql",
                    PG_FUNCTION + "UPDATE items AS i SET note = i.note; RETURN;" + PG_FUNCTION_END)) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "DELETE FROM items AS i USING tags t WHERE t.id = i.tag_id",
                    "WITH c AS (SELECT id FROM staged) UPDATE items SET note = 'x' WHERE id IN (SELECT c.id FROM c)",
                    "MERGE INTO items AS t USING staged AS s ON t.id = s.id "
                            + "WHEN MATCHED THEN UPDATE SET note = s.note "
                            + "WHEN NOT MATCHED THEN INSERT (id, note) VALUES (s.id, s.note)")) {
                scopeAccepts(PG, sql);
            }
        }

        @Test
        void postgresUndeclaredQualifiersCallsAndObjectsStayChecked() {
            for (String sql : List.of(
                    "UPDATE items AS other SET note = (SELECT x FROM other.t)",
                    "UPDATE items AS other SET note = other.f(1)",
                    "DELETE FROM items AS other WHERE id IN (SELECT id FROM other.t)",
                    "SELECT other.x FROM other.t",
                    "UPDATE items SET note = 'x' WHERE other.id = 1",
                    "UPDATE items AS \"OTHER\" SET note = other.note",
                    "UPDATE items i SET note = i.note WHERE i.id IN (SELECT other.id FROM t)",
                    "UPDATE items SET note = (SELECT 1 FROM t other) || other.note",
                    "UPDATE items SET note = excluded.note",
                    "UPDATE items SET note = new.note",
                    "INSERT INTO items (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET note = excluded.f(1)",
                    PG_FUNCTION + "UPDATE items AS other SET qty = 1; UPDATE items SET note = other.note; RETURN;"
                            + PG_FUNCTION_END)) {
                scopeRejects(PG, sql, REJECTED);
            }
        }

        @Test
        void mysqlAndMariaDbCorrelationsAreColumns() {
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                for (String sql : List.of(
                        "UPDATE items AS i SET note = i.note",
                        "UPDATE items i JOIN tags t ON t.id = i.tag_id SET i.note = t.name",
                        "UPDATE items SET items.note = 'x'",
                        "UPDATE items i, tags t SET i.note = t.name WHERE t.id = i.tag_id",
                        "INSERT INTO items (id, note) VALUES (1, 'x') ON DUPLICATE KEY UPDATE items.note = 'y'",
                        "INSERT INTO items (id, note) SELECT s.id, s.note FROM staged s",
                        MYSQL_TRIGGER + "SET NEW.note = OLD.note; END")) {
                    accepted(dialect, sql);
                }
                scopeAccepts(dialect, "DELETE i FROM items i JOIN tags t ON t.id = i.tag_id WHERE t.name = 'x'");
                scopeAccepts(dialect, "DELETE FROM items WHERE items.id = 1");
                for (String sql : List.of(
                        "UPDATE items AS other SET note = (SELECT x FROM other.t)",
                        "UPDATE items AS other SET note = other.f(1)",
                        "DELETE FROM items AS other WHERE id IN (SELECT id FROM other.t)",
                        "SELECT other.x FROM other.t",
                        "UPDATE items i JOIN tags t ON t.id = i.tag_id SET i.note = other.name",
                        "UPDATE items AS OTHER SET note = other.note",
                        "INSERT INTO items (id) VALUES (1) ON DUPLICATE KEY UPDATE other.note = 'y'",
                        MYSQL_TRIGGER + "UPDATE items other SET qty = 1; SET NEW.note = other.note; END")) {
                    scopeRejects(dialect, sql, REJECTED);
                }
            }
        }

        @Test
        void sqlServerCorrelationsAndOutputRowsAreColumns() {
            for (String sql : List.of(
                    "UPDATE i SET i.qty = 1 OUTPUT inserted.qty FROM dbo.items i",
                    "UPDATE items SET qty = 1 OUTPUT inserted.qty, deleted.qty",
                    "UPDATE I SET i.qty = 1 FROM dbo.items i",
                    "UPDATE i SET i.note = x.name FROM dbo.items i CROSS APPLY (SELECT t.name FROM dbo.tags t "
                            + "WHERE t.id = i.tag_id) x",
                    MSSQL_TRIGGER + "UPDATE l SET l.qty = i.qty FROM dbo.item_log l JOIN inserted i ON i.id = l.id; "
                            + "UPDATE dbo.items SET note = 'x' WHERE id IN (SELECT deleted.id FROM deleted); END")) {
                accepted(MSSQL, sql);
            }
            scopeAccepts(MSSQL, "DELETE i FROM dbo.items i JOIN dbo.tags t ON t.id = i.tag_id");
            scopeAccepts(MSSQL, "MERGE dbo.items AS t USING dbo.staged AS s ON t.id = s.id "
                    + "WHEN MATCHED THEN UPDATE SET t.note = s.note "
                    + "WHEN NOT MATCHED THEN INSERT (id, note) VALUES (s.id, s.note);");
            for (String sql : List.of(
                    "UPDATE items other SET note = 1 FROM other.t",
                    "UPDATE items SET note = other.x",
                    "UPDATE items SET note = other.f(1)",
                    "DELETE FROM items WHERE id IN (SELECT id FROM other.t)",
                    "SELECT other.x FROM other.t",
                    MSSQL_TRIGGER + "UPDATE dbo.items SET qty = 1 FROM dbo.items other "
                            + "UPDATE dbo.item_log SET note = other.note; END")) {
                scopeRejects(MSSQL, sql, REJECTED);
            }
            scopeRejects(MSSQL, "UPDATE items SET note = inserted.note", REJECTED);
            scopeRejects(MSSQL, "UPDATE items SET qty = 1 OUTPUT inserted.f(1)", REJECTED);
        }

        @Test
        void oracleCorrelationsAndTriggerRowsAreColumns() {
            for (String sql : List.of(
                    "UPDATE items i SET note = i.note",
                    "UPDATE items I SET note = i.note",
                    ORACLE_TRIGGER + ":NEW.note := :OLD.note; END;",
                    "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items REFERENCING NEW AS n OLD AS o "
                            + "FOR EACH ROW BEGIN :n.note := :o.note; END;")) {
                accepted(ORACLE, sql);
            }
            scopeAccepts(ORACLE, "DELETE FROM items i WHERE i.id IN (SELECT t.item_id FROM tags t)");
            scopeAccepts(ORACLE, "MERGE INTO items t USING staged s ON (t.id = s.id) "
                    + "WHEN MATCHED THEN UPDATE SET t.note = s.note "
                    + "WHEN NOT MATCHED THEN INSERT (id, note) VALUES (s.id, s.note)");
            for (String sql : List.of(
                    "UPDATE items i SET note = other.f",
                    "UPDATE items other SET note = (SELECT x FROM other.t)",
                    "UPDATE items other SET note = other.f(1)",
                    "UPDATE items \"other\" SET note = other.f",
                    "UPDATE items SET note = (SELECT 1 FROM t other) || other.f",
                    "UPDATE items SET note = (SELECT other.x FROM t)",
                    ORACLE_TRIGGER + "UPDATE items other SET qty = 1; :NEW.note := other.f; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
        }

        @Test
        void aNameResolvesOnlyInItsOwnSetOperationBranch() {
            for (DatabaseDialect dialect : List.of(PG, MYSQL, MARIADB, MSSQL, ORACLE)) {
                accepted(dialect, "INSERT INTO items (id, note) SELECT s.id, s.note FROM staged s "
                        + "UNION ALL SELECT t.id, t.name FROM tags t");
                scopeRejects(dialect, "INSERT INTO items (id) SELECT other.x FROM staged "
                        + "UNION ALL SELECT id FROM tags other", REJECTED);
            }
            for (String sql : List.of(
                    "SELECT other.f FROM dual UNION ALL SELECT 1 FROM t other",
                    "SELECT (SELECT other.f FROM dual) FROM dual UNION ALL SELECT 1 FROM t other",
                    "SELECT 1 FROM t other UNION SELECT other.f FROM dual",
                    "SELECT 1 FROM t other INTERSECT SELECT other.f FROM dual",
                    "SELECT 1 FROM t other MINUS SELECT other.f FROM dual",
                    "INSERT INTO items (id) SELECT other.f FROM dual UNION ALL SELECT 1 FROM t other")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
            accepted(ORACLE, "INSERT INTO items (id) SELECT i.id FROM items i UNION SELECT (SELECT t.id FROM tags t "
                    + "WHERE t.id = s.id) FROM staged s");
        }

        @Test
        void oracleTriggerRowsNeedTheColonOutsideTheWhenCondition() {
            String referencing = "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items REFERENCING NEW AS other "
                    + "FOR EACH ROW ";
            for (String sql : List.of(
                    referencing + "BEGIN :other.note := 'x'; END;",
                    referencing + "WHEN (other.qty > 0) BEGIN :other.note := 'x'; END;",
                    "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW WHEN (new.qty > old.qty) "
                            + "BEGIN :NEW.note := :OLD.note; END;",
                    ORACLE_TRIGGER + ":new.note := :old.note; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    referencing + "BEGIN :other.note := other.f; END;",
                    referencing + "BEGIN :other.id := other.s.NEXTVAL; END;",
                    referencing + "WHEN (other.qty > 0) BEGIN :other.note := other.f; END;",
                    ORACLE_TRIGGER + ":NEW.note := new.f; END;",
                    ORACLE_TRIGGER + ":NEW.note := old.f; END;",
                    ORACLE_TRIGGER + ":NEW.id := new.s.NEXTVAL; END;",
                    ORACLE_TRIGGER + ":NEW.id := :new.s.NEXTVAL; END;",
                    ORACLE_TRIGGER + ":NEW.note := : new.f; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
        }

        @Test
        void postgresRelationNameLiteralsAreScopeChecked() {
            for (String sql : List.of(
                    "UPDATE items SET qty = nextval('items_seq')",
                    "UPDATE items SET qty = nextval('public.items_seq')",
                    "UPDATE items SET qty = nextval('PUBLIC.items_seq')",
                    "UPDATE items SET qty = nextval('items_seq'::regclass)",
                    "UPDATE items SET qty = pg_catalog.nextval('\"Items_Seq\"')",
                    "UPDATE items SET qty = currval('items_seq') + setval('public.items_seq', 5)",
                    "ALTER TABLE items ALTER COLUMN id SET DEFAULT nextval('items_id_seq'::regclass)",
                    "SELECT to_regclass('public.items') IS NOT NULL",
                    "SELECT 'items'::regclass, CAST('public.items' AS regclass), regclass 'items'",
                    "SELECT 'pg_catalog.pg_class'::regclass")) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "UPDATE items SET qty = nextval('other.seq')",
                    "UPDATE items SET qty = nextval('\"other\".seq')",
                    "UPDATE items SET qty = nextval('\"PUBLIC\".items_seq')",
                    "UPDATE items SET qty = nextval('other.seq'::regclass)",
                    "UPDATE items SET qty = pg_catalog.nextval('other.seq')",
                    "UPDATE items SET qty = currval('other.seq')",
                    "UPDATE items SET qty = setval('other.seq', 1)",
                    "SELECT to_regclass('other.t')",
                    "SELECT 'other.seq'::regclass",
                    "SELECT CAST('other.t' AS regclass)",
                    "SELECT regclass 'other.t'",
                    PG_FUNCTION + "PERFORM nextval('other.seq'); RETURN;" + PG_FUNCTION_END)) {
                scopeRejects(PG, sql, REJECTED);
            }
            for (String sql : List.of(
                    "UPDATE items SET qty = nextval(note)",
                    "UPDATE items SET qty = nextval('oth' || 'er.seq')",
                    "UPDATE items SET qty = nextval('other.seq'::text::regclass)",
                    "UPDATE items SET qty = nextval(E'other.seq')",
                    "UPDATE items SET qty = nextval('db.public.seq')",
                    "UPDATE items SET qty = nextval('other.')")) {
                assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(sql, namespace(PG), PG))
                        .as(sql).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void fromAndForInsideFunctionsAndDistinctPredicatesTakeOperands() {
            for (String sql : List.of(
                    "CREATE TRIGGER trg BEFORE UPDATE ON items FOR EACH ROW "
                            + "WHEN (OLD.note IS DISTINCT FROM NEW.note) EXECUTE FUNCTION trg_f()",
                    "UPDATE items i SET note = s.note FROM src s WHERE i.note IS DISTINCT FROM s.note",
                    "UPDATE items i SET note = 'x' WHERE i.note IS NOT DISTINCT FROM NULL",
                    "UPDATE items i SET qty = EXTRACT(YEAR FROM i.created_at)",
                    "UPDATE items i SET note = TRIM(BOTH ' ' FROM i.note)",
                    "UPDATE items i SET note = SUBSTRING(i.note FROM 2 FOR 3)",
                    "UPDATE items i SET note = SUBSTRING(i.note FROM i.qty FOR i.qty)",
                    "UPDATE items i SET note = OVERLAY(i.note PLACING 'x' FROM 1 FOR 1)",
                    "INSERT INTO items AS x (id, note) VALUES (1, 'n') ON CONFLICT (id) "
                            + "DO UPDATE SET note = EXCLUDED.note WHERE x.note IS DISTINCT FROM EXCLUDED.note")) {
                accepted(PG, sql);
            }
            for (DatabaseDialect dialect : List.of(MYSQL, MARIADB)) {
                accepted(dialect, "UPDATE items i SET i.qty = EXTRACT(YEAR FROM i.created_at)");
                accepted(dialect, "UPDATE items i SET i.note = TRIM(BOTH ' ' FROM i.note)");
                accepted(dialect, "UPDATE items i SET i.note = SUBSTRING(i.note FROM 2 FOR 3)");
            }
            accepted(MSSQL, "UPDATE i SET i.note = TRIM(' ' FROM i.note) FROM dbo.items i");
            accepted(ORACLE, "UPDATE items i SET qty = EXTRACT(YEAR FROM i.created_at)");
            accepted(ORACLE, "UPDATE items i SET note = TRIM(BOTH ' ' FROM i.note)");
            for (DatabaseDialect dialect : List.of(PG, MYSQL, MARIADB, MSSQL, ORACLE)) {
                for (String sql : List.of(
                        "UPDATE items SET qty = COALESCE((SELECT 1 FROM other.t), 0)",
                        "UPDATE items SET qty = ABS((SELECT x FROM other.t))",
                        "UPDATE items SET note = 'x' WHERE EXISTS (SELECT 1 FROM other.t)",
                        "UPDATE items SET note = TRIM(BOTH ' ' FROM x) || x.f",
                        "UPDATE items SET note = TRIM(BOTH x.f FROM x)",
                        "UPDATE items SET note = SUBSTRING(x.f FROM x FOR 1)",
                        "UPDATE items i SET note = 'x' WHERE i.note IS DISTINCT FROM other.note")) {
                    scopeRejects(dialect, sql, REJECTED);
                }
            }
            scopeRejects(PG, "UPDATE items i SET note = s.note FROM other.src s WHERE i.note IS DISTINCT FROM s.note",
                    REJECTED);
        }

        @Test
        void tableFunctionsTableVariablesAndRowAliasesDeclareNames() {
            for (String sql : List.of(
                    "UPDATE i SET i.note = j.note FROM dbo.items i JOIN OPENJSON(N'[{\"id\":1}]') "
                            + "WITH (id INT '$.id', note NVARCHAR(40) '$.note') AS j ON j.id = i.id",
                    "UPDATE i SET i.note = j.note FROM dbo.items i CROSS APPLY OPENJSON(i.doc) "
                            + "WITH (note NVARCHAR(40)) j",
                    MSSQL_TRIGGER + "DECLARE @t TABLE (id BIGINT); INSERT INTO @t SELECT id FROM inserted; "
                            + "UPDATE i SET i.qty = 1 FROM dbo.items i JOIN @t AS x ON x.id = i.id; "
                            + "UPDATE i SET i.qty = 2 FROM dbo.items i JOIN @t x ON x.id = i.id; END")) {
                accepted(MSSQL, sql);
            }
            scopeRejects(MSSQL, MSSQL_TRIGGER + "DECLARE @t TABLE (id BIGINT); "
                    + "UPDATE i SET i.qty = 1 FROM dbo.items i JOIN @t x ON other.id = i.id; END", REJECTED);
            scopeRejects(MSSQL, "UPDATE i SET i.note = other.note FROM dbo.items i JOIN OPENJSON(N'[]') "
                    + "WITH (note NVARCHAR(40)) AS j ON j.note = i.note", REJECTED);
            for (String sql : List.of(
                    "INSERT INTO items (id, note) VALUES (1, 'a') AS new ON DUPLICATE KEY UPDATE note = new.note",
                    "INSERT INTO items (id, note) VALUES (1, 'a'), (2, 'b') AS new (i, n) "
                            + "ON DUPLICATE KEY UPDATE note = new.n",
                    "INSERT INTO items (id, note) VALUES ROW(1, 'a'), ROW(2, 'b') AS r "
                            + "ON DUPLICATE KEY UPDATE note = r.note")) {
                accepted(MYSQL, sql);
            }
            for (String sql : List.of(
                    "INSERT INTO items (id, note) VALUES (1, 'a') AS new ON DUPLICATE KEY UPDATE note = other.note",
                    "INSERT INTO items (id) VALUES (1) ON DUPLICATE KEY UPDATE note = new.note",
                    "INSERT INTO items (id) SELECT 1 FROM dual WHERE 1 = (SELECT 1) AND new.x = 1")) {
                scopeRejects(MYSQL, sql, REJECTED);
            }
        }

        @Test
        void routineRecordsVariablesAndParametersQualifyColumns() {
            for (String sql : List.of(
                    PG_FUNCTION + "FOR r IN SELECT id FROM items LOOP UPDATE items SET qty = r.id WHERE id = r.id; "
                            + "END LOOP; RETURN;" + PG_FUNCTION_END,
                    PG_FUNCTION + "FOR r IN (SELECT id FROM items) LOOP UPDATE items SET qty = r.id; END LOOP; RETURN;"
                            + PG_FUNCTION_END,
                    "CREATE FUNCTION f() RETURNS void AS $$ DECLARE v items%ROWTYPE; n items.note%TYPE; "
                            + "BEGIN SELECT * INTO v FROM items WHERE id = 1; n := v.note; "
                            + "UPDATE items SET note = v.note WHERE id = 2; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(p integer) RETURNS integer AS $$ BEGIN RETURN f.p + 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r items) RETURNS text AS $$ BEGIN RETURN r.note; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(IN r items) RETURNS text LANGUAGE sql RETURN r.note")) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "CREATE FUNCTION f(p integer) RETURNS integer AS $$ BEGIN RETURN g.p; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS void AS $$ DECLARE v other.t%ROWTYPE; BEGIN NULL; END $$ "
                            + "LANGUAGE plpgsql",
                    PG_FUNCTION + "UPDATE items SET qty = other.id; RETURN;" + PG_FUNCTION_END)) {
                scopeRejects(PG, sql, REJECTED);
            }
            String oracleFunction = "CREATE OR REPLACE FUNCTION f(p NUMBER) RETURN NUMBER IS ";
            for (String sql : List.of(
                    oracleFunction + "BEGIN FOR r IN (SELECT id FROM items) LOOP "
                            + "UPDATE items SET qty = r.id WHERE id = r.id; END LOOP; RETURN 1; END;",
                    oracleFunction + "v items%ROWTYPE; n items.note%TYPE; BEGIN SELECT * INTO v FROM items "
                            + "WHERE id = 1; n := v.note; UPDATE items SET note = v.note WHERE id = 2; RETURN 1; END;",
                    oracleFunction + "BEGIN RETURN f.p + 1; END;",
                    "CREATE OR REPLACE FUNCTION f(r items%ROWTYPE) RETURN VARCHAR2 IS BEGIN RETURN r.note; END;",
                    "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW DECLARE v items.note%TYPE; "
                            + "BEGIN v := :NEW.note; :NEW.note := v; END;",
                    ORACLE_TRIGGER + "DECLARE v items%ROWTYPE; BEGIN v.note := 'x'; :NEW.note := v.note; END; END;",
                    oracleFunction + "BEGIN FOR r IN (SELECT id FROM items) LOOP FOR s IN (SELECT id FROM tags) LOOP "
                            + "UPDATE items SET qty = r.id + s.id; END LOOP; UPDATE items SET qty = r.id; END LOOP; "
                            + "RETURN 1; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    oracleFunction + "BEGIN FOR r IN (SELECT id FROM items) LOOP NULL; END LOOP; "
                            + "UPDATE items SET note = r.f; RETURN 1; END;",
                    oracleFunction + "BEGIN FOR r IN (SELECT id FROM items) LOOP FOR s IN (SELECT id FROM tags) LOOP "
                            + "NULL; END LOOP; UPDATE items SET qty = s.f; END LOOP; RETURN 1; END;",
                    oracleFunction + "BEGIN DECLARE v items%ROWTYPE; BEGIN NULL; END; UPDATE items SET note = v.f; "
                            + "RETURN 1; END;",
                    oracleFunction + "BEGIN RETURN g.p; END;",
                    oracleFunction + "v other.t%ROWTYPE; BEGIN RETURN 1; END;",
                    oracleFunction + "v other.t.c%TYPE; BEGIN RETURN 1; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
        }

        @Test
        void oracleSequencesInTheCurrentSchema() {
            for (String sql : List.of(
                    "INSERT INTO items (id) VALUES (items_seq.NEXTVAL)",
                    "UPDATE items SET qty = items_seq.currval",
                    "INSERT INTO items (id) VALUES (APP.items_seq.NEXTVAL)",
                    ORACLE_TRIGGER + ":NEW.id := items_seq.NEXTVAL; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    "INSERT INTO items (id) VALUES (other.s.NEXTVAL)",
                    "UPDATE items SET qty = other.nextval(1)",
                    "UPDATE items SET qty = other.s.CURRVAL")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
        }

        @Test
        void oracleSuppliedPackagesAndSchemaQualifiedPackageCalls() {
            for (String call : List.of(
                    "DBMS_OUTPUT.PUT_LINE(:NEW.note);",
                    "sys.dbms_output.put_line('x');",
                    ":NEW.note := DBMS_UTILITY.FORMAT_ERROR_STACK;",
                    ":NEW.qty := DBMS_RANDOM.VALUE;",
                    ":NEW.note := UTL_RAW.CAST_TO_VARCHAR2(UTL_RAW.CAST_TO_RAW('a'));",
                    ":NEW.note := UTL_I18N.RAW_TO_CHAR(UTL_I18N.STRING_TO_RAW('a', 'AL32UTF8'), 'AL32UTF8');",
                    ":NEW.qty := DBMS_LOB.GETLENGTH(:NEW.doc);",
                    ":NEW.note := APP.util_pkg.normalize(:NEW.note);")) {
                accepted(ORACLE, ORACLE_TRIGGER + call + " END;");
            }
            for (String call : List.of(
                    ":NEW.note := util_pkg.normalize(:NEW.note);",
                    ":NEW.note := DBMS_ASSERT.ENQUOTE_LITERAL(:NEW.note);",
                    "DBMS_UTILITY.EXEC_DDL_STATEMENT('x');",
                    "DBMS_LOB.OPEN(:NEW.doc, 0);",
                    ":NEW.note := other.pkg.proc(1);",
                    "\"dbms_output\".put_line('x');",
                    "other.DBMS_OUTPUT.PUT_LINE('x');")) {
                scopeRejects(ORACLE, ORACLE_TRIGGER + call + " END;", REJECTED);
            }
        }

        @Test
        void cteUpdatesAndInsertsFollowEachEngine() {
            String cteUpdate = "WITH c AS (SELECT id FROM staged) UPDATE items SET note = 'x' WHERE id IN (SELECT c.id FROM c)";
            String cteInsert = "WITH s AS (SELECT 1 AS id) INSERT INTO items (id) SELECT s.id FROM s";
            for (DatabaseDialect dialect : List.of(PG, MSSQL)) {
                accepted(dialect, cteUpdate);
                accepted(dialect, cteInsert);
            }
            accepted(MYSQL, cteUpdate);
            policyRejects(MYSQL, cteInsert, "unsupported");
            for (DatabaseDialect dialect : List.of(MARIADB, ORACLE)) {
                policyRejects(dialect, cteUpdate, "unsupported");
                policyRejects(dialect, cteInsert, "unsupported");
            }
            for (DatabaseDialect dialect : List.of(PG, MSSQL, MYSQL)) {
                policyRejects(dialect, "WITH c AS (SELECT id FROM staged) DELETE FROM items WHERE id IN "
                        + "(SELECT id FROM c)", DESTRUCTIVE);
                scopeRejects(dialect, "WITH c AS (SELECT id FROM other.t) UPDATE items SET note = 'x'", REJECTED);
            }
            policyRejects(PG, "WITH d AS (DELETE FROM items RETURNING id) UPDATE tags SET name = 'x'", DESTRUCTIVE);
            scopeRejects(ORACLE, "WITH other AS (SELECT 1 x FROM dual) SELECT other.f FROM dual", REJECTED);
        }
    }

    /**
     * Oracle entry points that run SQL text, reach other schemas through XML/URI/OLAP access, or
     * hand execution to code the synchronizer cannot read.
     */
    @Nested
    class OracleDynamicSql {

        private static final String FUNCTION =
                "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS c SYS_REFCURSOR; s VARCHAR2(99) := 'x'; BEGIN ";
        private static final String FUNCTION_END = " RETURN 1; END;";

        @Test
        void openForAcceptsOnlyAStaticQuery() {
            for (String open : List.of(
                    "OPEN c FOR SELECT id FROM items;",
                    "OPEN c FOR (SELECT id FROM items);",
                    "OPEN c FOR ((SELECT id FROM items));",
                    "OPEN c FOR WITH w AS (SELECT id FROM items) SELECT id FROM w;",
                    "open c for select id from items;")) {
                accepted(ORACLE, FUNCTION + open + FUNCTION_END);
            }
            accepted(ORACLE, ORACLE_TRIGGER + "DECLARE c SYS_REFCURSOR; BEGIN OPEN c FOR SELECT id FROM items; "
                    + "CLOSE c; END; END;");
            for (String open : List.of(
                    "OPEN c FOR 'SELECT 1 FROM other.t';",
                    "OPEN c FOR ('SELECT 1 FROM other.t');",
                    "OPEN c FOR s;",
                    "OPEN c FOR s || ' WHERE 1 = 1';",
                    "OPEN c FOR 'SELECT ' || 'x FROM dual';",
                    "OPEN c FOR lower(s);",
                    "OPEN c FOR q'[SELECT 1 FROM other.t]';",
                    "OPEN c FOR s USING 1;",
                    "OPEN :c FOR s;",
                    "OPEN pkg.c FOR s;")) {
                policyRejects(ORACLE, FUNCTION + open + FUNCTION_END, DESTRUCTIVE);
            }
            policyRejects(ORACLE, FUNCTION + "EXECUTE IMMEDIATE s;" + FUNCTION_END, DESTRUCTIVE);
        }

        @Test
        void everyDynamicSqlAndExternalAccessNameIsRejected() {
            for (String name : List.of(
                    "DBMS_SQL", "DBMS_SYS_SQL", "DBMS_XMLGEN", "DBMS_XMLQUERY", "DBMS_XMLSTORE", "DBMS_XMLSAVE",
                    "DBMS_AW", "DBMS_ODCI", "DBMS_PARALLEL_EXECUTE", "DBMS_SQLTUNE", "DBMS_SQLTUNE_UTIL0",
                    "DBMS_SQLDIAG", "DBMS_SQLPA", "DBMS_SQLSET", "DBMS_SQLQ", "DBMS_SPM", "DBMS_SQL_TRANSLATOR",
                    "DBMS_SQL_MONITOR", "DBMS_XPLAN", "DBMS_ADVISOR", "DBMS_ADDM", "DBMS_SNAPSHOT", "DBMS_MVIEW",
                    "DBMS_SYNC_REFRESH", "DBMS_SPACE", "DBMS_DATA_MINING", "DBMS_DIMENSION", "DBMS_SUMMARY",
                    "DBMS_DEBUG", "DBMS_JOB", "DBMS_SCHEDULER", "DBMS_DDL", "DBMS_REDEFINITION",
                    "DBMS_HS_PASSTHROUGH", "DBMS_DATAPUMP", "DBMS_XSLPROCESSOR", "DBMS_PIPE", "OWA_UTIL",
                    "UTL_HTTP", "UTL_TCP", "UTL_SMTP", "UTL_MAIL", "UTL_FILE", "UTL_INADDR")) {
                policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.note := " + name + ".run('SELECT 1 FROM dual'); END;",
                        DESTRUCTIVE);
                policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.note := sys." + name.toLowerCase()
                        + ".run('x'); END;", DESTRUCTIVE);
            }
            policyRejects(ORACLE, ORACLE_TRIGGER + ":NEW.note := DBMS_UTILITY.EXPAND_SQL_TEXT('x'); END;", DESTRUCTIVE);
            for (String call : List.of(
                    "DBURITYPE('/OTHER/T/ROW/NOTE').getClob()",
                    "XDBURITYPE('/public/x').getClob()",
                    "HTTPURITYPE('example.com').getClob()",
                    "FTPURITYPE('example.com').getClob()",
                    "URIFACTORY.getUri('/OTHER/T').getClob()",
                    "(SELECT COUNT(*) FROM TABLE(CUBE_TABLE('OTHER.C')))",
                    "(SELECT COUNT(*) FROM TABLE(OLAP_TABLE('OTHER.AW', 't', '', '')))",
                    "XMLQUERY('fn:collection(\"oradb:/OTHER/T\")' RETURNING CONTENT).getStringVal()",
                    "XMLQUERY('for $r in collection(\"oradb:/OTHER/T\") return $r' RETURNING CONTENT).getStringVal()",
                    "XMLQUERY('fn:doc(\"/public/x.xml\")' RETURNING CONTENT).getStringVal()",
                    "XMLQUERY('ora:view(\"OTHER\", \"T\")' RETURNING CONTENT).getStringVal()",
                    "(SELECT COUNT(*) FROM XMLTABLE('uri-collection(\"/public\")'))",
                    "(SELECT 1 FROM dual WHERE XMLEXISTS('fn:doc-available(\"/x\")'))")) {
                policyRejects(ORACLE, "UPDATE items SET note = " + call, DESTRUCTIVE);
            }
            for (String call : List.of(
                    "XMLQUERY('/a/b' PASSING XMLTYPE(note) RETURNING CONTENT).getStringVal()",
                    "(SELECT COUNT(*) FROM XMLTABLE('/r/i' PASSING XMLTYPE(note) COLUMNS v NUMBER PATH 'v'))",
                    "XMLQUERY('$d/collection' PASSING XMLTYPE(note) AS \"d\" RETURNING CONTENT).getStringVal()",
                    "'fn:collection(\"x\")'")) {
                accepted(ORACLE, "UPDATE items SET note = " + call);
            }
        }

        @Test
        void sqlMacrosAndCallSpecsAreRejected() {
            for (String sql : List.of(
                    "CREATE OR REPLACE FUNCTION f RETURN VARCHAR2 SQL_MACRO IS BEGIN RETURN 'SELECT * FROM other.t'; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN VARCHAR2 SQL_MACRO(TABLE) IS BEGIN RETURN 'x'; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER AS LANGUAGE JAVA NAME 'Foo.bar() return int';",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER AS LANGUAGE C NAME \"f\" LIBRARY lib;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER AS EXTERNAL NAME \"f\" LIBRARY lib;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS EXTERNAL LIBRARY lib;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER AS EXTERNAL;")) {
                policyRejects(ORACLE, sql, DESTRUCTIVE);
            }
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f(language NUMBER) RETURN NUMBER IS external NUMBER := 1; "
                    + "BEGIN RETURN language + external; END;");
        }
    }

    /**
     * PostgreSQL functions and casts that take an object name as text, classified from pg_proc:
     * export and query-string functions are rejected; name-to-metadata functions are scope-checked.
     */
    @Nested
    class PostgresNameArguments {

        private static final String REJECTED = "targets namespace";

        private static final List<String> EXPORTS_AND_QUERY_STRINGS = List.of(
                "query_to_xml", "query_to_xmlschema", "query_to_xml_and_xmlschema",
                "cursor_to_xml", "cursor_to_xmlschema",
                "table_to_xml", "table_to_xmlschema", "table_to_xml_and_xmlschema",
                "schema_to_xml", "schema_to_xmlschema", "schema_to_xml_and_xmlschema",
                "database_to_xml", "database_to_xmlschema", "database_to_xml_and_xmlschema",
                "ts_stat", "ts_rewrite", "pg_nextoid", "dblink", "dblink_exec", "pg_read_file",
                "pg_import_system_collations", "lo_import");

        private static final List<String> NAME_REQUIRED_FUNCTIONS = List.of(
                "nextval", "currval", "setval", "pg_get_serial_sequence", "pg_extension_config_dump",
                "brin_summarize_new_values", "brin_summarize_range", "brin_desummarize_range",
                "gin_clean_pending_list");

        private static final List<String> METADATA_FUNCTIONS = List.of(
                "to_regclass", "pg_get_viewdef",
                "pg_relation_size", "pg_table_size", "pg_total_relation_size", "pg_indexes_size",
                "pg_relation_filenode", "pg_relation_filepath", "pg_sequence_last_value",
                "pg_get_replica_identity_index", "pg_column_is_updatable", "pg_relation_is_updatable",
                "pg_relation_is_publishable", "pg_partition_root", "pg_partition_tree", "pg_partition_ancestors",
                "pg_index_has_property", "pg_index_column_has_property");

        private static final List<String> RELATION_NAME_FUNCTIONS = java.util.stream.Stream.concat(
                NAME_REQUIRED_FUNCTIONS.stream(), METADATA_FUNCTIONS.stream()).toList();

        private static final List<String> QUALIFIED_NAME_FUNCTIONS = List.of(
                "to_regproc", "to_regprocedure", "to_regtype", "to_regtypemod", "to_regoper", "to_regoperator",
                "to_regcollation");

        private static final List<String> REG_TYPES = List.of(
                "regclass", "regproc", "regprocedure", "regtype", "regoper", "regoperator", "regconfig",
                "regdictionary", "regcollation");

        @Test
        void exportAndQueryStringFunctionsAreRejected() {
            for (String function : EXPORTS_AND_QUERY_STRINGS) {
                for (String call : List.of(function, "pg_catalog." + function, "\"" + function + "\"",
                        function.toUpperCase())) {
                    policyRejects(PG, "SELECT " + call + "('items', true, false, '')", DESTRUCTIVE);
                }
                policyRejects(PG, PG_FUNCTION + "PERFORM " + function + "('items'); " + PG_FUNCTION_END, DESTRUCTIVE);
            }
            accepted(PG, "UPDATE items SET note = 'table_to_xml(x)'");
            accepted(PG, "SELECT xmlelement(name note, 'x')");
        }

        @Test
        void relationNameFunctionsAreScopeChecked() {
            for (String function : RELATION_NAME_FUNCTIONS) {
                accepted(PG, "SELECT " + function + "('items')");
                accepted(PG, "SELECT " + function + "('public.items')");
                accepted(PG, "SELECT pg_catalog." + function + "('\"items\"', 1)");
                accepted(PG, "SELECT " + function + "('items'::regclass)");
                accepted(PG, "SELECT " + function + "(CAST('items' AS pg_catalog.regclass), 1)");
                accepted(PG, "SELECT " + function + "(E'public.items')");
                accepted(PG, "SELECT " + function + "(U&'items')");
                accepted(PG, "SELECT " + function + "($$items$$)");
                accepted(PG, "SELECT " + function + "(('items'))");
                accepted(PG, "SELECT " + function + "('items'::text::regclass)");
                scopeRejects(PG, "SELECT " + function + "(CAST('other.items' AS regclass))", REJECTED);
                scopeRejects(PG, "SELECT " + function + "('other.items')", REJECTED);
                scopeRejects(PG, "SELECT " + function + "(E'other.items')", REJECTED);
                scopeRejects(PG, "SELECT " + function + "(U&'other.items')", REJECTED);
                scopeRejects(PG, "SELECT " + function + "($$other.items$$)", REJECTED);
                scopeRejects(PG, "SELECT " + function + "($q$other.items$q$)", REJECTED);
                scopeRejects(PG, "SELECT " + function + "(('other.items'))", REJECTED);
                scopeRejects(PG, "SELECT " + function + "('other.items'::varchar(40)::regclass)", REJECTED);
                scopeRejects(PG, "SELECT pg_catalog." + function + "('\"other\".items', 1)", REJECTED);
                scopeAccepts(PG, "SELECT " + function + "('pg_catalog.pg_class')");
                scopeRejects(PG, "SELECT " + function + "('other.' || 'items')", "string literal");
                scopeRejects(PG, "SELECT " + function + "(lower('other.items'))", "string literal");
            }
        }

        @Test
        void sequenceAndMaintenanceFunctionsRequireALiteralName() {
            for (String function : NAME_REQUIRED_FUNCTIONS) {
                scopeRejects(PG, "SELECT " + function + "(note) FROM items", "string literal");
                scopeRejects(PG, "SELECT " + function + "(c.oid) FROM pg_class c", "string literal");
                scopeRejects(PG, "SELECT " + function + "(CAST(note AS regclass)) FROM items", "string literal");
                scopeRejects(PG, "SELECT " + function + "(c.relname::regclass) FROM pg_class c", "string literal");
            }
        }

        @Test
        void metadataFunctionsAcceptOidAndColumnArguments() {
            for (String function : METADATA_FUNCTIONS) {
                accepted(PG, "SELECT " + function + "(c.oid) FROM pg_class c");
                accepted(PG, "SELECT " + function + "(c.oid, true) FROM pg_class c");
                accepted(PG, "SELECT " + function + "(CAST(note AS regclass)) FROM items");
                accepted(PG, "SELECT " + function + "(i.indexrelid, 'clusterable') FROM pg_index i");
            }
            for (String sql : List.of(
                    "SELECT pg_relation_size(c.oid) FROM pg_class c WHERE c.relname = 'items'",
                    "SELECT pg_get_viewdef(c.oid, true) FROM pg_class c",
                    "SELECT pg_index_has_property(i.indexrelid, 'clusterable') FROM pg_index i",
                    "SELECT pg_total_relation_size(c.oid) + pg_indexes_size(c.oid) FROM pg_class c",
                    "SELECT pg_relation_filepath(c.oid), pg_relation_filenode(c.oid) FROM pg_class c",
                    "SELECT c.oid::regclass FROM pg_class c")) {
                accepted(PG, sql);
            }
        }

        @Test
        void indexMaintenanceFunctionsAreRejectedInVerification() {
            for (String function : List.of("brin_summarize_new_values", "brin_summarize_range",
                    "brin_desummarize_range", "gin_clean_pending_list")) {
                verificationRejected(PG, "SELECT " + function + "('items_idx') = 0");
                verificationRejected(PG, "SELECT pg_catalog." + function + "('items_idx', 0) = 0");
                verificationRejected(PG, "SELECT \"" + function + "\"('items_idx') = 0");
                verificationRejected(PG, "SELECT " + function.toUpperCase() + "('items_idx') = 0");
                accepted(PG, "SELECT " + function + "('items_idx')");
                verificationAccepted(PG, "SELECT count(*) = 0 FROM items WHERE note = '" + function + "(x)'");
                verificationAccepted(PG, "SELECT count(*) = 0 FROM items WHERE " + function + "_note IS NULL");
            }
        }

        @Test
        void serialSequenceLookupsNestInsideSequenceFunctions() {
            for (String sql : List.of(
                    "SELECT setval(pg_get_serial_sequence('items', 'id'), coalesce(max(id), 1)) FROM items",
                    "SELECT setval(pg_get_serial_sequence('public.items', 'id'), coalesce(max(id), 1), true) FROM items",
                    "INSERT INTO items (id) VALUES (nextval(pg_get_serial_sequence('items', 'id')))",
                    "SELECT currval(pg_catalog.pg_get_serial_sequence('items', 'id'))",
                    "SELECT setval(pg_get_serial_sequence('\"items\"', 'id'), (SELECT max(id) FROM items))")) {
                accepted(PG, sql);
            }
            scopeRejects(PG, "SELECT setval(pg_get_serial_sequence('other.items', 'id'), 1)", REJECTED);
            scopeRejects(PG, "SELECT nextval(pg_get_serial_sequence('other.items', 'id'))", REJECTED);
            scopeRejects(PG, "SELECT currval(pg_get_serial_sequence(note, 'id')) FROM items", "string literal");
            scopeRejects(PG, "SELECT nextval(lower('other.s'))", "string literal");
        }

        @Test
        void qualifiedAndSchemaNameFunctionsAreScopeChecked() {
            for (String function : QUALIFIED_NAME_FUNCTIONS) {
                accepted(PG, "SELECT " + function + "('x')");
                accepted(PG, "SELECT " + function + "('public.x')");
                scopeRejects(PG, "SELECT " + function + "('other.x')", REJECTED);
            }
            accepted(PG, "SELECT to_regtype('character varying')");
            accepted(PG, "SELECT to_regprocedure('public.f(integer, text)')");
            scopeRejects(PG, "SELECT to_regprocedure('public.f(other.t)')", REJECTED);
            accepted(PG, "SELECT to_regnamespace('public')");
            scopeRejects(PG, "SELECT to_regnamespace('other')", REJECTED);
            accepted(PG, "SELECT to_regrole('other')");
        }

        @Test
        void privilegeAndInputCheckFunctionsScopeCheckTheirNames() {
            for (String sql : List.of(
                    "SELECT has_table_privilege('items', 'SELECT')",
                    "SELECT has_table_privilege('bob', 'public.items', 'SELECT, INSERT')",
                    "SELECT has_column_privilege('items', 'note', 'UPDATE')",
                    "SELECT has_sequence_privilege('items_id_seq', 'USAGE')",
                    "SELECT has_function_privilege('public.f(integer)', 'EXECUTE')",
                    "SELECT has_type_privilege('public.t', 'USAGE')",
                    "SELECT has_schema_privilege('public', 'USAGE')",
                    "SELECT has_schema_privilege('bob', 'public', 'USAGE')",
                    "SELECT has_database_privilege('otherdb', 'CONNECT')",
                    "SELECT pg_input_is_valid('42', 'integer')",
                    "SELECT pg_input_is_valid('items', 'regclass')",
                    "SELECT pg_input_error_info('x', 'pg_catalog.regtype')")) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "SELECT has_table_privilege('other.t', 'SELECT')",
                    "SELECT has_any_column_privilege('bob', 'other.t', 'SELECT')",
                    "SELECT has_function_privilege('other.f(integer)', 'EXECUTE')",
                    "SELECT has_schema_privilege('other', 'USAGE')",
                    "SELECT has_schema_privilege('bob', 'other', 'USAGE')",
                    "SELECT pg_input_is_valid('other.t', 'regclass')",
                    "SELECT pg_input_is_valid('other', 'pg_catalog.regnamespace')",
                    "SELECT pg_input_is_valid('x', 'other.t')",
                    "SELECT has_table_privilege($$other.t$$, 'SELECT')",
                    "SELECT has_table_privilege(E'other.t', 'SELECT')",
                    "SELECT has_table_privilege(('other.t'), 'SELECT')",
                    "SELECT has_table_privilege('other.t'::text, 'SELECT')",
                    "SELECT has_schema_privilege($$other$$, 'USAGE')",
                    "SELECT pg_input_is_valid($$other.t$$, $$regclass$$)",
                    "SELECT pg_input_is_valid('other.t'::text, 'regclass'::text)",
                    "SELECT pg_input_is_valid('x', $$other.t$$)",
                    "SELECT to_regtypemod('other.t')",
                    "SELECT to_regtypemod($$other.t(3)$$)")) {
                scopeRejects(PG, sql, REJECTED);
            }
            for (String type : List.of("regclass[]", "_regclass", "regclass ARRAY", "pg_catalog.regclass[]",
                    "regclass[3]", "regtype[]")) {
                scopeRejects(PG, "SELECT pg_input_is_valid('{other.t}', '" + type + "')", "literal array");
                scopeRejects(PG, "SELECT pg_input_is_valid('{items}', '" + type + "')", "literal array");
            }
            scopeRejects(PG, "SELECT pg_input_is_valid('{x}', 'other.t[]')", REJECTED);
            scopeRejects(PG, "SELECT pg_input_is_valid('other.t', lower('regclass'))", "string literal");
            accepted(PG, "SELECT pg_input_is_valid('{1,2}', 'integer[]')");
            accepted(PG, "SELECT to_regtypemod('varchar(20)')");
            accepted(PG, "SELECT to_regtypemod('public.t')");
            accepted(PG, "SELECT has_table_privilege(c.oid, 'SELECT') FROM pg_class c");
        }

        @Test
        void regTypeLiteralsAreScopeCheckedInEverySpelling() {
            for (String type : REG_TYPES) {
                for (String form : List.of(
                        "'other.x'::" + type, "'other.x'::pg_catalog." + type, "'other.x'::\"" + type + "\"",
                        "CAST('other.x' AS " + type + ")", "CAST('other.x' AS pg_catalog." + type + ")",
                        type + " 'other.x'", "pg_catalog." + type + " 'other.x'",
                        type + "('other.x')", "pg_catalog." + type + "('other.x')",
                        "'other.x'::" + type.toUpperCase())) {
                    scopeRejects(PG, "SELECT " + form, REJECTED);
                }
                accepted(PG, "SELECT 'x'::" + type);
                accepted(PG, "SELECT 'public.x'::pg_catalog." + type);
                for (String array : List.of("'{x}'::" + type + "[]", "'{x}'::_" + type, "'{x}'::" + type + " ARRAY",
                        "'{x}'::" + type + "[2]", "ARRAY['x']::" + type + "[]", "CAST('{x}' AS " + type + "[])",
                        "ARRAY[$$x$$]::" + type + "[]")) {
                    scopeRejects(PG, "SELECT " + array, "so the name can be checked");
                }
            }
        }

        @Test
        void regTypeLiteralsAreCheckedThroughEveryStringSpellingAndTextCast() {
            for (String form : List.of(
                    "$$other.t$$::regclass", "$q$other.t$q$::regclass", "E'other.t'::regclass",
                    "U&'other.t'::regclass", "regclass $$other.t$$", "CAST($q$other.t$q$ AS regclass)",
                    "('other.t')::regclass", "(('other.t'))::regclass", "CAST(('other.t') AS regclass)",
                    "'other.t'::text::regclass", "'other.t'::varchar::regclass", "'other.t'::varchar(20)::regclass",
                    "'other.t'::character varying::regclass", "'other.t'::name::regclass",
                    "'other.t'::bpchar::regclass", "'other.t'::pg_catalog.text::regclass",
                    "CAST('other.t' AS text)::regclass", "CAST('other.t'::text AS regclass)",
                    "text 'other.t'::regclass", "'other.t'::regclass::oid", "regclass('other.t'::text)")) {
                scopeRejects(PG, "SELECT " + form, REJECTED);
            }
            for (String form : List.of(
                    "$$items$$::regclass", "E'public.items'::regclass", "U&'items'::regclass",
                    "('items')::regclass", "'items'::text::regclass", "'items'::varchar(20)::regclass",
                    "'items'::character varying::regclass", "'items'::name::regclass",
                    "$$items$$::text::pg_catalog.regclass", "CAST('items' AS text)::regclass",
                    "'items'::regclass::oid")) {
                accepted(PG, "SELECT " + form);
            }
            for (String form : List.of(
                    "('other.' || 't')::regclass", "lower('X')::regclass", "concat('a', 'b')::regclass",
                    "coalesce(note, 'x')::regclass", "format('%I', 'x')::regclass",
                    "CAST(lower('x') AS regclass)", "(CASE WHEN true THEN 'other.t' END)::regclass")) {
                scopeRejects(PG, "SELECT " + form + " FROM items", "so the name can be checked");
            }
            for (String sql : List.of(
                    "SELECT note::regclass FROM items",
                    "SELECT (note)::regclass FROM items",
                    "SELECT c.oid::regclass FROM pg_class c",
                    "SELECT i.indexrelid::regclass FROM pg_index i",
                    "SELECT CAST(c.oid AS regclass) FROM pg_class c",
                    "SELECT count(*) FROM pg_class c WHERE c.oid = 'items'::regclass")) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "SELECT 'character varying'::regtype",
                    "SELECT 'double precision'::pg_catalog.regtype",
                    "SELECT 'integer[]'::regtype",
                    "SELECT 'pg_catalog.lower(text)'::regprocedure",
                    "SELECT 'public.f(integer, text)'::regprocedure",
                    "SELECT '+(integer,integer)'::regoperator",
                    "SELECT 'english'::regconfig",
                    "SELECT 'public'::regnamespace",
                    "SELECT 'other'::regrole",
                    "SELECT pg_get_viewdef('items'::regclass)",
                    "SELECT to_tsvector('english', 'a.b')",
                    "SELECT note::regclass FROM items")) {
                accepted(PG, sql);
            }
            scopeRejects(PG, "SELECT 'public.f(other.t)'::regprocedure", REJECTED);
            scopeRejects(PG, "SELECT 'other'::regnamespace", REJECTED);
            scopeAccepts(PG, "SELECT 'pg_catalog.pg_class'::regclass");
            scopeRejects(PG, "SELECT pg_relation_size(CAST('other.t' AS pg_catalog.regclass))", REJECTED);
        }
    }

    /**
     * A two-part name where a type is expected names schema.type: aliases, parameters, and
     * variables never qualify a type, so the qualifier is always scope-checked.
     */
    @Nested
    class TypePositions {

        private static final String REJECTED = "targets namespace";

        @Test
        void postgresTypePositionsIgnoreAliasesAndRoutineItems() {
            for (String sql : List.of(
                    "SELECT c::other.t FROM items other",
                    "SELECT note::other.t[] FROM items other",
                    "SELECT CAST(note AS other.t) FROM items other",
                    "SELECT other.t 'x' FROM items other",
                    "SELECT other.t $$x$$ FROM items other",
                    "SELECT note COLLATE other.\"C\" FROM items other",
                    "SELECT 1 OPERATOR(other.+) 1",
                    "SELECT XMLCAST(note AS other.t) FROM items other",
                    "CREATE FUNCTION f(other int, x other.t) RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other.t) RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(INOUT other.t) RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other int DEFAULT 1, VARIADIC x other.t[]) RETURNS int AS $$ BEGIN RETURN 1; "
                            + "END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION other() RETURNS other.t AS $$ BEGIN RETURN NULL; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other int) RETURNS SETOF other.t AS $$ BEGIN RETURN; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other int) RETURNS TABLE (a other.t) AS $$ BEGIN RETURN; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS int AS $$ DECLARE other int; x other.t; BEGIN RETURN 1; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS int AS $$ DECLARE other int; x other.t := NULL; BEGIN RETURN 1; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS int AS $$ DECLARE other int; BEGIN RETURN other::other.t; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION f(items) RETURNS int AS $$ BEGIN RETURN $1::other.t; END $$ LANGUAGE plpgsql",
                    "ALTER TABLE items ADD COLUMN c items.t",
                    "ALTER TABLE items ADD COLUMN IF NOT EXISTS c items.t",
                    "CREATE TABLE items2 (c items2.t)",
                    "CREATE TABLE IF NOT EXISTS items2 (id int, c items2.t)",
                    "CREATE INDEX i ON items (note items.text_ops)")) {
                scopeRejects(PG, sql, REJECTED);
            }
            for (String sql : List.of(
                    "SELECT i.note::text FROM items i",
                    "SELECT i.note::public.t FROM items i",
                    "SELECT i.note::pg_catalog.text FROM items i",
                    "SELECT CAST(i.note AS public.t) FROM items i",
                    "SELECT public.t 'x'",
                    "SELECT i.note COLLATE \"C\" FROM items i",
                    "SELECT i.note COLLATE pg_catalog.\"C\" FROM items i",
                    "SELECT 1 OPERATOR(pg_catalog.+) 1",
                    "SELECT 1 OPERATOR(public.+) 1",
                    "CREATE FUNCTION f(p items, q int DEFAULT 1) RETURNS int AS $$ BEGIN RETURN p.qty; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION f(items) RETURNS int AS $$ BEGIN RETURN $1.qty; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(items, int) RETURNS int AS $$ BEGIN RETURN $1.qty + $2; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(p public.items) RETURNS public.items AS $$ DECLARE x public.items; "
                            + "y items.note%TYPE; BEGIN RETURN p; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS int AS $$ DECLARE c CURSOR (p int) FOR SELECT i.id FROM items i; "
                            + "x int := 1; BEGIN RETURN x; END $$ LANGUAGE plpgsql",
                    "ALTER TABLE items ADD COLUMN c public.t",
                    "CREATE TABLE items2 (c public.t, d int)")) {
                accepted(PG, sql);
            }
        }

        @Test
        void oracleTypePositionsIgnoreAliasesAndRoutineItems() {
            for (String sql : List.of(
                    "UPDATE items other SET note = CAST(note AS other.t)",
                    "UPDATE items other SET note = TREAT(note AS other.t)",
                    "UPDATE items i SET note = TREAT(i.obj AS REF other.t)",
                    "SELECT 1 FROM items other WHERE other.obj IS OF (other.t)",
                    "SELECT 1 FROM items other WHERE other.obj IS OF TYPE (ONLY other.t)",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS other NUMBER; x other.t; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS other NUMBER; x other.t := NULL; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f(other NUMBER) RETURN other.t IS BEGIN RETURN NULL; END;",
                    "CREATE OR REPLACE FUNCTION f(other NUMBER, x other.t) RETURN NUMBER IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS TYPE r_t IS RECORD (a other.t); BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS other NUMBER; "
                            + "FUNCTION q(x other.t) RETURN NUMBER IS BEGIN RETURN 1; END; BEGIN RETURN 1; END;",
                    ORACLE_TRIGGER.replace("BEGIN ", "DECLARE other NUMBER; x other.t; BEGIN ") + "NULL; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
            for (String sql : List.of(
                    "UPDATE items i SET note = CAST(i.note AS VARCHAR2(20))",
                    "UPDATE items i SET note = CAST(i.note AS APP.t)",
                    "CREATE OR REPLACE FUNCTION f(p items%ROWTYPE) RETURN NUMBER IS v items.qty%TYPE; "
                            + "r items%ROWTYPE; BEGIN RETURN p.qty; END;",
                    "CREATE OR REPLACE FUNCTION f(p APP.t) RETURN APP.t IS x APP.t; BEGIN RETURN p; END;")) {
                accepted(ORACLE, sql);
            }
        }

        @Test
        void sqlServerTypePositionsIgnoreAliases() {
            for (String sql : List.of(
                    "SELECT CAST(note AS other.t) FROM items other",
                    "SELECT TRY_CAST(note AS other.t) FROM items other",
                    "SELECT CONVERT(other.t, note) FROM items other",
                    "SELECT TRY_CONVERT(other.t, note) FROM items other",
                    MSSQL_TRIGGER + "DECLARE @t other.tt; SELECT 1 FROM items other END",
                    MSSQL_TRIGGER + "DECLARE @t AS other.tt; SELECT 1 FROM items other END",
                    "ALTER TABLE items ADD c items.t")) {
                scopeRejects(MSSQL, sql, REJECTED);
            }
            for (String sql : List.of(
                    "SELECT CAST(i.note AS nvarchar(20)) FROM items i",
                    "SELECT CAST(i.note AS dbo.t) FROM items i",
                    "SELECT CONVERT(int, i.qty) FROM items i",
                    MSSQL_TRIGGER + "DECLARE @t dbo.tt; SELECT 1 FROM items i END")) {
                accepted(MSSQL, sql);
            }
        }

        @Test
        void oracleNestedSubprogramItemsAreScopedToTheirBody() {
            String nested = "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS "
                    + "FUNCTION q(r items%ROWTYPE) RETURN NUMBER IS n NUMBER := 0; BEGIN n := q.n; RETURN r.qty + n; "
                    + "END q; ";
            accepted(ORACLE, nested + "BEGIN RETURN 1; END;");
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS FUNCTION q(r items%ROWTYPE) "
                    + "RETURN NUMBER IS BEGIN RETURN r.note; END; BEGIN RETURN q(NULL); END;");
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS PROCEDURE p2(x NUMBER); "
                    + "FUNCTION q(r items%ROWTYPE) RETURN NUMBER IS BEGIN RETURN r.qty; END; "
                    + "PROCEDURE p2(x NUMBER) IS BEGIN NULL; END; BEGIN RETURN 1; END;");
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS v NUMBER := 1; "
                    + "PROCEDURE p2(r items%ROWTYPE) IS BEGIN UPDATE items SET qty = r.qty + v; END p2; "
                    + "BEGIN RETURN f.v; END;");
            scopeRejects(ORACLE, nested + "BEGIN RETURN r.qty; END;", REJECTED);
            scopeRejects(ORACLE, nested + "BEGIN RETURN n.qty; END;", REJECTED);
            scopeRejects(ORACLE, nested + "BEGIN RETURN q.n; END;", REJECTED);
            scopeRejects(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS FUNCTION q(r items%ROWTYPE) "
                    + "RETURN NUMBER IS BEGIN RETURN r.qty; END; "
                    + "FUNCTION z RETURN NUMBER IS BEGIN RETURN r.qty; END; BEGIN RETURN 1; END;", REJECTED);
        }

        @Test
        void schemaQualifiedColumnAnchorsCheckTheirSchema() {
            String function = "CREATE FUNCTION f() RETURNS int AS $$ DECLARE ";
            String end = " BEGIN RETURN 1; END $$ LANGUAGE plpgsql";
            for (String sql : List.of(
                    function + "n public.items.note%TYPE;" + end,
                    function + "n \"public\".\"items\".\"note\"%TYPE;" + end,
                    function + "n PUBLIC.items.note%TYPE; m items.note%TYPE; r public.items%ROWTYPE;" + end,
                    "CREATE FUNCTION f(p public.items.note%TYPE) RETURNS public.items.qty%TYPE AS $$ "
                            + "BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(public.items.note%TYPE) RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql")) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    function + "n other.items.note%TYPE;" + end,
                    function + "other int; n other.items.note%TYPE;" + end,
                    function + "n \"PUBLIC\".items.note%TYPE;" + end,
                    function + "r other.items%ROWTYPE;" + end,
                    "CREATE FUNCTION f(p other.items.note%TYPE) RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other int) RETURNS other.items.qty%TYPE AS $$ BEGIN RETURN 1; END $$ "
                            + "LANGUAGE plpgsql")) {
                scopeRejects(PG, sql, REJECTED);
            }
            scopeRejects(PG, function + "n db.public.items.note%TYPE;" + end, "database.schema.object");
            scopeRejects(PG, "SELECT 1 FROM db.public.items", "database.schema.object");

            String oracle = "CREATE OR REPLACE FUNCTION f(p APP.items.note%TYPE) RETURN APP.items.qty%TYPE IS ";
            for (String sql : List.of(
                    oracle + "v APP.items.note%TYPE; r APP.items%ROWTYPE; w items.note%TYPE; BEGIN RETURN 1; END;",
                    oracle + "v \"APP\".\"ITEMS\".\"NOTE\"%TYPE; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS TYPE a_t IS RECORD (c VARCHAR2(9)); "
                            + "TYPE h_t IS RECORD (a a_t); r h_t; x r.a.c%TYPE; y f.r.a.c%TYPE; "
                            + "BEGIN x := 'y'; RETURN 1; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    "CREATE OR REPLACE FUNCTION f(p OTHER.items.note%TYPE) RETURN NUMBER IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN OTHER.items.qty%TYPE IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS v OTHER.items.note%TYPE; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS r OTHER.items%ROWTYPE; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS x r.a.c%TYPE; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS "
                            + "FUNCTION q RETURN NUMBER IS TYPE a_t IS RECORD (c NUMBER); r a_t; BEGIN RETURN 1; END q; "
                            + "x r.c.d%TYPE; BEGIN RETURN 1; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
            scopeRejects(ORACLE, "UPDATE items SET note = 'x' WHERE r.a.c%TYPE IS NULL", REJECTED);
            scopeRejects(ORACLE, ORACLE_TRIGGER + "UPDATE items r SET note = CAST(note AS r.a.c%TYPE); END;", REJECTED);
        }

        @Test
        void routineParametersAreNotInScopeInTheirOwnHeader() {
            for (String sql : List.of(
                    "CREATE OR REPLACE FUNCTION f(other IN other.t.c%TYPE) RETURN NUMBER IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f(other IN NUMBER) RETURN other.t.c%TYPE IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f(other IN NUMBER, x other.t.c%TYPE) RETURN NUMBER IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION other RETURN other.t.c%TYPE IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f(other IN other.t%ROWTYPE) RETURN NUMBER IS BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS "
                            + "FUNCTION q(other IN other.t.c%TYPE) RETURN NUMBER IS BEGIN RETURN 1; END; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS "
                            + "FUNCTION q(other NUMBER) RETURN other.t.c%TYPE IS BEGIN RETURN 1; END; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS "
                            + "FUNCTION other RETURN other.t.c%TYPE IS BEGIN RETURN 1; END; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS PROCEDURE p2(other other.t.c%TYPE); "
                            + "PROCEDURE p2(other other.t.c%TYPE) IS BEGIN NULL; END; BEGIN RETURN 1; END;",
                    ORACLE_TRIGGER.replace("BEGIN ", "DECLARE FUNCTION q(other other.t.c%TYPE) RETURN NUMBER IS "
                            + "BEGIN RETURN 1; END; BEGIN ") + "NULL; END;")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
            for (String sql : List.of(
                    "CREATE OR REPLACE FUNCTION f(APP IN APP.items.note%TYPE) RETURN APP.items.qty%TYPE IS "
                            + "BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f(o IN APP.addr_t) RETURN NUMBER IS x o.city.name%TYPE; BEGIN RETURN 1; END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS TYPE a_t IS RECORD (c NUMBER); "
                            + "TYPE h_t IS RECORD (a a_t); r h_t; "
                            + "FUNCTION q(p r.a.c%TYPE) RETURN r.a.c%TYPE IS BEGIN RETURN p; END; BEGIN RETURN q(1); END;",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS TYPE a_t IS RECORD (c NUMBER); "
                            + "TYPE h_t IS RECORD (a a_t); "
                            + "FUNCTION q(r h_t) RETURN NUMBER IS x r.a.c%TYPE; BEGIN RETURN r.a.c; END; "
                            + "BEGIN RETURN 1; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    "CREATE FUNCTION f(other int, x other.items.note%TYPE) RETURNS int AS $$ BEGIN RETURN 1; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION f(other int) RETURNS other.items.note%TYPE AS $$ BEGIN RETURN NULL; END $$ "
                            + "LANGUAGE plpgsql",
                    "CREATE FUNCTION other() RETURNS other.items.note%TYPE AS $$ BEGIN RETURN NULL; END $$ "
                            + "LANGUAGE plpgsql")) {
                scopeRejects(PG, sql, REJECTED);
            }
        }

        @Test
        void oracleAliasQualifiesColumnAttributesAndMethods() {
            for (String sql : List.of(
                    "UPDATE items i SET note = i.doc.getStringVal()",
                    "UPDATE items i SET note = i.addr.city",
                    "UPDATE items system SET note = system.doc.getStringVal()",
                    "UPDATE items AS i SET note = i.addr.city WHERE i.addr.zip.code IS NOT NULL",
                    "SELECT i.addr.city, i.doc.extract('/a').getStringVal() FROM items i",
                    "INSERT INTO tags (name) SELECT t.addr.city FROM items t",
                    "UPDATE items SET note = (SELECT t.doc.getStringVal() FROM items t WHERE ROWNUM = 1)",
                    ORACLE_TRIGGER + "UPDATE items i SET note = i.addr.city WHERE i.id = :NEW.id; END;")) {
                accepted(ORACLE, sql);
            }
            scopeAccepts(ORACLE, "MERGE INTO items i USING staged s ON (i.id = s.id) "
                    + "WHEN MATCHED THEN UPDATE SET i.note = s.addr.city");
            for (String sql : List.of(
                    "UPDATE items SET note = items.doc.getStringVal()",
                    "UPDATE items SET note = items.addr.city",
                    "UPDATE items i SET note = other.doc.getStringVal()",
                    "SELECT i.addr.city FROM items x",
                    "UPDATE items SET note = (SELECT 'x' FROM tags i) || i.addr.city",
                    "SELECT i.addr.city FROM items i UNION SELECT i.addr.city FROM tags",
                    "SELECT 1 FROM items i JOIN i.addr.x ON 1 = 1",
                    "INSERT INTO i.addr.city SELECT 1 FROM items i")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
        }

        @Test
        void oracleSuppliedNamesOnlyInExpressionAndTypePositions() {
            for (String sql : List.of(
                    "INSERT INTO SYS.ODCINUMBERLIST VALUES (1)",
                    "SELECT 1 FROM SYS.XMLTYPE",
                    "SELECT 1 FROM items, SYS.ODCIVARCHAR2LIST",
                    "SELECT 1 FROM items i JOIN SYS.XMLTYPE x ON 1 = 1",
                    "UPDATE SYS.XMLTYPE SET x = 1",
                    "SELECT 1 FROM DBMS_RANDOM.VALUE",
                    "INSERT INTO DBMS_OUTPUT.x VALUES (1)",
                    "SELECT 1 FROM XMLTYPE.CREATEXML",
                    "SELECT SYS.DUAL.DUMMY FROM dual",
                    "UPDATE items SET note = SYS.ANYDATA.ConvertNumber(1)",
                    "SELECT 1 FROM SYS.ANYDATA")) {
                scopeRejects(ORACLE, sql, REJECTED);
            }
            for (String sql : List.of(
                    "UPDATE SYS.DUAL SET dummy = 'Y'",
                    "INSERT INTO SYS.DUAL VALUES ('Y')",
                    "MERGE INTO SYS.DUAL d USING items i ON (1 = 1) WHEN MATCHED THEN UPDATE SET d.dummy = 'Y'",
                    "COMMENT ON TABLE SYS.DUAL IS 'x'",
                    "CREATE INDEX i ON SYS.DUAL (dummy)",
                    "LOCK TABLE SYS.DUAL IN SHARE MODE",
                    "UPDATE items SET note = SYS.DUAL")) {
                rejected(ORACLE, sql);
            }
            for (String sql : List.of(
                    "SELECT 1 FROM SYS.DUAL",
                    "SELECT dual.dummy FROM sys.dual",
                    "UPDATE items SET qty = (SELECT 1 FROM SYS.DUAL)",
                    "INSERT INTO items (id) SELECT 1 FROM SYS.DUAL",
                    "SELECT 1 FROM items, SYS.DUAL",
                    "SELECT 1 FROM items i JOIN SYS.DUAL d ON 1 = 1",
                    "SELECT column_value FROM TABLE(SYS.ODCINUMBERLIST(1))",
                    "SELECT 1 FROM items i, TABLE(SYS.ODCIVARCHAR2LIST('a')) t",
                    "UPDATE items SET note = DBMS_RANDOM.STRING('x', 5)")) {
                accepted(ORACLE, sql);
            }
        }

        @Test
        void postgresDeclarationDefaultsEndTheType() {
            String function = "CREATE FUNCTION f(p items) RETURNS int AS $$ DECLARE ";
            String end = " BEGIN RETURN 1; END $$ LANGUAGE plpgsql";
            for (String declaration : List.of(
                    "q int = p.qty;",
                    "q int := p.qty;",
                    "q int DEFAULT p.qty;",
                    "q CONSTANT int = p.qty;",
                    "q CONSTANT int := p.qty;",
                    "q int NOT NULL = p.qty;",
                    "q int NOT NULL := p.qty;",
                    "s text COLLATE \"C\" = p.note;",
                    "s text COLLATE pg_catalog.\"C\" NOT NULL DEFAULT p.note;",
                    "q public.items = p;",
                    "q int = p.qty; s text = q::text;")) {
                accepted(PG, function + declaration + end);
            }
            for (String declaration : List.of(
                    "q other.t = p.qty;",
                    "other int; q other.t = 1;",
                    "q CONSTANT other.t = 1;",
                    "s text COLLATE other.\"C\" = p.note;",
                    "q int = x.qty;",
                    "q int = other.f(1);",
                    "q int = p.qty::other.t;")) {
                scopeRejects(PG, function + declaration + end, REJECTED);
            }
        }

        @Test
        void oracleSuppliedSysDataTypes() {
            for (String sql : List.of(
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCINUMBERLIST(1, 2)))",
                    "INSERT INTO items (note) SELECT column_value FROM TABLE(SYS.ODCIVARCHAR2LIST('a', 'b'))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCIDATELIST(SYSDATE)))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(sys.odcirawlist(HEXTORAW('00'))))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCIGRANULELIST(1)))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCIRIDLIST('x')))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(ODCINUMBERLIST(1)))",
                    "UPDATE items SET note = SYS.XMLTYPE('<a/>').getStringVal()",
                    "UPDATE items SET note = XMLTYPE.createXML('<a/>').getStringVal()",
                    "UPDATE items SET note = SYS.XMLTYPE.createXML('<a/>').getStringVal()",
                    "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS v SYS.ODCINUMBERLIST := SYS.ODCINUMBERLIST(1, 2); "
                            + "BEGIN RETURN v.COUNT; END;")) {
                accepted(ORACLE, sql);
            }
            for (String sql : List.of(
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCIBFILELIST()))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCIOBJECTLIST()))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCICOLVALLIST()))",
                    "UPDATE items SET note = SYS.HTTPURITYPE('example.com').getClob()",
                    "UPDATE items SET note = SYS.XMLTYPE.NOT_A_MEMBER('<a/>')",
                    "UPDATE items SET note = XMLTYPE.NOT_A_MEMBER('<a/>')",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(OTHER.ODCINUMBERLIST(1)))",
                    "UPDATE items SET qty = (SELECT COUNT(*) FROM TABLE(SYS.ODCINUMBERLIST.x(1)))",
                    "UPDATE items SET note = SYS.ODCINUMBERLIST.ODCIGRANULELIST(1)")) {
                rejected(ORACLE, sql);
            }
            rejected(ORACLE, "UPDATE items SET note = SYS.XMLTYPE(BFILENAME('D', 'f.xml'), 0).getStringVal()");
        }

        @Test
        void oracleDbmsLobConstantsAndExceptionsAreAllowed() {
            for (String constant : List.of("LOB_READONLY", "LOB_READWRITE", "FILE_READONLY", "LOBMAXSIZE",
                    "CALL", "SESSION", "TRANSACTION", "DEFAULT_CSID", "NO_WARNING")) {
                accepted(ORACLE, ORACLE_TRIGGER.replace("BEGIN ", "DECLARE m NUMBER := DBMS_LOB." + constant
                        + "; BEGIN ") + "NULL; END;");
            }
            accepted(ORACLE, ORACLE_TRIGGER + "NULL; EXCEPTION WHEN DBMS_LOB.INVALID_ARGVAL THEN NULL; END;");
            accepted(ORACLE, ORACLE_TRIGGER + "NULL; EXCEPTION WHEN DBMS_LOB.ACCESS_ERROR THEN NULL; END;");
            scopeRejects(ORACLE, ORACLE_TRIGGER.replace("BEGIN ", "DECLARE m NUMBER := DBMS_LOB.NOT_A_MEMBER; BEGIN ")
                    + "NULL; END;", REJECTED);
        }
    }

    /**
     * PL/SQL and PL/pgSQL items a routine declares — parameters, variables, loop records, labels,
     * compound-trigger sections — qualify columns, fields, and collection methods without naming a
     * schema.
     */
    @Nested
    class RoutineItemQualifiers {

        private static final String REJECTED = "targets namespace";

        private static final String COLLECTION_FUNCTION =
                "CREATE OR REPLACE FUNCTION f(p t_ids) RETURN NUMBER IS TYPE t_ids IS TABLE OF NUMBER; "
                        + "t t_ids := t_ids(1, 2); i PLS_INTEGER; BEGIN ";

        @Test
        void oracleCollectionMethodsOnDeclaredItems() {
            for (String body : List.of(
                    "IF t.EXISTS(1) THEN t.DELETE(1); END IF;",
                    "t.DELETE;",
                    "t.DELETE(1, 2);",
                    "t.EXTEND;",
                    "t.EXTEND(2);",
                    "t.EXTEND(2, 1);",
                    "t.TRIM;",
                    "t.TRIM(1);",
                    "i := t.FIRST; WHILE i IS NOT NULL LOOP i := t.NEXT(i); END LOOP;",
                    "i := t.LAST; i := t.PRIOR(i);",
                    "i := t.COUNT + t.LIMIT;",
                    "i := p.COUNT; p.DELETE(1);",
                    "FOR r IN (SELECT id FROM items) LOOP t.EXTEND; t(t.LAST) := r.id; END LOOP;",
                    "UPDATE items SET qty = t.COUNT;",
                    "FORALL j IN 1..t.COUNT UPDATE items SET qty = 1 WHERE id = t(j);")) {
                accepted(ORACLE, COLLECTION_FUNCTION + body + " RETURN 1; END;");
            }
            for (String body : List.of(
                    "other.DELETE(1);",
                    "other.EXTEND(1);",
                    "i := other.pkg.COUNT;",
                    "UPDATE items SET qty = t.NEXT(1);",
                    "i := (SELECT t.NEXT(1) FROM dual);",
                    "UPDATE items SET qty = CASE WHEN 1 = 1 THEN t.NEXT(1) END;")) {
                scopeRejects(ORACLE, COLLECTION_FUNCTION + body + " RETURN 1; END;", REJECTED);
            }
            policyRejects(ORACLE, COLLECTION_FUNCTION + "DELETE FROM items; RETURN 1; END;", DESTRUCTIVE);
            policyRejects(ORACLE, COLLECTION_FUNCTION + "DELETE items; RETURN 1; END;", DESTRUCTIVE);
            policyRejects(ORACLE, COLLECTION_FUNCTION + "t . DELETE FROM items; RETURN 1; END;", DESTRUCTIVE);
        }

        @Test
        void oracleCompoundTriggerDeclarationsReachEveryTimingPoint() {
            String trigger = "CREATE OR REPLACE TRIGGER trg FOR INSERT OR UPDATE ON items COMPOUND TRIGGER "
                    + "TYPE t_ids IS TABLE OF NUMBER; g t_ids := t_ids(); v items%ROWTYPE; "
                    + "PROCEDURE flush IS BEGIN FOR i IN 1..g.COUNT LOOP UPDATE items SET qty = 1 WHERE id = g(i); "
                    + "END LOOP; g.DELETE; END flush; "
                    + "BEFORE STATEMENT IS BEGIN g.DELETE; END BEFORE STATEMENT; "
                    + "AFTER EACH ROW IS n NUMBER := 0; BEGIN g.EXTEND; g(g.LAST) := :NEW.id; v.id := n; "
                    + "END AFTER EACH ROW; "
                    + "AFTER STATEMENT IS BEGIN IF g.EXISTS(1) THEN v.note := 'x'; END IF; "
                    + "FOR i IN 1..g.COUNT LOOP UPDATE items SET qty = 1 WHERE id = g(i); END LOOP; "
                    + "END AFTER STATEMENT; END trg;";
            accepted(ORACLE, trigger);
            scopeRejects(ORACLE, trigger.replace("g.DELETE; END BEFORE", "other.DELETE; END BEFORE"), REJECTED);
            scopeRejects(ORACLE, trigger.replace("v.note := 'x'", "n.note := 'x'"), REJECTED);
            scopeRejects(ORACLE, trigger.replace("UPDATE items SET qty = 1 WHERE id = g(i); END LOOP; END AFTER",
                    "UPDATE other.items SET qty = 1 WHERE id = g(i); END LOOP; END AFTER"), REJECTED);
        }

        @Test
        void oracleLabelsQualifyOnlyInsideTheirBlock() {
            String function = "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS BEGIN "
                    + "<<outer>> DECLARE n NUMBER := 1; BEGIN DECLARE n NUMBER := 2; BEGIN "
                    + "n := outer.n; END; END; ";
            accepted(ORACLE, function + "RETURN 1; END;");
            scopeRejects(ORACLE, function + "RETURN outer.n; END;", REJECTED);
            accepted(ORACLE, "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS n NUMBER := 0; BEGIN "
                    + "<<l>> FOR i IN 1..3 LOOP n := n + l.i; END LOOP l; RETURN f.n; END;");
        }

        @Test
        void oracleNestedRecordFields() {
            String function = "CREATE OR REPLACE FUNCTION f RETURN VARCHAR2 IS TYPE a_t IS RECORD (city VARCHAR2(9)); "
                    + "TYPE h_t IS RECORD (addr a_t); h h_t; BEGIN ";
            accepted(ORACLE, function + "h.addr.city := 'x'; RETURN h.addr.city; END;");
            accepted(ORACLE, function + "h.addr.city := 'x'; RETURN f.h.addr.city; END;");
            accepted(ORACLE, function + "UPDATE items SET note = h.addr.city; RETURN 'x'; END;");
            accepted(ORACLE, function + "UPDATE items i SET note = h.addr.city WHERE i.note = h.addr.city; "
                    + "RETURN 'x'; END;");
            scopeRejects(ORACLE, function + "UPDATE items SET note = h.addr.upper(1); RETURN 'x'; END;", REJECTED);
            scopeRejects(ORACLE, function + "UPDATE items SET note = g.addr.city; RETURN 'x'; END;", REJECTED);
            scopeRejects(ORACLE, function + "RETURN other.addr.city; END;", REJECTED);
        }

        @Test
        void postgresLabelsAndRecordFields() {
            for (String sql : List.of(
                    "CREATE FUNCTION f() RETURNS void AS $$ <<blk>> DECLARE n int := 1; BEGIN "
                            + "DECLARE n int := 2; BEGIN UPDATE items SET qty = blk.n; END; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f() RETURNS void AS $$ <<blk>> DECLARE r record; BEGIN "
                            + "SELECT id, note INTO r FROM items LIMIT 1; UPDATE items SET note = blk.r.note; "
                            + "END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r items) RETURNS text AS $$ BEGIN RETURN r.note; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r items) RETURNS text AS $$ BEGIN RETURN f.r.note; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r holder) RETURNS text AS $$ BEGIN RETURN (r.addr).city; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(n int) RETURNS int AS $$ BEGIN RETURN f.n; END $$ LANGUAGE plpgsql",
                    PG_FUNCTION + "<<l>> FOR i IN 1..3 LOOP UPDATE items SET qty = l.i; END LOOP; " + PG_FUNCTION_END)) {
                accepted(PG, sql);
            }
            for (String sql : List.of(
                    "CREATE FUNCTION f() RETURNS void AS $$ BEGIN UPDATE items SET qty = blk.n; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r items) RETURNS text AS $$ BEGIN RETURN other.a.b; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r holder) RETURNS text AS $$ BEGIN RETURN r.addr.city; END $$ LANGUAGE plpgsql",
                    "CREATE FUNCTION f(r items) RETURNS int AS $$ BEGIN RETURN r.f(1); END $$ LANGUAGE plpgsql")) {
                scopeRejects(PG, sql, REJECTED);
            }
            scopeRejects(PG, "CREATE FUNCTION f(r items) RETURNS int AS $$ BEGIN RETURN r.t.f(1); END $$ LANGUAGE plpgsql",
                    THREE_PART);
            scopeRejects(PG, "CREATE FUNCTION f(r items) RETURNS int AS $$ <<blk>> BEGIN RETURN blk.r.f(1); END $$ "
                    + "LANGUAGE plpgsql", THREE_PART);
        }
    }

    /** Documented 1.3.0 limitation: these object types are created outside change sets (docs/ROADMAP.md). */
    @Nested
    class ObjectTypesNotYetSupported {

        @Test
        void sequencesViewsProceduresPackagesAndTypesAreRejected() {
            for (DatabaseDialect dialect : List.of(PG, MYSQL, MARIADB, MSSQL, ORACLE)) {
                rejected(dialect, "CREATE SEQUENCE items_seq");
                rejected(dialect, "CREATE VIEW items_v AS SELECT id FROM items");
                rejected(dialect, "CREATE PROCEDURE p() BEGIN UPDATE items SET qty = 1; END");
            }
            rejected(PG, "CREATE OR REPLACE VIEW items_v AS SELECT id FROM items");
            rejected(PG, "CREATE OR REPLACE PROCEDURE p() LANGUAGE sql AS 'UPDATE items SET qty = 1'");
            rejected(MSSQL, "CREATE OR ALTER VIEW dbo.items_v AS SELECT id FROM dbo.items");
            rejected(MSSQL, "CREATE PROCEDURE dbo.p AS UPDATE dbo.items SET qty = 1");
            rejected(ORACLE, "CREATE OR REPLACE PACKAGE util_pkg AS FUNCTION f RETURN NUMBER; END util_pkg;");
            rejected(ORACLE, "CREATE OR REPLACE TYPE item_t AS OBJECT (id NUMBER)");
            rejected(MARIADB, "CREATE SEQUENCE IF NOT EXISTS items_seq");
        }
    }
}
