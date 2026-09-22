package com.thinkai.schema;

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
@ConditionalOnProperty(prefix = "thinkai.schema", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SchemaApplierProperties.class)
public class SchemaApplierAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    SchemaApplier schemaApplier(ObjectMapper objectMapper, DataSource dataSource,
                                SchemaApplierProperties properties) {
        return new SchemaApplier(objectMapper, dataSource, properties.getResource(), properties.toOptions());
    }

    @Bean
    @ConditionalOnMissingBean
    SchemaApplierInitializer schemaApplierInitializer(SchemaApplier schemaApplier) {
        return new SchemaApplierInitializer(schemaApplier);
    }
}
