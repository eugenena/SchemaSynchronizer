// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract for the release-gate guard and the destructive-cleanup namespace rule. */
class LiveDatabaseGuardTest {
    private static final String URL = "schema.test.guardprobe.url";
    private static final String USER = "schema.test.guardprobe.user";
    private static final List<String> KEYS = List.of(URL, USER, LiveDatabaseExtension.REQUIRE_LIVE);

    private final Map<String, String> saved = new HashMap<>();
    private final LiveDatabaseExtension extension = new LiveDatabaseExtension();

    @LiveDatabase(engine = "Probe", properties = {URL, USER})
    static final class ProbeSuite {
    }

    static final class PlainSuite {
    }

    @BeforeEach
    void saveProperties() {
        for (String key : KEYS) {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    void allPropertiesSetRunsTheSuiteWithOrWithoutRequireLive() {
        System.setProperty(URL, "jdbc:probe://x");
        System.setProperty(USER, "u");
        for (String requireLive : List.of("false", "true")) {
            System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, requireLive);
            assertThat(extension.evaluateExecutionCondition(context(ProbeSuite.class)).isDisabled())
                    .as("require.live=%s", requireLive).isFalse();
            assertThatCode(() -> extension.beforeAll(context(ProbeSuite.class))).doesNotThrowAnyException();
        }
    }

    @Test
    void missingPropertiesSkipWithoutRequireLive() {
        System.setProperty(URL, "jdbc:probe://x");
        var result = extension.evaluateExecutionCondition(context(ProbeSuite.class));
        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).get().asString().contains(USER).doesNotContain(URL + ",");
        assertThatCode(() -> extension.beforeAll(context(ProbeSuite.class))).doesNotThrowAnyException();
    }

    @Test
    void missingPropertiesFailUnderRequireLive() {
        System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, "true");
        assertThat(extension.evaluateExecutionCondition(context(ProbeSuite.class)).isDisabled()).isFalse();
        assertThatThrownBy(() -> extension.beforeAll(context(ProbeSuite.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Probe")
                .hasMessageContaining(URL)
                .hasMessageContaining(USER);
    }

    @Test
    void oneMissingPropertyOfSeveralStillFailsUnderRequireLive() {
        System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, "true");
        System.setProperty(URL, "jdbc:probe://x");
        System.setProperty(USER, "  ");
        assertThatThrownBy(() -> extension.beforeAll(context(ProbeSuite.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(USER)
                .hasMessageNotContaining(URL);
    }

    @Test
    void requireLiveOnlyAcceptsTrue() {
        for (String value : List.of("false", "", "yes", "1")) {
            System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, value);
            assertThat(extension.evaluateExecutionCondition(context(ProbeSuite.class)).isDisabled())
                    .as("require.live=%s", value).isTrue();
        }
        System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, " TRUE ");
        assertThat(extension.evaluateExecutionCondition(context(ProbeSuite.class)).isDisabled()).isFalse();
    }

    @Test
    void unannotatedClassesAreUntouched() {
        System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, "true");
        assertThat(extension.evaluateExecutionCondition(context(PlainSuite.class)).isDisabled()).isFalse();
        assertThatCode(() -> extension.beforeAll(context(PlainSuite.class))).doesNotThrowAnyException();
    }

    @Test
    void assumeOrRequireSkipsNormallyAndFailsUnderRequireLive() {
        assertThatThrownBy(() -> LiveTestSupport.assumeOrRequire(false, "needs admin"))
                .isInstanceOf(org.opentest4j.TestAbortedException.class);
        System.setProperty(LiveDatabaseExtension.REQUIRE_LIVE, "true");
        assertThatThrownBy(() -> LiveTestSupport.assumeOrRequire(false, "needs admin"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("needs admin");
        assertThatCode(() -> LiveTestSupport.assumeOrRequire(true, "needs admin")).doesNotThrowAnyException();
    }

    @Test
    void destructiveCleanupOnlyTargetsTestNamespaces() {
        for (String allowed : List.of("schema_synchronizer_test", "SCHEMA_SYNCHRONIZER_TEST", "ss", "SS",
                "schema_sync", "SCHEMA_SYNC", "test_ss_0a1b2c3d", "test_ssx0a1b2c3d",
                "schema_synchronizer_test_0a1b2c3d", "schema_applier_test", "test",
                "apply_case_0123456789abcdef0123456789abcdef", "applyxcase_0123456789abcdef0123456789abcdef")) {
            assertThat(LiveTestSupport.isTestNamespace(allowed)).as(allowed).isTrue();
            assertThat(LiveTestSupport.requireTestNamespace(allowed, "db")).isEqualTo(allowed);
        }
        for (String refused : List.of("master", "public", "prod", "latest", "latest_orders", "contest",
                "attestation", "testing", "ss_prod", "schema_synchronizer", "schemaxsynchronizer",
                "apply_case_0123", "apply_case_0123456789ABCDEF0123456789ABCDEF_", "", " ")) {
            assertThat(LiveTestSupport.isTestNamespace(refused)).as(refused).isFalse();
            assertThatThrownBy(() -> LiveTestSupport.requireTestNamespace(refused, "db"))
                    .as(refused)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing destructive test cleanup");
        }
        assertThat(LiveTestSupport.isTestNamespace(null)).isFalse();
    }

    @Test
    void randomCredentialsDifferPerCall() {
        assertThat(LiveTestSupport.randomHex(8)).hasSize(16).matches("[0-9a-f]+")
                .isNotEqualTo(LiveTestSupport.randomHex(8));
    }

    private static ExtensionContext context(Class<?> type) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getTestClass()).thenReturn(Optional.of(type));
        return context;
    }
}
