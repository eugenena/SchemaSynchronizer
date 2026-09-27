// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaSynchronizerAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaSynchronizerAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("schema-synchronizer.resource=/empty-schema.json");

    @Test
    void staysOffUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SchemaSynchronizer.class);
            assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
        });
    }

    @Test
    void createsApplierAndInitializerWhenEnabled() {
        runner.withPropertyValues("schema-synchronizer.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(SchemaSynchronizer.class);
            assertThat(context).hasSingleBean(SchemaSynchronizerInitializer.class);
        });
    }

    @Test
    void canBeDisabledExplicitly() {
        runner.withPropertyValues("schema-synchronizer.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchemaSynchronizer.class);
            assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
        });
    }

    @Test
    void backsOffWhenMultipleDataSourcesExist() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaSynchronizerAutoConfiguration.class))
                .withUserConfiguration(MultipleDataSourcesConfiguration.class)
                .withPropertyValues("schema-synchronizer.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchemaSynchronizer.class);
                    assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean DataSource dataSource() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            Statement statement = mock(Statement.class);
            ResultSet lockRs = mock(ResultSet.class);
            ResultSet historyTables = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(metaData.getTables(any(), eq("public"), eq("schema_synchronizer_history"), any(String[].class)))
                    .thenReturn(historyTables);
            when(historyTables.next()).thenReturn(false);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute(anyString())).thenReturn(true);
            when(statement.executeQuery(anyString())).thenReturn(lockRs);
            when(lockRs.next()).thenReturn(true);
            when(lockRs.getBoolean(1)).thenReturn(true);
            return dataSource;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MultipleDataSourcesConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DataSource firstDataSource() { return mock(DataSource.class); }
        @Bean DataSource secondDataSource() { return mock(DataSource.class); }
    }
}
