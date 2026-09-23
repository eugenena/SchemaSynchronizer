// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDiscoveryMetadataTest {
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String SPRING_FACTORIES = "META-INF/spring.factories";
    private static final String DETECTOR_KEY = DatabaseInitializerDetector.class.getName();

    @Test
    void autoConfigurationImportNamesLoadAnnotatedClasses() throws Exception {
        List<String> classNames;
        try (InputStream input = requiredResource(AUTO_CONFIGURATION_IMPORTS)) {
            classNames = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }

        assertThat(classNames).containsExactly(SchemaSynchronizerAutoConfiguration.class.getName());
        for (String className : classNames) {
            Class<?> registered = Class.forName(className, false, getClass().getClassLoader());
            assertThat(registered).hasAnnotation(AutoConfiguration.class);
        }
    }

    @Test
    void springFactoriesNamesLoadDatabaseInitializerDetectors() throws Exception {
        Properties factories = new Properties();
        try (InputStream input = requiredResource(SPRING_FACTORIES)) {
            factories.load(input);
        }

        String registration = factories.getProperty(DETECTOR_KEY);
        assertThat(registration).isNotBlank();
        List<String> classNames = List.of(registration.split(",")).stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        assertThat(classNames).containsExactly(SchemaSynchronizerInitializerDetector.class.getName());
        for (String className : classNames) {
            Class<?> registered = Class.forName(className, false, getClass().getClassLoader());
            assertThat(registered).isAssignableTo(DatabaseInitializerDetector.class);
        }
    }

    private InputStream requiredResource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertThat(input).as("packaged resource %s", path).isNotNull();
        return input;
    }
}
