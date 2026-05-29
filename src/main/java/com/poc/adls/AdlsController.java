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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller for ADLS Gen2 ↔ App Service bidirectional file transfer.
 *
 * Endpoints:
 *   GET  /read?file={fileName}  → reads file from ADLS → returns content
 *   POST /write?file={fileName} → writes request body to ADLS as file
 *
 * Health liveness probe handled by Spring Actuator at /actuator/health.
 *
 * Security:
 *   - @GetMapping / @PostMapping — explicit method binding, no wildcard methods
 *   - @RequestParam required=true (default) — missing param returns 400
 *   - text/plain responses — no JSON, no serialization attack surface
 *   - @ExceptionHandler — consistent error responses, no stack traces exposed
 *   - All business logic delegated to AdlsService — controller is routing only
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
     * Writes the request body as a file to ADLS Gen2.
     * Creates the file if it does not exist; overwrites if it does.
     *
     * Content-Type: text/plain required.
     * Max body size: 10MB (enforced by Spring Boot — see application.properties).
     *
     * @param fileName file path within the ADLS container (query param)
     * @param content  text content to write (request body)
     * @return 201 + confirmation on success, 400 on invalid input, 500 on failure
     *
     * Example: POST /write?file=output.txt  Body: "Hello from App Service"
     */
    @PostMapping(
            value   = "/write",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> writeFile(
            @RequestParam("file") final String fileName,
            @RequestBody final String content) {

        LOG.info("POST /write — file: {} contentLength: {}", fileName,
                content == null ? 0 : content.length());

        final TransferResult result = adlsService.writeFile(fileName, content);

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

    /**
     * Handles missing required query parameter — returns 400.
     * Example: GET /read (without ?file=) → "Missing required parameter: file"
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingParam(
            final MissingServletRequestParameterException ex) {
        LOG.warn("Missing parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(String.format("Missing required query parameter: %s%n" +
                        "Usage: GET /read?file=filename.txt%n" +
                        "       POST /write?file=filename.txt%n",
                        ex.getParameterName()));
    }

    /**
     * Handles wrong HTTP method — returns 405.
     * Example: POST /read → "Method not allowed"
     */
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

    /**
     * Catch-all handler — returns 500 without exposing internals.
     * Stack trace is logged server-side only.
     */
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
