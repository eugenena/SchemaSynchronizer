package com.thinkai.schema;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

import java.util.Set;

public final class SchemaApplierInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {
    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(SchemaApplierInitializer.class);
    }
}
