// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetSchemaScopeTest {

    private static void pg(String sql, String namespace) {
        ChangeSetSchemaScope.requireScoped(sql, namespace, DatabaseDialect.POSTGRESQL);
    }

    @Test
    void allowsUnqualifiedAndMatchingQualifiedNames() {
        assertThatCode(() -> pg(
                "ALTER TABLE items ADD COLUMN note TEXT", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "CREATE INDEX idx_items_note ON public.items (note)", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "UPDATE public.items SET note = ''", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "UPDATE items SET note = 'x'", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "UPDATE items SET note = 'x' WHERE items.id = 1", "public"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> pg(
                "UPDATE items SET note = 'x' WHERE other.id = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
    }

    @Test
    void rejectsCrossSchemaTargetsWithWhitespaceAroundDot() {
        assertThatThrownBy(() -> pg(
                "ALTER TABLE others . victims ADD COLUMN x INT", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("others");
        assertThatThrownBy(() -> pg(
                "UPDATE \"app_b\" . \"accounts\" SET x = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
    }

    @Test
    void rejectsCrossSchemaTargets() {
        assertThatThrownBy(() -> pg(
                "UPDATE app_b.accounts SET balance = 0", "app_a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b")
                .hasMessageContaining("app_a");
        assertThatThrownBy(() -> pg(
                "CREATE TABLE other.evil (id INT)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO reader", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger");
    }

    @Test
    void rejectsQuotedBracketAndBacktickCrossSchemaTargets() {
        assertThatThrownBy(() -> pg(
                "UPDATE \"app_b\".\"accounts\" SET x = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
        assertThatThrownBy(() -> pg(
                "UPDATE [app_b].[accounts] SET x = 1", "dbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
        assertThatThrownBy(() -> pg(
                "UPDATE `app_b`.`accounts` SET x = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
    }

    @Test
    void rejectsAdditionalCrossSchemaStatementForms() {
        assertThatThrownBy(() -> pg(
                "CREATE INDEX IF NOT EXISTS idx ON other.items (note)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "COMMENT ON TABLE other.evil IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE OR REPLACE FUNCTION other.wipe() RETURNS void LANGUAGE plpgsql AS $$ BEGIN PERFORM 1; END $$", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE TRIGGER t BEFORE INSERT ON other.items FOR EACH ROW EXECUTE FUNCTION f()", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE EXTENSION vector SCHEMA other", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "SELECT 1 AS id INTO other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE TABLE IF NOT EXISTS other.evil (id INT)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "ALTER TABLE IF EXISTS other.t ADD COLUMN x INT", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON other.t (id)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON SEQUENCE other.seq TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "COMMENT ON SCHEMA other IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "SELECT 1 INTO TEMP TABLE other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "GRANT EXECUTE ON PROCEDURE other.p() TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE TABLE public.t (LIKE other.src INCLUDING ALL)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "SELECT 1 INTO UNLOGGED other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "GRANT USAGE ON SCHEMA other TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "COMMENT ON DATABASE other IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> pg(
                "CREATE FUNCTION public.f() RETURNS void LANGUAGE plpgsql AS "
                        + "'BEGIN PERFORM 1 FROM other.t; END'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE FUNCTION public.f() RETURNS void LANGUAGE plpgsql AS "
                        + "'BEGIN EXECUTE ''GRANT USAGE ON SCHEMA other TO u''; END'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destructive");
    }

    @Test
    void rejectsGlobalAndSchemaColonGrants() {
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON *.* TO reader", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("*.*");
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON TABLE *.* TO reader", "public"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON otherdb.* TO reader", "appdb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("otherdb");
        assertThatCode(() -> pg(
                "GRANT SELECT ON appdb.* TO reader", "appdb"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "GRANT SELECT ON TABLE `appdb`.* TO reader", "appdb"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON evil.* TO u GRANT SELECT ON appdb.* TO u", "appdb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evil");
        assertThatThrownBy(() -> pg(
                "GRANT SELECT ON SCHEMA::other TO reader", "dbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
    }

    @Test
    void rejectsEveryDatabaseLevelGrantRegardlessOfName() {
        for (DatabaseDialect dialect : new DatabaseDialect[]{DatabaseDialect.SQLSERVER, DatabaseDialect.POSTGRESQL}) {
            String ns = dialect == DatabaseDialect.SQLSERVER ? "dbo" : "public";
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT CONNECT ON DATABASE::evil TO reader", ns, dialect))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("database-level");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT CONNECT ON DATABASE::" + ns + " TO reader", ns, dialect))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("database-level");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT CREATE ON DATABASE appdb TO reader", ns, dialect))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("database-level");
        }
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "COMMENT ON TABLE items IS 'GRANT ON DATABASE x'", "public", DatabaseDialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void mysqlCatalogComparisonIsCaseExact() {
        DatabaseDialect[] family = {DatabaseDialect.MYSQL, DatabaseDialect.MARIADB};
        for (DatabaseDialect dialect : family) {
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT SELECT ON appdb.* TO reader", "AppDB", dialect))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("appdb");
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT SELECT ON `appdb`.* TO reader", "AppDB", dialect))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "UPDATE appdb.items SET note = 'x'", "AppDB", dialect))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                    "UPDATE `AppDB`.items SET note = 'x'", "appdb", dialect))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                    "GRANT SELECT ON AppDB.* TO reader", "AppDB", dialect))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                    "UPDATE `AppDB`.items SET note = 'x'", "AppDB", dialect))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void sqlServerSchemaComparisonIsCaseExact() {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE [Sales].items SET note = 'x'", "sales", DatabaseDialect.SQLSERVER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE SALES.items SET note = 'x'", "sales", DatabaseDialect.SQLSERVER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE [sales].items SET note = 'x'", "sales", DatabaseDialect.SQLSERVER))
                .doesNotThrowAnyException();
    }

    @Test
    void postgresFoldsUnquotedButQuotedIsExact() {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE PUBLIC.items SET note = 'x'", "public", DatabaseDialect.POSTGRESQL))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"public\".items SET note = 'x'", "public", DatabaseDialect.POSTGRESQL))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"Public\".items SET note = 'x'", "public", DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"App\".items SET note = 'x'", "App", DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oracleFoldsUnquotedToUpperButQuotedIsExact() {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE app.items SET note = 'x'", "APP", DatabaseDialect.ORACLE))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"APP\".items SET note = 'x'", "app", DatabaseDialect.ORACLE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"app\".items SET note = 'x'", "APP", DatabaseDialect.ORACLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSessionNamespaceMutators() {
        assertThatThrownBy(() -> pg(
                "SELECT set_config('search_path', 'evil', true)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> pg(
                "SET search_path TO evil", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> pg(
                "ALTER SESSION SET CURRENT_SCHEMA = EVIL", "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> pg(
                "USE evil_db", "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
    }

    @Test
    void ignoresQualifiedNamesInsideOrdinaryStringLiterals() {
        assertThatCode(() -> pg(
                "UPDATE items SET note = 'app_b.accounts'", "public"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsInformationSchemaAndPgCatalogReferences() {
        assertThatCode(() -> pg(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> pg(
                "SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname = 'public'", "public"))
                .doesNotThrowAnyException();
    }

    @Test
    void mysqlLockResourceHashesWhenOverSixtyFourChars() {
        String shortName = DialectSupport.mysqlLockResource("app");
        assertThat(shortName).isEqualTo("schema_synchronizer_app");
        assertThat(shortName.length()).isLessThanOrEqualTo(DialectSupport.MYSQL_LOCK_NAME_MAX);

        String longSchema = "a".repeat(50);
        String hashed = DialectSupport.mysqlLockResource(longSchema);
        assertThat(hashed).startsWith("ss_");
        assertThat(hashed.length()).isLessThanOrEqualTo(DialectSupport.MYSQL_LOCK_NAME_MAX);
        // 1.2.0 locked "schema_synchronizer_" + schema with its case; MySQL rejects names over 64 chars.
        assertThat(DialectSupport.mysqlLockResourceLegacy(DatabaseDialect.MYSQL, longSchema)).isEqualTo(hashed);
        assertThat(DialectSupport.mysqlLockResourceLegacy(DatabaseDialect.MARIADB, longSchema))
                .isEqualTo("schema_synchronizer_" + longSchema);
        assertThat(DialectSupport.mysqlLockResourceLegacy(DatabaseDialect.MYSQL, "AppDb"))
                .isEqualTo("schema_synchronizer_AppDb");
        assertThat(DialectSupport.mysqlLockResourceLegacy(DatabaseDialect.MARIADB, "AppDb"))
                .isEqualTo("schema_synchronizer_AppDb");
        assertThat(DialectSupport.mysqlLockResource("AppDb")).isEqualTo("schema_synchronizer_appdb");
        assertThat(DialectSupport.namespaceLockKey("payments"))
                .isNotEqualTo(DialectSupport.namespaceLockKey("ledger"));
    }
}
