// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseDialectTest {

    @Test
    void detectsSupportedDatabaseFamilies() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);

        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertThat(DatabaseDialect.detect(metadata)).isEqualTo(DatabaseDialect.POSTGRESQL);

        when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        assertThat(DatabaseDialect.detect(metadata)).isEqualTo(DatabaseDialect.MARIADB);

        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        assertThat(DatabaseDialect.detect(metadata)).isEqualTo(DatabaseDialect.MYSQL);

        assertThat(DatabaseDialect.MARIADB.isMySqlFamily()).isTrue();
        assertThat(DatabaseDialect.MYSQL.isMySqlFamily()).isTrue();
        assertThat(DatabaseDialect.POSTGRESQL.isMySqlFamily()).isFalse();
    }

    @Test
    void rejectsUnknownDatabaseAndDeclaredDialect() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getDatabaseProductName()).thenReturn("H2");

        assertThatThrownBy(() -> DatabaseDialect.detect(metadata))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported database");
        assertThatThrownBy(() -> DatabaseDialect.parse("oracle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported schema dialect");
    }
}
