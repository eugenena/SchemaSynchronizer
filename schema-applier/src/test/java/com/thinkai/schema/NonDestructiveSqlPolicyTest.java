package com.thinkai.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonDestructiveSqlPolicyTest {
    @Test
    void acceptsAdditivePostgresOperationsAndBackfills() {
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(
                "ALTER TABLE child ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES parent(id)"))
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
    }
}
