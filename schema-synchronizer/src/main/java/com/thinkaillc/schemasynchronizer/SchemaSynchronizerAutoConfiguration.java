// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, ObjectMapper.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "schema-synchronizer", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SchemaSynchronizerProperties.class)
public class SchemaSynchronizerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    SchemaSynchronizer schemaSynchronizer(ObjectMapper objectMapper, DataSource dataSource,
                                SchemaSynchronizerProperties properties) {
        return new SchemaSynchronizer(objectMapper, dataSource, properties.getResource(), properties.toOptions());
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaSynchronizerInitializer schemaSynchronizerInitializer(SchemaSynchronizer schemaSynchronizer) {
        return new SchemaSynchronizerInitializer(schemaSynchronizer);
    }
}
