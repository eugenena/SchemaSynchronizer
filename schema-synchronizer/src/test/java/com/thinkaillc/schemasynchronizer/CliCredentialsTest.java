// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCredentialsTest {

    @Test
    void rejectsLiteralPasswordOnArgv() {
        assertThatThrownBy(() -> CliCredentials.requirePasswordFromEnv("secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCHEMA_DB_PASSWORD")
                .hasMessageContaining("'-'");
    }

    @Test
    void historyActorPrefersTrimmedEnvironmentValue() throws Exception {
        assertThat(actor(Map.of(CliCredentials.ACTOR_ENV, "  deploy-bot  "), "someone-else"))
                .isEqualTo("deploy-bot");
    }

    @Test
    void historyActorFallsBackToUserNameWhenEnvironmentIsUnsetOrBlank() throws Exception {
        assertThat(actor(Map.of(), " operator ")).isEqualTo("operator");
        assertThat(actor(Map.of(CliCredentials.ACTOR_ENV, "   "), "operator")).isEqualTo("operator");
    }

    @Test
    void historyActorIsUnknownWhenUserNameIsBlank() throws Exception {
        assertThat(actor(Map.of(), "   ")).isEqualTo("unknown");
    }

    @Test
    void historyActorIsTruncatedTo200Characters() throws Exception {
        String longActor = "a".repeat(199) + "bc" + "z".repeat(50);
        assertThat(actor(Map.of(CliCredentials.ACTOR_ENV, longActor), "operator"))
                .isEqualTo("a".repeat(199) + "b");
        assertThat(actor(Map.of(), "u".repeat(250))).isEqualTo("u".repeat(200));
        assertThat(actor(Map.of(CliCredentials.ACTOR_ENV, "x".repeat(200)), "operator"))
                .isEqualTo("x".repeat(200));
    }

    @Test
    void passwordComesFromTheEnvironmentWhenTheArgumentIsDash() throws Exception {
        ChildJvm.Result result = ChildJvm.run(Map.of(CliCredentials.PASSWORD_ENV, "s3cr et"), List.of(),
                "password", "-").assertSuccess();
        assertThat(result.output()).contains(ChildJvm.Launcher.PASSWORD_MARKER + "s3cr et|");
    }

    @Test
    void dashWithoutAnEnvironmentPasswordIsRejected() throws Exception {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of(CliCredentials.PASSWORD_ENV, "  "))) {
            ChildJvm.Result result = ChildJvm.run(env, List.of(), "password", "-");
            assertThat(result.exitCode()).as("env=%s", env).isEqualTo(1);
            assertThat(result.output()).contains("SCHEMA_DB_PASSWORD must be set")
                    .doesNotContain(ChildJvm.Launcher.PASSWORD_MARKER);
        }
    }

    @Test
    void literalPasswordIsRejectedEvenWhenTheEnvironmentHasOne() throws Exception {
        ChildJvm.Result result = ChildJvm.run(Map.of(CliCredentials.PASSWORD_ENV, "env-pw"), List.of(),
                "password", "argv-pw");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("literal passwords on the command line are not allowed")
                .doesNotContain("env-pw");
    }

    private static String actor(Map<String, String> env, String userName) throws Exception {
        String output = ChildJvm.run(env, List.of("-Duser.name=" + userName), "actor").assertSuccess().output();
        int start = output.indexOf(ChildJvm.Launcher.ACTOR_MARKER);
        assertThat(start).as(output).isNotNegative();
        return output.substring(start + ChildJvm.Launcher.ACTOR_MARKER.length(), output.indexOf('|', start));
    }
}
