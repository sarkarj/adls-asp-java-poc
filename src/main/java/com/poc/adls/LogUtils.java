package com.poc.adls;

/**
 * Centralized log sanitization utility.
 *
 * Prevents CWE-117 Improper Output Neutralization for Logs (Log Injection).
 * SLF4J parameterized logging {} prevents format-string attacks but does NOT
 * strip newline/carriage-return characters — these must be sanitized explicitly
 * before any user-controlled value is passed to a log statement.
 *
 * Single source of truth — avoids duplication across service + controller.
 * Package-private — not part of public API.
 */
final class LogUtils {

    private LogUtils() {
        // Utility class — not instantiable
    }

    /**
     * Sanitizes a user-controlled string value for safe log output.
     * Replaces newlines, carriage returns, and tabs with underscores.
     *
     * Attack prevented:
     *   Input:  "file=test%0AFATAL:+system+compromised"
     *   Before: LOG writes two lines including fake FATAL entry
     *   After:  LOG writes "test_FATAL:+system+compromised" (single line)
     *
     * @param value user-controlled input — may be null
     * @return sanitized string safe for logging, never null
     */
    static String sanitize(final String value) {
        if (value == null) return "[null]";
        return value.replaceAll("[\r\n\t]", "_");
    }
}
