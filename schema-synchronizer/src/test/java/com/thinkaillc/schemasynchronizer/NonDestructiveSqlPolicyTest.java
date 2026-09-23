// Copyright 2026 Eugene Naoumov
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
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "UPDATE child SET state = 'READY' WHERE state IS NULL"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "CREATE OR REPLACE FUNCTION guard_row() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN RAISE EXCEPTION 'immutable'; END $$"))
                .doesNotThrowAnyException();
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
