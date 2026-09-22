package com.thinkai.schema;

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

class SchemaApplierAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaApplierAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("thinkai.schema.resource=/empty-schema.json");

    @Test
    void createsApplierAndInitializerByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchemaApplier.class);
            assertThat(context).hasSingleBean(SchemaApplierInitializer.class);
        });
    }

    @Test
    void canBeDisabledExplicitly() {
        runner.withPropertyValues("thinkai.schema.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchemaApplier.class);
            assertThat(context).doesNotHaveBean(SchemaApplierInitializer.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DataSource dataSource() throws Exception {
            DataSource result = mock(DataSource.class, RETURNS_DEEP_STUBS);
            when(result.getConnection().getAutoCommit()).thenReturn(true);
            return result;
        }
    }
}
