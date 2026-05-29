package com.poc.adls;

import java.time.Instant;

/**
 * Immutable value record representing the outcome of an ADLS file operation.
 *
 * Uses Java 21 record — zero boilerplate, thread-safe, null-safe by contract.
 * Validated in compact canonical constructor — invariants enforced at creation.
 *
 * Factories:
 *   TransferResult.success(fileName, bytes, preview) → read operation succeeded
 *   TransferResult.written(fileName, bytes)           → write operation succeeded
 *   TransferResult.failure(fileName, errorMessage)    → any operation failed
 */
public record TransferResult(
        boolean success,
        String fileName,
        long bytesTransferred,
        String contentPreview,   // populated for reads; null for writes
        Instant timestamp,
        String errorMessage      // populated on failure; null on success
) {

    /** Compact canonical constructor — validates invariants at record creation */
    public TransferResult {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (success && bytesTransferred < 0) {
            throw new IllegalArgumentException("bytesTransferred must be >= 0 on success");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factories
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read success — ADLS Gen2 → App Service.
     *
     * @param fileName       file that was read
     * @param bytesRead      number of bytes transferred
     * @param contentPreview safe truncated preview of file content
     */
    public static TransferResult success(
            final String fileName,
            final long bytesRead,
            final String contentPreview) {
        return new TransferResult(
                true, fileName, bytesRead,
                contentPreview, Instant.now(), null);
    }

    /**
     * Write success — App Service → ADLS Gen2.
     * contentPreview is null — content was written to ADLS, not returned.
     *
     * @param fileName     file that was written
     * @param bytesWritten number of bytes committed to ADLS
     */
    public static TransferResult written(
            final String fileName,
            final long bytesWritten) {
        return new TransferResult(
                true, fileName, bytesWritten,
                null, Instant.now(), null);
    }

    /**
     * Operation failure — read or write.
     * bytesTransferred = 0, contentPreview = null.
     *
     * @param fileName     file that was targeted
     * @param errorMessage sanitized error description (no tokens/credentials)
     */
    public static TransferResult failure(
            final String fileName,
            final String errorMessage) {
        return new TransferResult(
                false, fileName, 0,
                null, Instant.now(), errorMessage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // toString
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        if (!success) {
            return String.format(
                    "TransferResult[FAILURE file=%s error=%s at=%s]",
                    fileName, errorMessage, timestamp);
        }
        return contentPreview != null
                ? String.format(
                        "TransferResult[READ file=%s bytes=%d at=%s]",
                        fileName, bytesTransferred, timestamp)
                : String.format(
                        "TransferResult[WRITE file=%s bytes=%d at=%s]",
                        fileName, bytesTransferred, timestamp);
    }
}
