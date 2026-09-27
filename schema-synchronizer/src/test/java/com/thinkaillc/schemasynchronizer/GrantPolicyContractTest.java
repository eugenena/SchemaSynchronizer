// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code GRANT} is allowed only as {@code GRANT … ON object TO grantee}: ON and TO as unquoted keywords
 * outside parentheses, in that order, on a non-server object. The check is a token scan, not a regex.
 */
class GrantPolicyContractTest {

    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test
    void objectGrantsAreAccepted() {
        for (String sql : List.of(
                "GRANT SELECT ON items TO app_reader",
                "GRANT SELECT, INSERT ON items TO app_writer, app_admin",
                "GRANT SELECT (id, label) ON items TO app_reader",
                "GRANT SELECT ON \"order\" TO \"Reporting\"",
                "GRANT SELECT ON public.items TO app_reader WITH GRANT OPTION")) {
            assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe(sql)).as(sql).doesNotThrowAnyException();
        }
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe("GRANT SELECT ON `order` TO app_reader",
                DatabaseDialect.MYSQL)).doesNotThrowAnyException();
        assertThatCode(() -> NonDestructiveSqlPolicy.requireSafe("GRANT SELECT ON [order] TO app_reader",
                DatabaseDialect.SQLSERVER)).doesNotThrowAnyException();
    }

    @Test
    void grantsWithoutATopLevelOnThenToAreRejected() {
        for (String sql : List.of(
                "GRANT app_admin TO app_user",
                "GRANT SELECT TO app_user",
                "GRANT \"ON\" TO app_user",
                "GRANT SELECT ON items",
                "GRANT SELECT TO app_user ON items",
                "GRANT f(ON x TO y) TO app_user",
                "GRANT SELECT /* ON items TO */ TO app_user",
                "GRANT SELECT ON items \"TO\" app_user")) {
            assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(sql)).as(sql)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void serverAndRoleTargetsAreRejectedEvenWithOnAndTo() {
        for (String sql : List.of(
                "GRANT CONNECT ON DATABASE app TO app_user",
                "GRANT USAGE ON FOREIGN SERVER remote TO app_user",
                "GRANT EXECUTE ON SYS.DBMS_LOCK TO app_user",
                "GRANT SELECT ON items TO app_user WITH ADMIN OPTION")) {
            assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(sql)).as(sql)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void grantsOnObjectToIsOrderSensitiveAndDepthAware() {
        assertThat(grants("GRANT SELECT ON items TO r")).isTrue();
        assertThat(grants("GRANT SELECT TO r ON items")).isFalse();
        assertThat(grants("GRANT SELECT (ON) TO r")).isFalse();
        assertThat(grants("GRANT SELECT ON (TO) x")).isFalse();
        assertThat(grants("GRANT SELECT \"ON\" items TO r")).isFalse();
        assertThat(grants("GRANT")).isFalse();
    }

    @Test
    void twoHundredKilobyteAcceptedGrantIsLinear() {
        String sql = "GRANT SELECT ON items TO " + "app_user_x, ".repeat(200_000 / 12) + "app_user_last";
        assertThat(sql.length()).isGreaterThan(200_000);
        assertTimeoutPreemptively(BUDGET, () -> NonDestructiveSqlPolicy.requireSafe(sql));
    }

    @Test
    void twoHundredKilobyteAdversarialGrantsFailFast() {
        List<String> adversarial = List.of(
                "GRANT " + "SELECT ON ".repeat(200_000 / 10),
                "GRANT SELECT " + "(ON ".repeat(200_000 / 4) + ")".repeat(200_000 / 4) + " TO r",
                "GRANT SELECT ON " + " ".repeat(200_000) + "DATABASE x TO r",
                "GRANT " + "ON TO ".repeat(200_000 / 6) + "ON DATABASE x");
        for (String sql : adversarial) {
            assertThat(sql.length()).isGreaterThanOrEqualTo(200_000);
            assertTimeoutPreemptively(BUDGET, () -> assertThatThrownBy(() -> NonDestructiveSqlPolicy.requireSafe(sql))
                    .isInstanceOf(IllegalArgumentException.class));
        }
    }

    private static boolean grants(String sql) {
        return NonDestructiveSqlPolicy.grantsOnObjectTo(SqlTokenizer.tokenize(sql, SqlLexer.Mode.POSTGRES));
    }
}
