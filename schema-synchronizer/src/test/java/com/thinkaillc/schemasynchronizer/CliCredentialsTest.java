// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

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
    void historyActorFallsBackToUserName() {
        String actor = CliCredentials.historyActor();
        assertThat(actor).isNotBlank();
        assertThat(actor.length()).isLessThanOrEqualTo(200);
    }
}
