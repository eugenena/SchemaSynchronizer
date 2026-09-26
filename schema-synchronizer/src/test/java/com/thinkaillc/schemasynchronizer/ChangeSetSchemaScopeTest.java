// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetSchemaScopeTest {

    @Test
    void allowsUnqualifiedAndMatchingQualifiedNames() {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "ALTER TABLE items ADD COLUMN note TEXT", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE INDEX idx_items_note ON public.items (note)", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE public.items SET note = ''", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE items SET note = 'x'", "public"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE items SET items.note = 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @Test
    void rejectsCrossSchemaTargets() {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE app_b.accounts SET balance = 0", "app_a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b")
                .hasMessageContaining("app_a");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE TABLE other.evil (id INT)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO reader", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger");
    }

    @Test
    void rejectsQuotedBracketAndBacktickCrossSchemaTargets() {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE \"app_b\".\"accounts\" SET x = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE [app_b].[accounts] SET x = 1", "dbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE `app_b`.`accounts` SET x = 1", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_b");
    }

    @Test
    void rejectsAdditionalCrossSchemaStatementForms() {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE INDEX IF NOT EXISTS idx ON other.items (note)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "COMMENT ON TABLE other.evil IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE OR REPLACE FUNCTION other.wipe() RETURNS void LANGUAGE plpgsql AS $$ BEGIN PERFORM 1; END $$",
                "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE TRIGGER t BEFORE INSERT ON other.items FOR EACH ROW EXECUTE FUNCTION f()",
                "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE EXTENSION vector SCHEMA other", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "SELECT 1 AS id INTO other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE TABLE IF NOT EXISTS other.evil (id INT)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "ALTER TABLE IF EXISTS other.t ADD COLUMN x INT", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON other.t (id)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "GRANT SELECT ON SEQUENCE other.seq TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "COMMENT ON SCHEMA other IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "SELECT 1 INTO TEMP TABLE other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "GRANT EXECUTE ON PROCEDURE other.p() TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE TABLE public.t (LIKE other.src INCLUDING ALL)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "SELECT 1 INTO UNLOGGED other.stolen", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "GRANT USAGE ON SCHEMA other TO u", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "COMMENT ON DATABASE other IS 'x'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE FUNCTION public.f() RETURNS void LANGUAGE plpgsql AS "
                        + "'BEGIN PERFORM 1 FROM other.t; END'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "CREATE FUNCTION public.f() RETURNS void LANGUAGE plpgsql AS "
                        + "'BEGIN EXECUTE ''GRANT USAGE ON SCHEMA other TO u''; END'", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
    }

    @Test
    void rejectsSessionNamespaceMutators() {
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "SELECT set_config('search_path', 'evil', true)", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "SET search_path TO evil", "public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "ALTER SESSION SET CURRENT_SCHEMA = EVIL", "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
        assertThatThrownBy(() -> ChangeSetSchemaScope.requireScoped(
                "USE evil_db", "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session namespace");
    }

    @Test
    void ignoresQualifiedNamesInsideOrdinaryStringLiterals() {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "UPDATE items SET note = 'app_b.accounts'", "public"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsInformationSchemaAndPgCatalogReferences() {
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'", "public"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChangeSetSchemaScope.requireScoped(
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
        assertThat(DialectSupport.namespaceLockKey("payments"))
                .isNotEqualTo(DialectSupport.namespaceLockKey("ledger"));
    }
}
