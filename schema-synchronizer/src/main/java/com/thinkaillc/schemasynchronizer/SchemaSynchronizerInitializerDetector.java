// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

import java.util.Set;

public final class SchemaSynchronizerInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {
    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(SchemaSynchronizerInitializer.class);
    }
}
