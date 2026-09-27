// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

import java.util.Set;

/**
 * Tells Spring Boot's database-initialization ordering that {@link SchemaSynchronizerInitializer} beans
 * initialize the database, so beans that depend on an initialized database start after them.
 */
public final class SchemaSynchronizerInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {
    /** Instantiated by Spring through {@code META-INF/spring.factories}. */
    public SchemaSynchronizerInitializerDetector() {
    }

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(SchemaSynchronizerInitializer.class);
    }
}
