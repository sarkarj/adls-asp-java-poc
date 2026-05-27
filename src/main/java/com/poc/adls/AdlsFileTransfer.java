package com.poc.adls;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ADLS Gen2 → App Service file transfer PoC.
 * Exposes minimal HTTP endpoints — App Service requires a running web server.
 * Uses Java SE built-in HttpServer — zero additional dependencies.
 *
 * Endpoints:
 *   GET /         → triggers file transfer, returns result
 *   GET /health   → liveness probe, returns 200 OK
 */
public final class AdlsFileTransfer {

    private static final Logger LOG =
            Logger.getLogger(AdlsFileTransfer.class.getName());

    // App Service routes traffic to port 8080 by default for Java
    private static final int PORT = 8080;

    private AdlsFileTransfer() {}

    public static void main(final String[] args) throws IOException {

        LOG.info("╔══════════════════════════════════════════╗");
        LOG.info("║   ADLS → ASP File Transfer PoC — START  ║");
        LOG.info("╚══════════════════════════════════════════╝");

        // --- 1. Load + validate config (fail-fast) ---
        final String accountName   = requireEnv("AZURE_STORAGE_ACCOUNT_NAME");
        final String containerName = requireEnv("AZURE_STORAGE_CONTAINER_NAME");
        final String fileName      = requireEnv("AZURE_STORAGE_FILE_NAME");

        LOG.info(String.format("Config loaded — account: %s | container: %s | file: %s",
            accountName, containerName, fileName));

        // --- 2. Build MSI-authenticated ADLS service once — reused per request ---
        final AdlsClientFactory factory = new AdlsClientFactory(accountName);
        final AdlsFileService service   =
                new AdlsFileService(factory.getFileSystemClient(containerName));

        // --- 3. Start HTTP server ---
        final HttpServer server =
                HttpServer.create(new InetSocketAddress(PORT), 0);

        // Health probe — App Service + load balancer liveness check
        server.createContext("/health", exchange -> {
            sendResponse(exchange, 200, "OK");
        });

        // Transfer endpoint — triggers parameterized ADLS file read
        server.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            LOG.info("Transfer request received — executing...");
            final TransferResult result = service.readFile(fileName);

            if (result.success()) {
                final String body = String.format(
                    "TRANSFER SUCCESS%n" +
                    "File      : %s%n" +
                    "Bytes     : %d%n" +
                    "Timestamp : %s%n" +
                    "Preview   : %s%n",
                    result.fileName(),
                    result.bytesTransferred(),
                    result.timestamp(),
                    result.contentPreview()
                );
                LOG.info("✅ Transfer successful — " + result.bytesTransferred() + " bytes");
                sendResponse(exchange, 200, body);
            } else {
                final String body = String.format(
                    "TRANSFER FAILED%n" +
                    "File  : %s%n" +
                    "Error : %s%n",
                    result.fileName(),
                    result.errorMessage()
                );
                LOG.severe("❌ Transfer failed — " + result.errorMessage());
                sendResponse(exchange, 500, body);
            }
        });

        // Virtual thread executor — Java 21, lightweight per-request threads
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        LOG.info(String.format("✅ Server started — listening on port %d", PORT));
        LOG.info("   GET /        → trigger file transfer");
        LOG.info("   GET /health  → liveness probe");
    }

    /**
     * Sends HTTP response with given status code and plain text body.
     */
    private static void sendResponse(
            final HttpExchange exchange,
            final int statusCode,
            final String body) throws IOException {

        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Reads required env var — fail-fast on missing or blank.
     */
    private static String requireEnv(final String key) {
        final String value = System.getenv(key);
        Objects.requireNonNull(value,
            "FATAL: Required environment variable not set: " + key);
        if (value.isBlank()) {
            throw new IllegalStateException(
                "FATAL: Environment variable is blank: " + key);
        }
        return value;
    }
}
