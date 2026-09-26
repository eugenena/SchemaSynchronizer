// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

/**
 * CLI credential helper. Passwords must come from {@code SCHEMA_DB_PASSWORD};
 * argv must use {@code -} so credentials never appear in process listings.
 */
final class CliCredentials {
    static final String PASSWORD_ENV = "SCHEMA_DB_PASSWORD";
    static final String ACTOR_ENV = "SCHEMA_SYNCHRONIZER_ACTOR";

    private CliCredentials() {
    }

    /**
     * Resolves the database password. Only {@code -} is accepted as the argv token.
     */
    static String requirePasswordFromEnv(String passwordArgument) {
        if (!"-".equals(passwordArgument)) {
            throw new IllegalArgumentException(
                    "Pass the database password via " + PASSWORD_ENV
                            + " and use '-' as the password argument "
                            + "(literal passwords on the command line are not allowed)");
        }
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(PASSWORD_ENV + " must be set when the password argument is '-'");
        }
        return password;
    }

    /** Operator / deploy principal recorded in schema history (max 200 chars). */
    static String historyActor() {
        String fromEnv = System.getenv(ACTOR_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return truncate(fromEnv.trim(), 200);
        }
        String user = System.getProperty("user.name", "unknown");
        return truncate(user == null || user.isBlank() ? "unknown" : user.trim(), 200);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
