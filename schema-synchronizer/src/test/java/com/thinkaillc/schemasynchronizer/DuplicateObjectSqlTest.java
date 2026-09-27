// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateObjectSqlTest {

    @Test
    void recognizesPostgresDuplicateObjectStates() {
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("constraint exists", "42710")))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("relation exists", "42P07")))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("column exists", "42701")))
                .isTrue();
    }

    @Test
    void recognizesMySqlFamilyVendorCodes() {
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("Table exists", "42S01", 1050)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("Duplicate column", "42S21", 1060)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("Duplicate key name", "42000", 1061)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(new SQLException("Duplicate FK", "HY000", 1826)))
                .isTrue();
    }

    @Test
    void recognizesSqlServerAndOracleDuplicateObjectCodes() {
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("There is already an object named 'items'", "S0001", 2714)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("The operation failed because an index already exists", "S0001", 1913)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("Column names in each table must be unique", "S0001", 2705)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("Column name 'notes' does not exist", "S0001", 1911)))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("ORA-00955: name is already used by an existing object", "42000", 955)))
                .isTrue();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("ORA-01430: column being added already exists in table", "42000", 1430)))
                .isTrue();
    }

    @Test
    void doesNotTreatDataUniquenessAsSkippableDuplicate() {
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("duplicate key value violates unique constraint", "23505")))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("Key (id)=(1) already exists.", "23505")))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("Duplicate entry 'x' for key 'PRIMARY'", "23000", 1062)))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("Violation of UNIQUE KEY constraint", "23000", 2627)))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("ORA-00001: unique constraint violated", "23000", 1)))
                .isFalse();
    }

    @Test
    void ignoresChainedNextExceptionWhenPrimaryIsHardFailure() {
        SQLException primary = new SQLException("permission denied", "42501");
        primary.setNextException(new SQLException("constraint already exists", "42710"));
        assertThat(DuplicateObjectSql.isAlreadyExists(primary)).isFalse();
    }

    @Test
    void doesNotSwallowMissingRelationErrors() {
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("relation \"widgets\" does not exist", "42P01")))
                .isFalse();
    }

    @Test
    void doesNotClassifyFromMessageAloneWhenSqlStateAndVendorCodeAreAbsent() {
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("table already exists")))
                .isFalse();
        assertThat(DuplicateObjectSql.isAlreadyExists(
                new SQLException("name is already used by an existing object", null, 0)))
                .isFalse();
    }
}
