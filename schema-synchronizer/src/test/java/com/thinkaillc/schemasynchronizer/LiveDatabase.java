// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a live-database suite on its connection properties. Without them the suite is skipped,
 * unless {@code -Dschema.test.require.live=true}: then the suite runs and fails in
 * {@code @BeforeAll}, so a release gate cannot pass with every integration test skipped.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(LiveDatabaseExtension.class)
@interface LiveDatabase {
    /** Engine name used in messages. */
    String engine();

    /** System properties that must all be non-blank for the suite to run. */
    String[] properties();
}
