// Copyright 2026 Eugene Naoumov
// SPDX-License-Identifier: Apache-2.0

package io.github.eugenena.schemasynchronizer;

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
