// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.boot4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerInitializer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerProperties;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/** Spring Boot 4 adapter; kept in a separate artifact so Boot 3 consumers retain their existing setup. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({DataSource.class, ObjectMapper.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "schema-synchronizer", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SchemaSynchronizerProperties.class)
public class SchemaSynchronizerBoot4AutoConfiguration {

    /** Creates the synchronizer and lifecycle bridge when the opt-in property is enabled. */
    @Bean
    @ConditionalOnMissingBean
    SchemaSynchronizer schemaSynchronizer(ObjectMapper objectMapper, DataSource dataSource,
                                          SchemaSynchronizerProperties properties) {
        SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(properties.getSchema(),
                properties.getHistoryTable(), properties.getAdvisoryLockId(), properties.isDryRun(),
                properties.isFailOnPending(), properties.isRequireDefinition());
        return new SchemaSynchronizer(objectMapper, dataSource, properties.getResource(), options);
    }

    /** Provides the Jackson 2 mapper required by the shared engine when Boot 4 uses Jackson 3. */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper schemaSynchronizerJackson2ObjectMapper() {
        return new ObjectMapper();
    }

    /** Runs synchronization before JPA initialization. */
    @Bean
    @ConditionalOnMissingBean
    SchemaSynchronizerInitializer schemaSynchronizerInitializer(SchemaSynchronizer schemaSynchronizer) {
        return new SchemaSynchronizerInitializer(schemaSynchronizer);
    }
}
