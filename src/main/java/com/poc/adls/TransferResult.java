package com.poc.adls;

import java.time.Instant;

/**
 * Immutable value record representing the outcome of an ADLS file transfer.
 * Uses Java 21 record — zero boilerplate, thread-safe, null-safe by contract.
 */
public record TransferResult(
        boolean success,
        String fileName,
        long bytesTransferred,
        String contentPreview,
        Instant timestamp,
        String errorMessage
) {
    /** Compact canonical constructor — validates invariants */
    public TransferResult {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (success && bytesTransferred < 0) {
            throw new IllegalArgumentException("bytesTransferred must be >= 0 on success");
        }
    }

    /** Factory — success path */
    public static TransferResult success(
            String fileName,
            long bytesTransferred,
            String contentPreview) {
        return new TransferResult(
                true, fileName, bytesTransferred,
                contentPreview, Instant.now(), null);
    }

    /** Factory — failure path */
    public static TransferResult failure(String fileName, String errorMessage) {
        return new TransferResult(
                false, fileName, 0,
                null, Instant.now(), errorMessage);
    }

    @Override
    public String toString() {
        return success
            ? String.format("TransferResult[SUCCESS file=%s bytes=%d preview='%s' at=%s]",
                fileName, bytesTransferred, contentPreview, timestamp)
            : String.format("TransferResult[FAILURE file=%s error=%s at=%s]",
                fileName, errorMessage, timestamp);
    }
}
