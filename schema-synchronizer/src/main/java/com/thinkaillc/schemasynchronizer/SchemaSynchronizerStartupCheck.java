// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Objects;

/**
 * Always-on Spring Boot auto-configuration that warns once at startup when an application ships a
 * schema definition but nothing will synchronize it: since 2.0.0 synchronization is opt-in, so such
 * an application would otherwise silently stop receiving schema changes.
 *
 * <p>Synchronization runs at startup exactly when a {@link SchemaSynchronizerInitializer} bean is
 * registered (by {@link SchemaSynchronizerAutoConfiguration} or by the application). Without one, it
 * warns unless {@code schema-synchronizer.enabled=false} opts out explicitly; the message says why
 * (property unset, set to {@code true} but the auto-configuration did not activate, or not a boolean).
 * It registers one bean that only reads the environment, bean definitions, and the classpath; it
 * needs no {@code DataSource} and never connects.
 */
@AutoConfiguration(after = SchemaSynchronizerAutoConfiguration.class)
public class SchemaSynchronizerStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SchemaSynchronizerStartupCheck.class);

    static final String ENABLED_PROPERTY = "schema-synchronizer.enabled";
    static final String RESOURCE_PROPERTY = "schema-synchronizer.resource";
    static final String DEFAULT_RESOURCE = "/schema-definition.json";

    /** Creates the auto-configuration; Spring Boot instantiates it. */
    public SchemaSynchronizerStartupCheck() {
    }

    @Bean
    Result schemaSynchronizerStartupCheckResult(Environment environment, ListableBeanFactory beanFactory) {
        boolean initializerRegistered = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                beanFactory, SchemaSynchronizerInitializer.class, true, false).length > 0;
        String warning = warningFor(environment, Thread.currentThread().getContextClassLoader(),
                initializerRegistered);
        if (warning != null) {
            log.warn(warning);
        }
        return new Result(warning);
    }

    /** What the check decided; {@code warning} is null when nothing was logged. */
    record Result(String warning) {}

    /**
     * The startup warning, or null. Silent when the configured resource (default
     * {@value #DEFAULT_RESOURCE}, looked up as {@link SchemaSynchronizer#synchronizeFromClasspath()}
     * does) is absent, when {@code initializerRegistered} (synchronization will run), or when
     * {@code enabled} is {@code false}; otherwise warns with the reason synchronization is off.
     */
    static String warningFor(Environment environment, ClassLoader classLoader, boolean initializerRegistered) {
        Objects.requireNonNull(environment, "environment");
        String resource = environment.getProperty(RESOURCE_PROPERTY, DEFAULT_RESOURCE);
        if (initializerRegistered || resource.isBlank() || !resourceExists(resource, classLoader)) {
            return null;
        }
        String enabled = environment.getProperty(ENABLED_PROPERTY);
        String prefix = "[SchemaSynchronizer] " + resource + " is on the classpath but ";
        String choice = " Set " + ENABLED_PROPERTY + "=true to synchronize at startup, or " + ENABLED_PROPERTY
                + "=false to silence this warning.";
        if (enabled == null) {
            return prefix + ENABLED_PROPERTY + " is not set, so schema synchronization is OFF "
                    + "(it is opt-in since 2.0.0)." + choice;
        }
        String value = enabled.trim();
        if (value.equalsIgnoreCase("false")) {
            return null;
        }
        if (value.equalsIgnoreCase("true")) {
            return prefix + "schema synchronization is OFF although " + ENABLED_PROPERTY + "=true: the "
                    + "auto-configuration needs exactly one DataSource bean (or a @Primary one) and Jackson's "
                    + "ObjectMapper on the classpath. Fix that, or register a SchemaSynchronizerInitializer bean.";
        }
        return prefix + ENABLED_PROPERTY + "='" + enabled + "' is not 'true', so schema synchronization is OFF."
                + choice;
    }

    private static boolean resourceExists(String resource, ClassLoader classLoader) {
        if (SchemaSynchronizer.class.getResource(resource) != null) {
            return true;
        }
        ClassLoader loader = classLoader != null ? classLoader : SchemaSynchronizer.class.getClassLoader();
        return loader.getResource(resource.startsWith("/") ? resource.substring(1) : resource) != null;
    }
}
