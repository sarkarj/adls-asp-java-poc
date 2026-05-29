package com.poc.adls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller for ADLS Gen2 ↔ App Service bidirectional file transfer.
 *
 * Endpoints:
 *   GET  /read?file={fileName}  → reads file from ADLS → returns content
 *   POST /write?file={fileName} → App Service generates content → writes to ADLS
 *
 * Health liveness probe handled by Spring Actuator at /actuator/health.
 *
 * Security:
 *   - @GetMapping / @PostMapping — explicit method binding, no wildcard methods
 *   - @RequestParam required=true (default) — missing param returns 400
 *   - text/plain responses — no JSON, no serialization attack surface
 *   - @ExceptionHandler — consistent error responses, no stack traces exposed
 *   - No @RequestBody on write — ASP generates content, no external input accepted
 */
@RestController
public final class AdlsController {

    private static final Logger LOG = LoggerFactory.getLogger(AdlsController.class);

    private final AdlsService adlsService;

    public AdlsController(final AdlsService adlsService) {
        this.adlsService = Objects.requireNonNull(adlsService, "adlsService must not be null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /read?file={fileName} — ADLS Gen2 → App Service
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads a file from ADLS Gen2 and returns its content.
     *
     * @param fileName file path within the ADLS container (query param)
     * @return 200 + file content on success, 400 on invalid input, 500 on failure
     *
     * Example: GET /read?file=sample.txt
     */
    @GetMapping(value = "/read", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> readFile(
            @RequestParam("file") final String fileName) {

        LOG.info("GET /read — file: {}", fileName);
        final TransferResult result = adlsService.readFile(fileName);

        if (result.success()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(formatReadResponse(result));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body(formatErrorResponse(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /write?file={fileName} — App Service → ADLS Gen2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * App Service generates content from its own environment metadata
     * and writes it to ADLS Gen2 as a file.
     *
     * No request body required or accepted — ASP is the sole content source.
     * Generated content: hostname, instance ID, region, timestamp, version.
     *
     * @param fileName target file path in ADLS container (query param)
     * @return 201 + confirmation on success, 400 on invalid input, 500 on failure
     *
     * Example: POST /write?file=output.txt
     */
    @PostMapping(value = "/write", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> writeFile(
            @RequestParam("file") final String fileName) {

        LOG.info("POST /write — file: {} (ASP-generated content)", fileName);
        final TransferResult result = adlsService.writeGeneratedContent(fileName);

        if (result.success()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(formatWriteResponse(result));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body(formatErrorResponse(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception Handlers — consistent error responses, no stack traces
    // ─────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingParam(
            final MissingServletRequestParameterException ex) {
        LOG.warn("Missing parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(String.format("Missing required query parameter: %s%n" +
                        "Usage: GET  /read?file=filename.txt%n" +
                        "       POST /write?file=filename.txt%n",
                        ex.getParameterName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleMethodNotAllowed(
            final HttpRequestMethodNotSupportedException ex) {
        LOG.warn("Method not allowed: {}", ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.TEXT_PLAIN)
                .body(String.format("Method not allowed: %s%n" +
                        "Supported: GET /read, POST /write%n",
                        ex.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(final Exception ex) {
        LOG.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal server error — check application logs\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private response formatters
    // ─────────────────────────────────────────────────────────────────────────

    private static String formatReadResponse(final TransferResult result) {
        return String.format(
                "╔══════════════════════════════════════════╗%n" +
                "║        READ SUCCESSFUL  ✅  (ADLS→ASP)   ║%n" +
                "╚══════════════════════════════════════════╝%n" +
                "File      : %s%n" +
                "Bytes     : %d%n" +
                "Timestamp : %s%n" +
                "Content   : %s%n",
                result.fileName(),
                result.bytesTransferred(),
                result.timestamp(),
                result.contentPreview());
    }

    private static String formatWriteResponse(final TransferResult result) {
        return String.format(
                "╔══════════════════════════════════════════╗%n" +
                "║       WRITE SUCCESSFUL  ✅  (ASP→ADLS)   ║%n" +
                "╚══════════════════════════════════════════╝%n" +
                "File      : %s%n" +
                "Bytes     : %d%n" +
                "Timestamp : %s%n",
                result.fileName(),
                result.bytesTransferred(),
                result.timestamp());
    }

    private static String formatErrorResponse(final TransferResult result) {
        return String.format(
                "OPERATION FAILED ❌%n" +
                "File      : %s%n" +
                "Error     : %s%n" +
                "Timestamp : %s%n",
                result.fileName(),
                result.errorMessage(),
                result.timestamp());
    }
}
