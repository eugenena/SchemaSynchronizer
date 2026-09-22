package com.thinkai.schema;

import org.springframework.beans.factory.InitializingBean;

/** Spring lifecycle bridge; detected by Boot as a database initializer. */
public final class SchemaApplierInitializer implements InitializingBean {
    private final SchemaApplier schemaApplier;

    public SchemaApplierInitializer(SchemaApplier schemaApplier) {
        this.schemaApplier = schemaApplier;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        schemaApplier.applyFromClasspath();
    }
}
