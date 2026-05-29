package com.poc.adls;

import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reusable, parameterized ADLS Gen2 file service.
 *
 * Responsibilities:
 *   - readFile(fileName)          → ADLS Gen2 → App Service
 *   - writeFile(fileName,content) → App Service → ADLS Gen2
 *
 * Security controls:
 *   - Path traversal prevention   → strict filename regex allowlist
 *   - File size cap on read       → 10MB hard limit
 *   - Content size cap on write   → enforced by Spring (application.properties)
 *   - Error sanitization          → Bearer/SAS tokens stripped from logs
 *   - Never throws to caller      → always returns TransferResult
 *   - Immutable client            → Spring-managed singleton, thread-safe
 */
@Service
public final class AdlsService {

    private static final Logger LOG = LoggerFactory.getLogger(AdlsService.class);

    // 10 MB read limit — guards against OOM on oversized files
    private static final int MAX_FILE_BYTES    = 10 * 1024 * 1024;

    // 500 char preview — truncates large files in response body
    private static final int MAX_PREVIEW_CHARS = 500;

    /**
     * Filename allowlist — alphanumeric, dots, hyphens, underscores only.
     * Must start with alphanumeric. Max 255 chars.
     * Rejects: path traversal (..), directory separators (/ \), null bytes.
     */
    private static final Pattern SAFE_FILENAME =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$");

    // Spring-managed singleton — injected by AdlsConfig @Bean
    // Thread-safe: DataLakeFileSystemClient is immutable after construction
    private final DataLakeFileSystemClient fsClient;

    public AdlsService(final DataLakeFileSystemClient fsClient) {
        this.fsClient = Objects.requireNonNull(fsClient, "fsClient must not be null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ — ADLS Gen2 → App Service
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads a text file from ADLS Gen2.
     * Validates fileName, probes file existence, enforces size limit,
     * streams content into memory, returns safe preview.
     *
     * @param fileName file path within the container — validated against allowlist
     * @return TransferResult.success() with content, or TransferResult.failure() with error
     */
    public TransferResult readFile(final String fileName) {
        LOG.info("Read request — file: {}", fileName);

        try {
            validateFileName(fileName);

            final DataLakeFileClient fileClient = fsClient.getFileClient(fileName);

            // Lightweight HEAD call — verifies file exists + gets metadata
            final var properties = fileClient.getProperties();
            final long fileSize  = properties.getFileSize();

            LOG.info("File found — size: {} bytes", fileSize);

            // Guard: reject files exceeding safe in-memory threshold
            if (fileSize > MAX_FILE_BYTES) {
                return TransferResult.failure(fileName,
                        String.format("File size %d bytes exceeds %d byte limit",
                                fileSize, MAX_FILE_BYTES));
            }

            // Stream content into bounded output stream
            try (final var outputStream = new ByteArrayOutputStream((int) fileSize)) {
                fileClient.read(outputStream);

                final byte[] bytes = outputStream.toByteArray();
                final String content = new String(bytes, StandardCharsets.UTF_8);

                // Safe preview — never return unbounded content in response
                final String preview = content.length() > MAX_PREVIEW_CHARS
                        ? content.substring(0, MAX_PREVIEW_CHARS) + "...[truncated]"
                        : content;

                LOG.info("Read success — file: {} bytes: {}", fileName, bytes.length);
                return TransferResult.success(fileName, bytes.length, preview);
            }

        } catch (final IllegalArgumentException ex) {
            // Filename validation failure — client error, not logged at ERROR level
            LOG.warn("Read rejected — invalid file name: {} reason: {}", fileName, ex.getMessage());
            return TransferResult.failure(fileName, ex.getMessage());

        } catch (final Exception ex) {
            final String sanitized = sanitizeError(ex.getMessage());
            LOG.error("Read failed — file: {} error: {}", fileName, sanitized);
            return TransferResult.failure(fileName, sanitized);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WRITE — App Service → ADLS Gen2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes text content to ADLS Gen2 as a new or overwritten file.
     * Validates fileName, encodes content as UTF-8, creates/overwrites file,
     * appends content, flushes to commit.
     *
     * Content size limit enforced upstream by Spring Boot (10MB via application.properties).
     *
     * @param fileName file path within the container — validated against allowlist
     * @param content  text content to write — must not be null
     * @return TransferResult.written() on success, or TransferResult.failure() with error
     */
    public TransferResult writeFile(final String fileName, final String content) {
        LOG.info("Write request — file: {}", fileName);

        try {
            validateFileName(fileName);
            Objects.requireNonNull(content, "Content must not be null");

            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            final DataLakeFileClient fileClient = fsClient.getFileClient(fileName);

            // Create or overwrite — true = overwrite existing file
            fileClient.create(true);

            // Append content block starting at offset 0
            try (final var inputStream = new ByteArrayInputStream(bytes)) {
                fileClient.append(inputStream, 0, bytes.length);
            }

            // Flush and commit — makes file readable
            // overwrite=true closes any incomplete upload
            fileClient.flush(bytes.length, true);

            LOG.info("Write success — file: {} bytes: {}", fileName, bytes.length);
            return TransferResult.written(fileName, bytes.length);

        } catch (final IllegalArgumentException | NullPointerException ex) {
            LOG.warn("Write rejected — invalid request: {}", ex.getMessage());
            return TransferResult.failure(fileName, ex.getMessage());

        } catch (final Exception ex) {
            final String sanitized = sanitizeError(ex.getMessage());
            LOG.error("Write failed — file: {} error: {}", fileName, sanitized);
            return TransferResult.failure(fileName, sanitized);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates fileName against strict allowlist.
     * Rejects path traversal, directory separators, null bytes, blank names.
     *
     * @throws IllegalArgumentException if fileName is invalid
     */
    private static void validateFileName(final String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("File name must not be blank");
        }
        // Check for null bytes — defense against null byte injection
        if (fileName.contains("\0")) {
            throw new IllegalArgumentException("File name contains illegal null byte");
        }
        // Allowlist check — rejects .., /, \, and all other unsafe characters
        if (!SAFE_FILENAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "File name contains invalid characters. " +
                    "Allowed: alphanumeric, dots, hyphens, underscores (max 255 chars)");
        }
    }

    /**
     * Strips token-like patterns from error messages before logging.
     * Defensive measure against SDK errors containing auth token fragments.
     */
    private static String sanitizeError(final String message) {
        if (message == null) return "Unknown error";
        // Redact Bearer tokens and SAS query parameters
        return message.replaceAll(
                "(?i)(bearer|sig|sv|se|sp|sr|st)=[^&\\s]+", "[REDACTED]");
    }
}
