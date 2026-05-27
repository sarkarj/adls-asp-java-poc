package com.poc.adls;

import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable, parameterized ADLS file service.
 * All operations return TransferResult — never throw to caller.
 * Stateless beyond the injected filesystem client.
 */
public final class AdlsFileService {

    private static final Logger LOG =
            Logger.getLogger(AdlsFileService.class.getName());

    // Max bytes read into memory — guards against oversized files
    private static final int MAX_PREVIEW_BYTES = 500;
    private static final int MAX_FILE_BYTES    = 10 * 1024 * 1024; // 10 MB hard cap

    private final DataLakeFileSystemClient fsClient;

    /**
     * @param fsClient pre-authenticated filesystem client — must not be null
     */
    public AdlsFileService(final DataLakeFileSystemClient fsClient) {
        this.fsClient = Objects.requireNonNull(fsClient,
                "fsClient must not be null");
    }

    /**
     * Reads a text file from ADLS Gen2 and returns its content as TransferResult.
     * Parameterized — fileName drives which file is read, no hardcoding.
     *
     * @param fileName path within the container (e.g. "sample.txt")
     * @return TransferResult — success with content or failure with error message
     */
    public TransferResult readFile(final String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        if (fileName.isBlank()) {
            return TransferResult.failure(fileName, "fileName must not be blank");
        }

        LOG.info(String.format("Reading file: %s", fileName));

        try {
            final DataLakeFileClient fileClient =
                    fsClient.getFileClient(fileName);

            // Probe file existence + metadata before reading content
            final var properties = fileClient.getProperties();
            final long fileSize   = properties.getFileSize();

            LOG.info(String.format("File found — size: %d bytes", fileSize));

            // Guard: reject files exceeding safe memory threshold
            if (fileSize > MAX_FILE_BYTES) {
                return TransferResult.failure(fileName,
                    String.format("File size %d bytes exceeds limit of %d bytes",
                        fileSize, MAX_FILE_BYTES));
            }

            // Stream file content into memory-bounded output stream
            try (var outputStream = new ByteArrayOutputStream((int) fileSize)) {
                fileClient.read(outputStream);

                final byte[] rawBytes = outputStream.toByteArray();
                final String fullContent =
                        new String(rawBytes, StandardCharsets.UTF_8);

                // Safe preview — truncated to MAX_PREVIEW_BYTES
                final String preview = fullContent.length() > MAX_PREVIEW_BYTES
                        ? fullContent.substring(0, MAX_PREVIEW_BYTES) + "...[truncated]"
                        : fullContent;

                LOG.info(String.format(
                    "File read successfully — %d bytes transferred", rawBytes.length));

                return TransferResult.success(fileName, rawBytes.length, preview);
            }

        } catch (Exception ex) {
            // Sanitized error — exception message only, no stack trace logged
            // to prevent potential token/credential leakage in SDK internals
            final String sanitizedError = sanitizeError(ex.getMessage());
            LOG.log(Level.SEVERE,
                "File read failed for [{0}]: {1}",
                new Object[]{fileName, sanitizedError});
            return TransferResult.failure(fileName, sanitizedError);
        }
    }

    /**
     * Strips any token-like patterns from error messages before logging.
     * Defensive measure against SDK error messages containing auth details.
     */
    private static String sanitizeError(final String message) {
        if (message == null) return "Unknown error";
        // Redact anything resembling a Bearer token or SAS token
        return message.replaceAll("(?i)(bearer|sig|sv|se|sp|sr|st)=[^&\\s]+", "[REDACTED]");
    }
}
