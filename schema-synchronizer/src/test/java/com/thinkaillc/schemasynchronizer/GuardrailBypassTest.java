// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Counterexamples from hostile review: each input once passed both the non-destructive
 * policy and schema scope binding under the listed dialect. Every one must now be rejected,
 * and the legitimate neighbours must still pass.
 */
class GuardrailBypassTest {

    private static void assertRejected(String sql, String namespace, DatabaseDialect dialect) {
        assertThatThrownBy(() -> changeSet(sql, namespace, dialect))
                .as("%s: %s", dialect, sql)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertAccepted(String sql, String namespace, DatabaseDialect dialect) {
        assertThatCode(() -> changeSet(sql, namespace, dialect))
                .as("%s: %s", dialect, sql)
                .doesNotThrowAnyException();
    }

    private static void changeSet(String sql, String namespace, DatabaseDialect dialect) {
        NonDestructiveSqlPolicy.requireSafe(sql, dialect);
        ChangeSetSchemaScope.requireScoped(sql, namespace, dialect);
    }

    @Test
    void lexerFollowsEachDialectsStringAndCommentRules() {
        assertRejected("UPDATE items SET note = E'\\'' || (SELECT secret FROM other.accounts LIMIT 1) || 'x'",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE items SET note = (SELECT '\\'' FROM dual) , note2 = (SELECT s FROM otherdb.t)",
                "appdb", DatabaseDialect.MYSQL);
        assertRejected("UPDATE items SET note = 'a\\'b'", "appdb", DatabaseDialect.MARIADB);
        assertRejected("UPDATE items SET cnt = cnt --1, note = (SELECT secret FROM otherdb.accounts LIMIT 1)",
                "appdb", DatabaseDialect.MYSQL);
        assertRejected("UPDATE items SET note='x' /*!, note2=(SELECT secret FROM otherdb.accounts LIMIT 1) */",
                "appdb", DatabaseDialect.MYSQL);
        assertRejected("UPDATE items SET note='x' /*M!, note2=(SELECT 1 FROM otherdb.t) */",
                "appdb", DatabaseDialect.MARIADB);
        assertRejected("UPDATE items SET note = 'x' # it's\n, note2 = (SELECT s FROM otherdb.t)",
                "appdb", DatabaseDialect.MYSQL);
        assertRejected("UPDATE items SET note = 'x' /* /* */ ' */ , note2 = (SELECT s FROM other.t)",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE items SET note = 'open", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void quotesInsideQuotedIdentifiersDoNotOpenStrings() {
        assertRejected("UPDATE items AS \"a'\" SET note = (SELECT s FROM other.accounts LIMIT 1) WHERE note <> 'z'",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE [a'] SET note = (SELECT s FROM other.accounts) WHERE note <> 'z'",
                "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("UPDATE `a'` SET note = (SELECT s FROM otherdb.accounts) WHERE note <> 'z'",
                "appdb", DatabaseDialect.MYSQL);
    }

    @Test
    void oracleQQuotesMaskTheirContents() {
        assertAccepted("UPDATE items SET note = q'[it's other.t]'", "APP", DatabaseDialect.ORACLE);
        assertRejected("UPDATE items SET note = q'[it's]' || (SELECT s FROM other.t)", "APP", DatabaseDialect.ORACLE);
    }

    @Test
    void qualifiedNamesMatchWholeTokens() {
        assertRejected("UPDATE EVIL$APP.items SET note = 'x'", "APP", DatabaseDialect.ORACLE);
        assertRejected("UPDATE 1appdb.items SET note = 'x'", "appdb", DatabaseDialect.MYSQL);
        assertRejected("UPDATE épublic.items SET note = 'x'", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE otherdb..accounts SET balance = 0", "dbo", DatabaseDialect.SQLSERVER);
        assertAccepted("UPDATE items SET price = 1.5 WHERE price < 2.25", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void grantsMustBeScopedObjectGrants() {
        assertRejected("GRANT ALL PRIVILEGES ON*.* TO 'app'@'%'", "appdb", DatabaseDialect.MYSQL);
        assertRejected("GRANT ALL ON`otherdb`.* TO u", "appdb", DatabaseDialect.MYSQL);
        assertRejected("GRANT ALL ON evil$appdb.* TO u", "appdb", DatabaseDialect.MYSQL);
        assertRejected("GRANT ALL ON app_db.* TO u", "app_db", DatabaseDialect.MYSQL);
        assertAccepted("GRANT SELECT ON `app\\_db`.* TO u", "app_db", DatabaseDialect.MYSQL);
        assertRejected("GRANT pg_write_server_files TO app_user", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("GRANT CONTROL SERVER TO app", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("GRANT IMPERSONATE ON LOGIN::sa TO app", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("GRANT DBA TO app", "APP", DatabaseDialect.ORACLE);
        assertRejected("GRANT PROXY ON 'root'@'%' TO 'app'@'%'", "appdb", DatabaseDialect.MYSQL);
        assertAccepted("GRANT SELECT, DELETE ON items TO reader", "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("GRANT EXECUTE ON FUNCTION public.f() TO reader", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void alterTableActionsMustAllBeAdditive() {
        assertRejected("ALTER TABLE items ADD COLUMN x int, ALTER note TYPE int USING 0",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("ALTER TABLE items ADD COLUMN x INT, ENGINE=BLACKHOLE", "appdb", DatabaseDialect.MYSQL);
        assertRejected("ALTER TABLE items ADD COLUMN x INT, MODIFY note TINYINT", "appdb", DatabaseDialect.MYSQL);
        assertRejected("ALTER TABLE items ADD COLUMN x INT, RENAME TO items_old", "appdb", DatabaseDialect.MYSQL);
        assertRejected("ALTER TABLE items MODIFY (note VARCHAR2(1) DEFAULT 'x')", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items MODIFY (note DEFAULT 'x')", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items ADD COLUMN x INT, ALGORITHM=INPLACE", "appdb", DatabaseDialect.MYSQL);
        assertAccepted("ALTER TABLE items ADD COLUMN amount NUMERIC(10,2), ADD COLUMN type VARCHAR(10)",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("ALTER TABLE child ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) "
                + "REFERENCES parent(id) ON DELETE CASCADE", "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("ALTER TABLE items ALTER COLUMN note SET DEFAULT 'x'", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void routineBodiesRejectDestructiveAndDynamicSql() {
        assertRejected("CREATE TRIGGER t BEFORE INSERT ON items FOR EACH ROW BEGIN SET @x = '\\''; "
                + "DELETE FROM accounts; SET @y = 'z'; END", "appdb", DatabaseDialect.MYSQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE sql AS E'SELECT \\'\\'; DELETE FROM accounts'",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE TRIGGER trg ON items AFTER INSERT AS DELETE accounts", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("CREATE TRIGGER t BEFORE INSERT ON items FOR EACH ROW DELETE a FROM accounts a",
                "appdb", DatabaseDialect.MYSQL);
        assertRejected("CREATE TRIGGER trg ON items AFTER INSERT AS EXEC('DROP TABLE otherdb.dbo.accounts')",
                "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN DROP OWNED BY app CASCADE; END $$",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN EXECUTE format('x'); END $$",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("INSERT INTO items SELECT * FROM OPENQUERY(lnk, 'SELECT 1')", "dbo", DatabaseDialect.SQLSERVER);
        assertAccepted("CREATE TRIGGER t AFTER INSERT OR DELETE ON items FOR EACH ROW EXECUTE FUNCTION audit()",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("CREATE OR REPLACE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN NEW.updated_at := now(); RETURN NEW; END $$", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void quotedSetConfigIsASessionMutator() {
        assertRejected("SELECT \"set_config\"('search_path', 'evil', false)", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("SELECT \"pg_catalog\".\"set_config\"('search_path', 'evil', false)",
                "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void oracleSysIsNotASystemCatalogExemption() {
        assertRejected("UPDATE sys.items SET note = 'x'", "APP", DatabaseDialect.ORACLE);
        assertAccepted("SELECT name FROM sys.tables", "dbo", DatabaseDialect.SQLSERVER);
    }

    @Test
    void declarativeTargetsFollowDialectCaseFolding() {
        assertThatCode(() -> ChangeSetSchemaScope.sameNamespace("appdb", "AppDB", DatabaseDialect.MYSQL))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(
                ChangeSetSchemaScope.sameNamespace("appdb", "AppDB", DatabaseDialect.MYSQL)).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                ChangeSetSchemaScope.sameNamespace("public", "PUBLIC", DatabaseDialect.POSTGRESQL)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                ChangeSetSchemaScope.sameNamespace("APP", "app", DatabaseDialect.ORACLE)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                ChangeSetSchemaScope.sameNamespace("Sales", "sales", DatabaseDialect.SQLSERVER)).isFalse();
    }

    @Test
    void oracleClausesChainedAfterAddOrModifyAreRejected() {
        assertRejected("ALTER TABLE items ADD (c NUMBER) SET UNUSED (price)", "APP", DatabaseDialect.ORACLE);
        assertRejected("ALTER TABLE items ADD (c NUMBER) MODIFY (price NUMBER(2))", "APP", DatabaseDialect.ORACLE);
        assertRejected("ALTER TABLE items ADD (c NUMBER) RENAME COLUMN a TO b", "APP", DatabaseDialect.ORACLE);
        assertRejected("ALTER TABLE items MODIFY (a DEFAULT 1) SET UNUSED (b)", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items ADD (c NUMBER)", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items MODIFY (a DEFAULT 1, b NULL)", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items ADD CONSTRAINT fk_o FOREIGN KEY (o) REFERENCES orders (id) "
                + "ON DELETE SET NULL", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE items ADD CONSTRAINT fk_o FOREIGN KEY (o) REFERENCES orders (id) "
                + "ON DELETE CASCADE", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void serverDatabaseAndSystemEventTriggersAreRejected() {
        assertRejected("CREATE TRIGGER t ON ALL SERVER FOR LOGON AS ROLLBACK", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("CREATE TRIGGER t ON DATABASE FOR CREATE_TABLE AS PRINT 1", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("CREATE OR REPLACE TRIGGER t AFTER LOGON ON SCHEMA CALL audit_login",
                "APP", DatabaseDialect.ORACLE);
        assertRejected("CREATE OR REPLACE TRIGGER t AFTER STARTUP ON DATABASE CALL warm_up",
                "APP", DatabaseDialect.ORACLE);
        assertAccepted("CREATE OR REPLACE TRIGGER t BEFORE INSERT ON items FOR EACH ROW CALL audit_row",
                "APP", DatabaseDialect.ORACLE);
        assertAccepted("CREATE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW EXECUTE FUNCTION chk()",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON items FOR EACH ROW EXECUTE FUNCTION chk()",
                "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void crossDatabaseNamesAndLinksAreRejected() {
        assertRejected("UPDATE items SET note = (SELECT s FROM secrets@remote)", "APP", DatabaseDialect.ORACLE);
        assertRejected("UPDATE items SET note = (SELECT s FROM \"SECRETS\"@remote)", "APP", DatabaseDialect.ORACLE);
        assertRejected("UPDATE items SET note = (SELECT s FROM otherdb.dbo.accounts)", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("UPDATE items SET note = (SELECT s FROM otherdb.public.accounts)",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("UPDATE items SET note = 'mail@example.com'", "APP", DatabaseDialect.ORACLE);
    }

    @Test
    void systemCatalogsAreReadableButNotWriteTargets() {
        assertRejected("UPDATE pg_catalog.pg_class SET relname = 'x' WHERE oid = 1",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("INSERT INTO information_schema.t VALUES (1)", "appdb", DatabaseDialect.MYSQL);
        assertRejected("CREATE TABLE pg_catalog.evil (id int)", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE TRIGGER trg AFTER INSERT ON sys.objects AS PRINT 1", "dbo", DatabaseDialect.SQLSERVER);
        assertAccepted("UPDATE items SET cnt = (SELECT count(*) FROM pg_catalog.pg_class)",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("ALTER TABLE items ADD COLUMN note pg_catalog.text", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void routineBodyLiteralsAreScannedSeparatelyAsCode() {
        String raiseMessage = "CREATE FUNCTION chk() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.qty < 0 THEN RAISE EXCEPTION 'Quantity must be positive. Got %', NEW.qty; END IF; "
                + "-- use NEW values only\n RETURN NEW; END $$";
        assertAccepted(raiseMessage, "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("CREATE FUNCTION chk() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'cannot drop a shipped order'; END $$", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE sql AS 'DELETE FROM items'",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN "
                + "INSERT INTO other.t VALUES (1); END $$", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN "
                + "PERFORM 1; $q$ x $q$; DELETE FROM items; END $$", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN "
                + "PERFORM set_config('search_path', 'other', true); END $$", "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void catalogWriteTargetsAreFoundBehindModifiers() {
        assertRejected("CREATE TABLE IF NOT EXISTS information_schema.audit_x (id int)",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("ALTER TABLE IF EXISTS information_schema.sql_features ADD COLUMN x int",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE ONLY information_schema.sql_features SET comments = 'x'",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("GRANT SELECT ON ALL TABLES IN SCHEMA information_schema TO app_reader",
                "public", DatabaseDialect.POSTGRESQL);
        assertRejected("UPDATE TOP (1) sys.objects SET name = 'x'", "dbo", DatabaseDialect.SQLSERVER);
    }

    @Test
    void qualifiedOracleSchemaTriggersAreRejected() {
        assertRejected("CREATE TRIGGER trg AFTER DDL ON app.SCHEMA CALL app_p", "APP", DatabaseDialect.ORACLE);
        assertRejected("CREATE TRIGGER trg AFTER DDL ON \"APP\".SCHEMA CALL app_p", "APP", DatabaseDialect.ORACLE);
        assertRejected("CREATE TRIGGER trg AFTER DDL ON PLUGGABLE DATABASE CALL app_p", "APP", DatabaseDialect.ORACLE);
        assertRejected("CREATE TRIGGER trg AFTER CLONE ON PLUGGABLE DATABASE CALL app_p", "APP",
                DatabaseDialect.ORACLE);
        assertAccepted("CREATE TRIGGER trg BEFORE UPDATE ON items FOR EACH ROW "
                + "EXECUTE FUNCTION pg_catalog.suppress_redundant_updates_trigger()", "public",
                DatabaseDialect.POSTGRESQL);
    }

    @Test
    void routineBodiesMustNotRunDdl() {
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN "
                + "ALTER TABLE items RENAME COLUMN a TO b; END $$", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("CREATE TRIGGER trg ON t AFTER INSERT AS BEGIN ALTER TABLE t2 ALTER COLUMN c VARCHAR(1) END",
                "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("CREATE FUNCTION f() RETURNS void LANGUAGE sql AS 'GRANT SELECT ON items TO PUBLIC'",
                "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("CREATE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "NEW.updated_at := now(); RETURN NEW; END $$", "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("CREATE OR REPLACE FUNCTION f() RETURNS TABLE (id int) LANGUAGE sql AS $$ SELECT 1 $$",
                "public", DatabaseDialect.POSTGRESQL);
    }

    @Test
    void commentOnColumnUsesTableColumnNotSchemaObject() {
        assertAccepted("COMMENT ON COLUMN items.note IS 'x'", "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("COMMENT ON COLUMN public.items.note IS 'x'", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("COMMENT ON COLUMN other.items.note IS 'x'", "public", DatabaseDialect.POSTGRESQL);
        assertAccepted("COMMENT ON COLUMN items.note IS 'x'", "APP", DatabaseDialect.ORACLE);
    }

    @Test
    void grantsAndAlterClausesThatReachOutsideTheNamespaceAreRejected() {
        assertRejected("GRANT READ, WRITE ON DIRECTORY data_pump_dir TO u", "APP", DatabaseDialect.ORACLE);
        assertRejected("GRANT EXECUTE ON UTL_FILE TO u", "APP", DatabaseDialect.ORACLE);
        assertRejected("GRANT EXECUTE ON sys.dbms_scheduler TO u", "APP", DatabaseDialect.ORACLE);
        assertRejected("GRANT CONTROL ON ASSEMBLY::a TO u", "dbo", DatabaseDialect.SQLSERVER);
        assertRejected("ALTER TABLE t ADD (c NUMBER) DISABLE ALL TRIGGERS", "APP", DatabaseDialect.ORACLE);
        assertRejected("ALTER TABLE t ADD (c NUMBER) DISABLE PRIMARY KEY CASCADE", "APP", DatabaseDialect.ORACLE);
        assertAccepted("ALTER TABLE t ADD CONSTRAINT ck_c CHECK (c > 0) DISABLE", "APP", DatabaseDialect.ORACLE);
        assertAccepted("GRANT SELECT ON items TO app_reader", "APP", DatabaseDialect.ORACLE);
    }

    @Test
    void adminFunctionsAreRejectedInChangeSets() {
        assertRejected("SELECT pg_terminate_backend(pid) FROM pg_stat_activity", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("SELECT pg_drop_replication_slot('x')", "public", DatabaseDialect.POSTGRESQL);
        assertRejected("SELECT 1 INTO OUTFILE '/tmp/x'", "appdb", DatabaseDialect.MYSQL);
        assertAccepted("SELECT setval('items_id_seq', (SELECT max(id) FROM items))", "public",
                DatabaseDialect.POSTGRESQL);
    }

    @Test
    void verificationRejectsQuotedAndStandardSequenceAdvance() {
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT \"nextval\"('s') > 0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT CASE WHEN NEXT VALUE FOR dbo.seq > 0 THEN 1 ELSE 0 END", DatabaseDialect.SQLSERVER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mysqlDoubleQuotedTextIsAStringUnlessQualified() {
        assertAccepted("UPDATE settings SET url = \"https://example.com\" WHERE id = 1", "appdb",
                DatabaseDialect.MYSQL);
        assertRejected("UPDATE items SET note = (SELECT s FROM \"otherdb\".\"t\")", "appdb", DatabaseDialect.MYSQL);
    }

    @Test
    void verificationSqlMustNotWriteLockOrAdvanceSequences() {
        for (String sql : new String[] {
                "SELECT * INTO backup_items FROM items",
                "SELECT 1 FROM items FOR UPDATE",
                "SELECT 1 FROM items FOR NO KEY UPDATE",
                "SELECT 1 FROM items FOR SHARE",
                "SELECT pg_advisory_lock(1)",
                "SELECT setval('s', 1)",
                "SELECT nextval('s')",
                "SELECT GET_LOCK('x', 1)",
                "SELECT s.NEXTVAL FROM dual",
                "SELECT 1 FROM items INTO OUTFILE '/tmp/x'"}) {
            assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(sql))
                    .as(sql).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireNoSessionNamespaceChange(
                "SELECT 1 FROM t@remote", DatabaseDialect.ORACLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT count(*) > 0 FROM pg_class r WHERE r.relname = 'into'")).doesNotThrowAnyException();
    }
}
