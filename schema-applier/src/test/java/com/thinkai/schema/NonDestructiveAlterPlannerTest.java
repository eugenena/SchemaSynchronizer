package com.thinkai.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NonDestructiveAlterPlannerTest {

    @Test
    void setDefaultWhenLiveDiffers() {
        var target = ColumnDefinitionParser.parse("VARCHAR(50) DEFAULT 'a'");
        var live = live("VARCHAR", 50, false, "'b'");
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c SET DEFAULT 'a'");
        assertThat(plan.pendingSql()).isEmpty();
    }

    @Test
    void dropDefaultWhenTargetOmitsDefault() {
        var target = ColumnDefinitionParser.parse("VARCHAR(50)");
        var live = live("VARCHAR", 50, false, "'a'");
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c DROP DEFAULT");
    }

    @Test
    void widenVarcharLength() {
        var target = ColumnDefinitionParser.parse("VARCHAR(200)");
        var live = live("VARCHAR", 50, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c TYPE VARCHAR(200)");
    }

    @Test
    void widenVarcharToText() {
        var target = ColumnDefinitionParser.parse("TEXT");
        var live = live("VARCHAR", 50, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c TYPE TEXT");
    }

    @Test
    void widenIntToBigint() {
        var target = ColumnDefinitionParser.parse("BIGINT");
        var live = live("INTEGER", null, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c TYPE BIGINT");
    }

    @Test
    void widensFixedCharToVarcharWithoutTruncation() {
        var target = ColumnDefinitionParser.parse("VARCHAR(3)");
        var live = live("BPCHAR", 3, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "currency", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN currency TYPE VARCHAR(3)");
        assertThat(plan.pendingSql()).isEmpty();
    }

    @Test
    void narrowVarcharIsPending() {
        var target = ColumnDefinitionParser.parse("VARCHAR(20)");
        var live = live("VARCHAR", 100, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).isEmpty();
        assertThat(plan.pendingSql()).hasSize(1);
        assertThat(plan.pendingSql().getFirst()).contains("TYPE VARCHAR(20)");
    }

    @Test
    void dropNotNullIsApplied() {
        var target = ColumnDefinitionParser.parse("TEXT");
        var live = live("TEXT", null, true, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c DROP NOT NULL");
    }

    @Test
    void setNotNullWithoutDefaultIsPending() {
        var target = ColumnDefinitionParser.parse("TEXT NOT NULL");
        var live = live("TEXT", null, false, null);
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).isEmpty();
        assertThat(plan.pendingSql()).hasSize(1);
        assertThat(plan.pendingSql().getFirst()).contains("SET NOT NULL");
    }

    @Test
    void mixedList_narrowDoesNotBlockDefaultChangeOnOtherAspect() {
        // Same column: narrow type pending, but default change is still safe to apply.
        var target = ColumnDefinitionParser.parse("VARCHAR(10) DEFAULT 'x'");
        var live = live("VARCHAR", 50, false, "'y'");
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).containsExactly(
                "ALTER TABLE t ALTER COLUMN c SET DEFAULT 'x'");
        assertThat(plan.pendingSql()).hasSize(1);
        assertThat(plan.pendingSql().getFirst()).contains("TYPE VARCHAR(10)");
    }

    @Test
    void identicalColumns_noOp() {
        var target = ColumnDefinitionParser.parse("VARCHAR(50) NOT NULL DEFAULT 'a'");
        var live = live("VARCHAR", 50, true, "'a'::character varying");
        var plan = NonDestructiveAlterPlanner.plan("t", "c", target, live);
        assertThat(plan.applySql()).isEmpty();
        assertThat(plan.pendingSql()).isEmpty();
    }

    private static LiveColumn live(String type, Integer length, boolean notNull, String def) {
        return new LiveColumn(type, length, notNull, def);
    }
}
