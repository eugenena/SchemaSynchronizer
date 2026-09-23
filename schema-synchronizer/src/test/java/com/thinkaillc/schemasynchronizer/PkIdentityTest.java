// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkIdentityTest {

    @Test
    void nextvalDefault_becomesBigserial_notBareInt8() {
        String type = PkIdentity.createType("INT8", " NOT NULL", "nextval('access_rules_id_seq'::regclass)", false, true);
        assertThat(type).isEqualTo("BIGSERIAL NOT NULL");
        assertThat(type).doesNotContain("nextval");
    }

    @Test
    void jdbcAutoIncrement_becomesBigserial() {
        String type = PkIdentity.createType("INT8", " NOT NULL", null, true, true);
        assertThat(type).isEqualTo("BIGSERIAL NOT NULL");
    }

    @Test
    void foreignKeyInt8_staysInt8() {
        String type = PkIdentity.createType("INT8", " NOT NULL", null, false, false);
        assertThat(type).isEqualTo("INT8 NOT NULL");
    }

    @Test
    void restoreCreateSql_onlyTouchesSurrogateId_notFk() {
        String sql = "CREATE TABLE IF NOT EXISTS access_rules (id INT8 NOT NULL, monthly_limit INT4 NOT NULL, account_id INT8 NOT NULL, PRIMARY KEY (id))";
        String restored = PkIdentity.restoreCreateSql(sql);
        assertThat(restored).contains("id BIGSERIAL NOT NULL");
        assertThat(restored).contains("account_id INT8 NOT NULL");
        assertThat(restored).doesNotContain("(id INT8 NOT NULL");
    }

    @Test
    void restoreCreateSql_skipsNonIdPrimaryKey() {
        String sql = "CREATE TABLE IF NOT EXISTS resource_links (source_id INT8 NOT NULL, target_id INT8 NOT NULL, PRIMARY KEY (source_id, target_id))";
        assertThat(PkIdentity.restoreCreateSql(sql)).isEqualTo(sql);
    }

    @Test
    void restoreCreateSql_skipsCompositePrimaryKeyEvenIfIdIsFirst() {
        String sql = "CREATE TABLE t (id INT8 NOT NULL, resource_id INT8 NOT NULL, PRIMARY KEY (id, resource_id))";
        assertThat(PkIdentity.restoreCreateSql(sql)).isEqualTo(sql);
        assertThat(PkIdentity.hasSoleIdPrimaryKey(sql)).isFalse();
        assertThat(PkIdentity.createSqlWantsIdIdentity(sql)).isFalse();
    }

    @Test
    void lockTableSql_shareRowExclusive() {
        assertThat(PkIdentity.lockTableSql("access_rules"))
                .isEqualTo("LOCK TABLE access_rules IN SHARE ROW EXCLUSIVE MODE");
    }

    @Test
    void needsIdentityRepair_whenNeitherIdentityNorSequence() {
        assertThat(PkIdentity.needsIdentityRepair("", null)).isTrue();
        assertThat(PkIdentity.needsIdentityRepair("d", null)).isFalse();
        assertThat(PkIdentity.needsIdentityRepair("", "public.access_rules_id_seq")).isFalse();
    }

    @Test
    void needsSequenceAdvance_doesNotRewindHealthySequence() {
        assertThat(PkIdentity.needsSequenceAdvance(1000L, 50L)).isFalse();
        assertThat(PkIdentity.needsSequenceAdvance(1L, 50L)).isTrue();
        assertThat(PkIdentity.needsSequenceAdvance(50L, 50L)).isTrue();
        assertThat(PkIdentity.needsSequenceAdvance(5L, null)).isFalse();
        assertThat(PkIdentity.needsSequenceAdvance(null, 50L)).isTrue();
    }

    @Test
    void createSqlWantsIdIdentity_onlySurrogateSerialPk() {
        assertThat(PkIdentity.createSqlWantsIdIdentity(
                "CREATE TABLE t (id BIGSERIAL NOT NULL, PRIMARY KEY (id))")).isTrue();
        assertThat(PkIdentity.createSqlWantsIdIdentity(
                "CREATE TABLE t (source_id INT8 NOT NULL, target_id INT8 NOT NULL, PRIMARY KEY (source_id, target_id))")).isFalse();
    }

    @Test
    void syncIdentitySequenceSql_advancesPastMaxId_andIsCalledFalseWhenEmpty() {
        String sql = PkIdentity.syncIdentitySequenceSql("access_rules");
        assertThat(sql).contains("GREATEST");
        assertThat(sql).contains("last_value");
        assertThat(sql).contains("setval");
        assertThat(sql).contains("MAX(id)");
        assertThat(sql).contains("pg_get_serial_sequence('access_rules', 'id')");
        assertThat(sql).contains("IS NOT NULL");
    }
}
