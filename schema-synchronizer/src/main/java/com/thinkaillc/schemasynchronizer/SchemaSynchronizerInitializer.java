// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.springframework.beans.factory.InitializingBean;

/** Spring lifecycle bridge; detected by Boot as a database initializer. */
public final class SchemaSynchronizerInitializer implements InitializingBean {
    private final SchemaSynchronizer schemaSynchronizer;

    public SchemaSynchronizerInitializer(SchemaSynchronizer schemaSynchronizer) {
        this.schemaSynchronizer = schemaSynchronizer;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        schemaSynchronizer.synchronizeFromClasspath();
    }
}
