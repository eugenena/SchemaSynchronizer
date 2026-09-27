// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One startup WARN exactly when a definition ships, no initializer will synchronize it, and
 * {@code schema-synchronizer.enabled} is not an explicit {@code false}.
 */
class SchemaSynchronizerStartupCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaSynchronizerStartupCheck.class));

    @Test
    void warnsWhenTheResourceExistsAndEnabledIsUnset() {
        runner.withPropertyValues("schema-synchronizer.resource=/empty-schema.json").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                    .contains("/empty-schema.json")
                    .contains("schema-synchronizer.enabled is not set")
                    .contains("schema-synchronizer.enabled=true")
                    .contains("schema-synchronizer.enabled=false");
        });
    }

    @Test
    void aRegisteredInitializerIsSilentAndIsNotInstantiatedByTheCheck() {
        for (String enabled : new String[]{null, "true", "yes"}) {
            ApplicationContextRunner withInitializer = runner
                    .withPropertyValues("schema-synchronizer.resource=/empty-schema.json")
                    .withBean("appInitializer", SchemaSynchronizerInitializer.class, () -> {
                        throw new AssertionError("the check must not instantiate the initializer");
                    }, definition -> definition.setLazyInit(true));
            if (enabled != null) {
                withInitializer = withInitializer.withPropertyValues("schema-synchronizer.enabled=" + enabled);
            }
            withInitializer.run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class)
                    .warning()).as("enabled=%s", enabled).isNull());
        }
    }

    @Test
    void enabledTrueWithoutAnActiveAutoConfigurationWarnsWhy() {
        // Both auto-configurations, but no DataSource: SchemaSynchronizerAutoConfiguration stays off.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchemaSynchronizerAutoConfiguration.class,
                        SchemaSynchronizerStartupCheck.class))
                .withPropertyValues("schema-synchronizer.resource=/empty-schema.json", "schema-synchronizer.enabled=TRUE")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchemaSynchronizerInitializer.class);
                    assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                            .contains("OFF although schema-synchronizer.enabled=true")
                            .contains("exactly one DataSource");
                });
    }

    @Test
    void aValueOtherThanTrueOrFalseWarnsThatSynchronizationIsOff() {
        for (String value : new String[]{"yes", "on", ""}) {
            runner.withPropertyValues("schema-synchronizer.resource=/empty-schema.json",
                            "schema-synchronizer.enabled=" + value)
                    .run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                            .as("enabled=%s", value)
                            .contains("schema-synchronizer.enabled='" + value + "' is not 'true'"));
        }
    }

    @Test
    void anApplicationSynchronizerBeanAloneDoesNotRunAtStartupSoItStillWarns() {
        runner.withPropertyValues("schema-synchronizer.resource=/empty-schema.json")
                .withBean(SchemaSynchronizer.class, () -> new SchemaSynchronizer(
                        new com.fasterxml.jackson.databind.ObjectMapper(), null))
                .run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                        .contains("is not set"));
    }

    @Test
    void enabledFalseIsSilent() {
        runner.withPropertyValues("schema-synchronizer.resource=/empty-schema.json", "schema-synchronizer.enabled=false")
                .run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                        .isNull());
    }

    @Test
    void missingResourceIsSilent() {
        runner.withPropertyValues("schema-synchronizer.resource=/no-such-definition.json")
                .run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                        .isNull());
        // The default /schema-definition.json is not on the test classpath.
        runner.run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                .isNull());
    }

    @Test
    void resourceWithoutLeadingSlashIsFoundLikeTheSynchronizerFindsIt() {
        runner.withPropertyValues("schema-synchronizer.resource=empty-schema.json")
                .run(context -> assertThat(context.getBean(SchemaSynchronizerStartupCheck.Result.class).warning())
                        .contains("empty-schema.json"));
    }

    @Test
    void enabledSetThroughAnEnvironmentVariableCountsAsSet() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("SCHEMA_SYNCHRONIZER_ENABLED", "false",
                        "SCHEMA_SYNCHRONIZER_RESOURCE", "/empty-schema.json")));
        assertThat(SchemaSynchronizerStartupCheck.warningFor(environment, getClass().getClassLoader(), false))
                .isNull();

        StandardEnvironment unset = new StandardEnvironment();
        unset.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("SCHEMA_SYNCHRONIZER_RESOURCE", "/empty-schema.json")));
        assertThat(SchemaSynchronizerStartupCheck.warningFor(unset, getClass().getClassLoader(), false))
                .isNotNull();
        assertThat(SchemaSynchronizerStartupCheck.warningFor(unset, getClass().getClassLoader(), true)).isNull();
    }

    @Test
    void isRegisteredAsAnAutoConfigurationAndNeedsNoDataSource() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(SchemaSynchronizerStartupCheck.class.getName(),
                        SchemaSynchronizerAutoConfiguration.class.getName());
        // No DataSource or ObjectMapper in this context: the check must still start.
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(SchemaSynchronizerStartupCheck.Result.class));
    }
}
