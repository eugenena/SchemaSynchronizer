// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonDestructiveSqlPolicyTest {

    @Test
    void mysqlAllowsCreateIndexWithoutUnsupportedIfNotExistsClause() {
        assertThatCode(() -> NonDestructiveSqlPolicy.requireCreateIndex(
                "CREATE INDEX idx_items_name ON items (name)", false))
                .doesNotThrowAnyException();
    }
    @Test
    void acceptsAdditivePostgresOperationsAndBackfills() {
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE child ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES parent(id)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT EXISTS (SELECT 1 FROM audit_log WHERE operation = 'update')"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT 'insert', \"delete\", $$merge$$"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $body$ "
                        + "BEGIN PERFORM 1; PERFORM ';'; END $body$;"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION wipe() RETURNS void LANGUAGE plpgsql AS $$ "
                        + "BEGIN EXECUTE 'DROP TABLE customers'; END $$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destructive");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION wipe() RETURNS void LANGUAGE plpgsql AS "
                        + "'BEGIN DROP TABLE customers; END'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destructive");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION wipe() RETURNS void LANGUAGE plpgsql AS "
                        + "E'BEGIN DROP TABLE customers; END'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destructive");
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "UPDATE child SET state = 'READY' WHERE state IS NULL"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION guard_row() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN RAISE EXCEPTION 'immutable'; END $$"))
                .doesNotThrowAnyException();
    }

    @Test
    void reviewedSqlAllowsManualOperationsButKeepsStatementShapeAndSupportedObjectChecks() {
        String triggerFunction = "CREATE OR REPLACE FUNCTION public.guard_row() RETURNS trigger "
                + "LANGUAGE plpgsql AS $body$ BEGIN EXECUTE format('"
                + "SELECT EXISTS (SELECT 1 FROM %I.items WHERE id = $1)', TG_TABLE_SCHEMA) "
                + "INTO found USING NEW.id; RETURN NEW; END $body$";

        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(triggerFunction, DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatCode(() -> NonDestructiveSqlPolicy.requireReviewed(triggerFunction, DatabaseDialect.POSTGRESQL))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReviewed(
                "CREATE VIEW items_view AS SELECT * FROM items", DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not yet supported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReviewed(
                "CREATE FUNCTION f() RETURNS void LANGUAGE sql AS $$ SELECT 1 $$; DROP TABLE items",
                DatabaseDialect.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one statement");
    }

    @Test
    void rejectsDestructiveAndUnclassifiedOperations() {
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe("DROP TABLE customers"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers DROP COLUMN email"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe("DELETE FROM customers"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers ALTER COLUMN name TYPE VARCHAR(10)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers ALTER COLUMN name VARCHAR(10)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers MODIFY (name VARCHAR2(1))"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers ADD notes VARCHAR(255)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers ADD (notes VARCHAR2(255))"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "DO $$ BEGIN EXECUTE 'DROP TABLE customers'; END $$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER SYSTEM SET shared_buffers = '1GB'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE customers RENAME TO former_customers"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE DATABASE unexpected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(
                "SELECT 1; DROP FUNCTION f() CASCADE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one statement");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "SELECT true; UPDATE customers SET name = 'changed'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one statement");
        assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireReadOnlyVerification(
                "WITH changed AS (UPDATE accounts SET balance = 0 RETURNING *) "
                        + "SELECT count(*) FROM changed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data-modifying");
    }
}
