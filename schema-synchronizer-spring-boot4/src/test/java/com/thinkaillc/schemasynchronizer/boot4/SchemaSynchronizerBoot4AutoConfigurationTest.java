// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.boot4;

import com.thinkaillc.schemasynchronizer.SchemaSynchronizer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerAutoConfiguration;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerInitializer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerStartupCheck;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSynchronizerBoot4AutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SchemaSynchronizerAutoConfiguration.class,
                    SchemaSynchronizerStartupCheck.class,
                    SchemaSynchronizerBoot4AutoConfiguration.class))
            .withBean(JdbcDataSource.class, () -> {
                JdbcDataSource dataSource = new JdbcDataSource();
                dataSource.setURL("jdbc:h2:mem:boot4");
                dataSource.setUser("sa");
                return dataSource;
            })
            .withPropertyValues(
                    "schema-synchronizer.enabled=true",
                    "schema-synchronizer.require-definition=false");

    @Test
    void boot4AdapterWinsAndBoot3AdapterIsSkipped() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SchemaSynchronizer.class);
            assertThat(context).hasSingleBean(SchemaSynchronizerInitializer.class);
            assertThat(context).hasSingleBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        });
    }

    @Test
    void adapterRemainsOptIn() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaSynchronizerBoot4AutoConfiguration.class))
                .withBean(JdbcDataSource.class, () -> new JdbcDataSource())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SchemaSynchronizer.class);
                    assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
                });
    }
}
