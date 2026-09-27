// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.Arrays;
import java.util.List;

final class LiveDatabaseExtension implements ExecutionCondition, BeforeAllCallback {
    static final String REQUIRE_LIVE = "schema.test.require.live";

    static boolean requireLive() {
        return Boolean.parseBoolean(System.getProperty(REQUIRE_LIVE, "false").trim());
    }

    static List<String> missing(String... properties) {
        return Arrays.stream(properties)
                .filter(name -> {
                    String value = System.getProperty(name);
                    return value == null || value.isBlank();
                })
                .toList();
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        LiveDatabase live = annotation(context);
        if (live == null) {
            return ConditionEvaluationResult.enabled("not a live-database suite");
        }
        List<String> missing = missing(live.properties());
        if (missing.isEmpty()) {
            return ConditionEvaluationResult.enabled(live.engine() + " properties are set");
        }
        if (requireLive()) {
            return ConditionEvaluationResult.enabled(REQUIRE_LIVE + "=true: " + live.engine()
                    + " must fail without " + missing);
        }
        return ConditionEvaluationResult.disabled(live.engine() + " suite skipped; set " + missing
                + " (or " + REQUIRE_LIVE + "=true to fail instead of skipping)");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        LiveDatabase live = annotation(context);
        if (live == null) {
            return;
        }
        List<String> missing = missing(live.properties());
        if (!missing.isEmpty() && requireLive()) {
            throw new AssertionError(REQUIRE_LIVE + "=true but the " + live.engine()
                    + " integration suite is missing " + missing);
        }
    }

    private static LiveDatabase annotation(ExtensionContext context) {
        return context.getTestClass()
                .flatMap(type -> AnnotationSupport.findAnnotation(type, LiveDatabase.class))
                .orElse(null);
    }
}
