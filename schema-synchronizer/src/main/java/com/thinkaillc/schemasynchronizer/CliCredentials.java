// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern URL_SECRET = Pattern.compile(
            "(?i)((?:password|pwd|passwd)\\s*=\\s*)[^;&]*|(//[^/:@;?]*:)[^@/]*@");

    /**
     * Opens a connection; a failure's message (JDK drivers include the URL, e.g. "No suitable driver
     * found for jdbc:...") has URL passwords replaced by {@code ***}, and the unredacted cause is dropped.
     */
    static Connection connect(String url, String user, String password) throws SQLException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException failure) {
            String message = failure.getMessage() == null ? "cannot connect" : failure.getMessage();
            SQLException redacted = new SQLException(redactUrlSecrets(message),
                    failure.getSQLState(), failure.getErrorCode());
            redacted.setStackTrace(failure.getStackTrace());
            throw redacted;
        }
    }

    /** {@code text} with {@code password=}/{@code pwd=} values and {@code //user:secret@} userinfo masked. */
    static String redactUrlSecrets(String text) {
        Matcher matcher = URL_SECRET.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) != null ? matcher.group(1) + "***" : matcher.group(2) + "***@";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Size of the history {@code applied_by} column; bytes on Oracle and SQL Server. */
    static final int ACTOR_MAX_BYTES = 200;

    /** Operator / deploy principal recorded in schema history (at most 200 UTF-8 bytes). */
    static String historyActor() {
        String fromEnv = System.getenv(ACTOR_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return truncateUtf8(fromEnv.trim(), ACTOR_MAX_BYTES);
        }
        String user = System.getProperty("user.name", "unknown");
        return truncateUtf8(user == null || user.isBlank() ? "unknown" : user.trim(), ACTOR_MAX_BYTES);
    }

    /** The longest prefix of whole code points whose UTF-8 encoding fits {@code maxBytes}. */
    static String truncateUtf8(String value, int maxBytes) {
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }
}
