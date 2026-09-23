// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package io.github.eugenena.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

class SchemaSynchronizerAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaSynchronizerAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("schema-synchronizer.resource=/empty-schema.json");

    @Test
    void createsApplierAndInitializerByDefault() {
        runner.run(context -> {
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
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchemaSynchronizer.class);
                    assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DataSource dataSource() throws Exception {
            DataSource result = mock(DataSource.class, RETURNS_DEEP_STUBS);
            when(result.getConnection().getAutoCommit()).thenReturn(true);
            when(result.getConnection().getMetaData().getDatabaseProductName()).thenReturn("PostgreSQL");
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MultipleDataSourcesConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DataSource firstDataSource() { return mock(DataSource.class); }
        @Bean DataSource secondDataSource() { return mock(DataSource.class); }
    }
}
