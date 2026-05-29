package com.poc.adls;

import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reusable, parameterized ADLS Gen2 file service.
 *
 * Responsibilities:
 *   - readFile(fileName)              → ADLS Gen2 → App Service
 *   - writeGeneratedContent(fileName) → App Service generates content → ADLS Gen2
 *
 * Security controls:
 *   - Path traversal prevention   → strict filename regex allowlist
 *   - File size cap on read       → 10MB hard limit
 *   - Error sanitization          → Bearer/SAS tokens stripped from logs
 *   - Never throws to caller      → always returns TransferResult
 *   - Immutable client            → Spring-managed singleton, thread-safe
 */
@Service
public final class AdlsService {

    private static final Logger LOG = LoggerFactory.getLogger(AdlsService.class);

    private static final int MAX_FILE_BYTES    = 10 * 1024 * 1024;
    private static final int MAX_PREVIEW_CHARS = 500;

    /**
     * Filename allowlist — alphanumeric, dots, hyphens, underscores only.
     * Must start with alphanumeric. Max 255 chars.
     * Rejects: path traversal (..), directory separators (/ \), null bytes.
     */
    private static final Pattern SAFE_FILENAME =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$");

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

            final var properties = fileClient.getProperties();
            final long fileSize  = properties.getFileSize();

            LOG.info("File found — size: {} bytes", fileSize);

            if (fileSize > MAX_FILE_BYTES) {
                return TransferResult.failure(fileName,
                        String.format("File size %d bytes exceeds %d byte limit",
                                fileSize, MAX_FILE_BYTES));
            }

            try (final var outputStream = new ByteArrayOutputStream((int) fileSize)) {
                fileClient.read(outputStream);

                final byte[] bytes = outputStream.toByteArray();
                final String content = new String(bytes, StandardCharsets.UTF_8);

                final String preview = content.length() > MAX_PREVIEW_CHARS
                        ? content.substring(0, MAX_PREVIEW_CHARS) + "...[truncated]"
                        : content;

                LOG.info("Read success — file: {} bytes: {}", fileName, bytes.length);
                return TransferResult.success(fileName, bytes.length, preview);
            }

        } catch (final IllegalArgumentException ex) {
            LOG.warn("Read rejected — invalid file name: {} reason: {}", fileName, ex.getMessage());
            return TransferResult.failure(fileName, ex.getMessage());

        } catch (final Exception ex) {
            final String sanitized = sanitizeError(ex.getMessage());
            LOG.error("Read failed — file: {} error: {}", fileName, sanitized);
            return TransferResult.failure(fileName, sanitized);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WRITE — App Service generated content → ADLS Gen2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates content internally from App Service environment and writes
     * it to ADLS Gen2. No external input required — ASP is the content source.
     *
     * Generated content includes:
     *   - App Service hostname     (WEBSITE_HOSTNAME env var)
     *   - App Service instance ID  (WEBSITE_INSTANCE_ID env var)
     *   - App Service region       (REGION_NAME env var)
     *   - Application version
     *   - Write timestamp
     *
     * Proves ASP → ADLS connectivity with ASP as the sole origin of content.
     *
     * @param fileName target file path in ADLS container — validated against allowlist
     * @return TransferResult.written() on success, or TransferResult.failure() with error
     */
    public TransferResult writeGeneratedContent(final String fileName) {
        LOG.info("Write request — file: {} (ASP-generated content)", fileName);

        try {
            validateFileName(fileName);

            // Azure App Service runtime injects these env vars automatically
            final String hostname   = resolveEnv("WEBSITE_HOSTNAME",    "localhost");
            final String instanceId = resolveEnv("WEBSITE_INSTANCE_ID", "local-instance");
            final String region     = resolveEnv("REGION_NAME",         "unknown-region");
            final Instant timestamp = Instant.now();

            // Content generated entirely by App Service — no external input
            final String content = String.format(
                    "╔══════════════════════════════════════════════════╗%n" +
                    "║      Generated by Azure App Service  ✅           ║%n" +
                    "╚══════════════════════════════════════════════════╝%n" +
                    "Source      : Azure App Service (ASP → ADLS)%n"       +
                    "Hostname    : %s%n"                                    +
                    "Instance ID : %s%n"                                    +
                    "Region      : %s%n"                                    +
                    "Timestamp   : %s%n"                                    +
                    "Written to  : %s%n"                                    +
                    "App Version : 2.0.0%n",
                    hostname, instanceId, region, timestamp, fileName);

            return writeToAdls(fileName, content);

        } catch (final IllegalArgumentException ex) {
            LOG.warn("Write rejected — invalid file name: {} reason: {}", fileName, ex.getMessage());
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
     * Writes UTF-8 encoded content to ADLS Gen2.
     * Creates file if absent; overwrites if present.
     */
    private TransferResult writeToAdls(
            final String fileName, final String content) throws Exception {

        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        final DataLakeFileClient fileClient = fsClient.getFileClient(fileName);

        fileClient.create(true);

        try (final var inputStream = new ByteArrayInputStream(bytes)) {
            fileClient.append(inputStream, 0, bytes.length);
        }

        fileClient.flush(bytes.length, true);

        LOG.info("Write success — file: {} bytes: {}", fileName, bytes.length);
        return TransferResult.written(fileName, bytes.length);
    }

    /**
     * Validates fileName against strict allowlist.
     * Rejects path traversal, directory separators, null bytes, blank names.
     */
    private static void validateFileName(final String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("File name must not be blank");
        }
        if (fileName.contains("\0")) {
            throw new IllegalArgumentException("File name contains illegal null byte");
        }
        if (!SAFE_FILENAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "File name contains invalid characters. " +
                    "Allowed: alphanumeric, dots, hyphens, underscores (max 255 chars)");
        }
    }

    /**
     * Reads an env var with a safe fallback — never returns null.
     */
    private static String resolveEnv(final String key, final String fallback) {
        return Optional.ofNullable(System.getenv(key))
                .filter(v -> !v.isBlank())
                .orElse(fallback);
    }

    /**
     * Strips token-like patterns from error messages before logging.
     */
    private static String sanitizeError(final String message) {
        if (message == null) return "Unknown error";
        return message.replaceAll(
                "(?i)(bearer|sig|sv|se|sp|sr|st)=[^&\\s]+", "[REDACTED]");
    }
}
