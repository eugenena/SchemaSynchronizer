// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
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

        when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        assertThat(DatabaseDialect.detect(metadata)).isEqualTo(DatabaseDialect.SQLSERVER);

        when(metadata.getDatabaseProductName()).thenReturn("Oracle");
        assertThat(DatabaseDialect.detect(metadata)).isEqualTo(DatabaseDialect.ORACLE);

        assertThat(DatabaseDialect.MARIADB.isMySqlFamily()).isTrue();
        assertThat(DatabaseDialect.MYSQL.isMySqlFamily()).isTrue();
        assertThat(DatabaseDialect.POSTGRESQL.isMySqlFamily()).isFalse();
        assertThat(DatabaseDialect.SQLSERVER.ddlMayCommitImplicitly()).isFalse();
        assertThat(DatabaseDialect.ORACLE.ddlMayCommitImplicitly()).isTrue();
        assertThat(DatabaseDialect.SQLSERVER.supportsTransactionalDryRun()).isTrue();
        assertThat(DatabaseDialect.ORACLE.supportsCreateIndexIfNotExists()).isFalse();
        assertThat(DatabaseDialect.SQLSERVER.supportsCreateTableIfNotExists()).isFalse();
        assertThat(DatabaseDialect.POSTGRESQL.maxIdentifierLength()).isEqualTo(63);
        assertThat(DatabaseDialect.MYSQL.maxIdentifierLength()).isEqualTo(63);
        assertThat(DatabaseDialect.SQLSERVER.maxIdentifierLength()).isEqualTo(128);
        assertThat(DatabaseDialect.ORACLE.maxIdentifierLength()).isEqualTo(128);
    }

    @Test
    void parsesDialectAliasesAndRejectsUnknown() throws Exception {
        assertThat(DatabaseDialect.parse("sqlserver")).isEqualTo(DatabaseDialect.SQLSERVER);
        assertThat(DatabaseDialect.parse("mssql")).isEqualTo(DatabaseDialect.SQLSERVER);
        assertThat(DatabaseDialect.parse("sql-server")).isEqualTo(DatabaseDialect.SQLSERVER);
        assertThat(DatabaseDialect.parse("oracle")).isEqualTo(DatabaseDialect.ORACLE);

        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getDatabaseProductName()).thenReturn("H2");

        assertThatThrownBy(() -> DatabaseDialect.detect(metadata))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported database");
        assertThatThrownBy(() -> DatabaseDialect.parse("db2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported schema dialect");
    }

    @Test
    void metadataHelpersMatchNamespaceModel() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("appdb");

        assertThat(DatabaseDialect.SQLSERVER.metadataCatalog(connection, "dbo")).isEqualTo("appdb");
        assertThat(DatabaseDialect.SQLSERVER.metadataSchemaPattern("dbo")).isEqualTo("dbo");
        assertThat(DatabaseDialect.SQLSERVER.qualifyHistoryTable("dbo", "schema_synchronizer_history"))
                .isEqualTo("dbo.schema_synchronizer_history");

        assertThat(DatabaseDialect.ORACLE.metadataCatalog(connection, "app")).isNull();
        assertThat(DatabaseDialect.ORACLE.metadataSchemaPattern("app")).isEqualTo("APP");
        assertThat(DatabaseDialect.ORACLE.metadataObjectName("items")).isEqualTo("ITEMS");
        assertThat(DatabaseDialect.ORACLE.qualifyHistoryTable("app", "schema_synchronizer_history"))
                .isEqualTo("app.schema_synchronizer_history");
    }
}
