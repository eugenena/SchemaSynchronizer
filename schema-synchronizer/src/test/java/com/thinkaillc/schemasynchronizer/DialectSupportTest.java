// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DialectSupportTest {

    @Test
    void releaseEachAttemptsEveryPartAndSuppressesLaterFailures() {
        java.util.List<String> attempted = new java.util.ArrayList<>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DialectSupport.releaseEach("a:b:c", part -> {
                    attempted.add(part);
                    if (!"b".equals(part)) {
                        throw new SQLException("release " + part);
                    }
                }))
                .isInstanceOf(SQLException.class)
                .hasMessage("release a")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        assertThat(attempted).containsExactly("a", "b", "c");
    }

    @Test
    void releaseEachSkipsPartsNotOwnedByThisRun() throws SQLException {
        java.util.List<String> attempted = new java.util.ArrayList<>();
        DialectSupport.releaseEach(":123", attempted::add);
        DialectSupport.releaseEach("456:", attempted::add);
        DialectSupport.releaseEach(":", attempted::add);
        assertThat(attempted).containsExactly("123", "456");
    }

    @Test
    void mysqlAppliedByMigrationDoesNotUseIfNotExists() {
        String ddl = DialectSupport.addAppliedByColumnDdl(DatabaseDialect.MYSQL, "schema_synchronizer_history");
        assertThat(ddl).contains("ADD COLUMN `applied_by`");
        assertThat(ddl).doesNotContain("IF NOT EXISTS");
    }

    @Test
    void mariaDbAppliedByMigrationKeepsIfNotExists() {
        assertThat(DialectSupport.addAppliedByColumnDdl(DatabaseDialect.MARIADB, "h"))
                .contains("IF NOT EXISTS");
    }

    @Test
    void detectsDuplicateColumnAcrossDialects() {
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.MYSQL,
                new SQLException("Duplicate column", "42S21", 1060))).isTrue();
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.POSTGRESQL,
                new SQLException("dup", "42701", 0))).isTrue();
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.SQLSERVER,
                new SQLException("dup", "S0001", 2705))).isTrue();
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.ORACLE,
                new SQLException("dup", "72000", 1430))).isTrue();
    }

    @Test
    void doesNotTreatSyntaxErrorsAsDuplicateColumn() {
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.MYSQL,
                new SQLException("syntax", "42000", 1064))).isFalse();
    }

    @Test
    void detectsDuplicateColumnViaCauseChain() {
        SQLException wrapped = new SQLException("wrapper", "HY000", 0);
        wrapped.initCause(new SQLException("Duplicate column", "42S21", 1060));
        assertThat(DialectSupport.isDuplicateColumn(DatabaseDialect.MYSQL, wrapped)).isTrue();
    }
}
