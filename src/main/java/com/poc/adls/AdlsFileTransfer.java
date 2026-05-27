package com.poc.adls;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ADLS Gen2 → App Service file transfer PoC.
 *
 * Critical design decisions:
 *   1. HTTP server starts FIRST — App Service health probe must succeed immediately
 *   2. ADLS client initialized LAZILY — MSI token acquired on first request only
 *   3. AtomicReference — thread-safe lazy singleton for service instance
 *   4. PORT env var — App Service sets this dynamically; fallback to 8080
 */
public final class AdlsFileTransfer {

    private static final Logger LOG =
            Logger.getLogger(AdlsFileTransfer.class.getName());

    private AdlsFileTransfer() {}

    public static void main(final String[] args) throws IOException {

        LOG.info("╔══════════════════════════════════════════╗");
        LOG.info("║   ADLS → ASP File Transfer PoC — START  ║");
        LOG.info("╚══════════════════════════════════════════╝");

        // --- 1. Load config (fail-fast — no Azure calls yet) ---
        final String accountName   = requireEnv("AZURE_STORAGE_ACCOUNT_NAME");
        final String containerName = requireEnv("AZURE_STORAGE_CONTAINER_NAME");
        final String fileName      = requireEnv("AZURE_STORAGE_FILE_NAME");

        LOG.info(String.format(
            "Config loaded — account: %s | container: %s | file: %s",
            accountName, containerName, fileName));

        // --- 2. Lazy service reference — initialized on first request ---
        // AtomicReference ensures thread-safe single initialization
        final AtomicReference<AdlsFileService> serviceRef = new AtomicReference<>();

        // --- 3. Resolve port — App Service sets PORT dynamically ---
        final int port = resolvePort();

        // --- 4. Start HTTP server IMMEDIATELY ---
        // Must bind to port before any Azure SDK calls
        // App Service health probe fires within seconds of container start
        final HttpServer server =
                HttpServer.create(new InetSocketAddress(port), 0);

        // Health probe — returns instantly, no Azure calls
        server.createContext("/health", exchange ->
            sendResponse(exchange, 200, "OK — ADLS PoC Running\n"));

        // Transfer endpoint — lazy MSI init on first call
        server.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed\n");
                return;
            }

            // Lazy init — build ADLS client only on first actual request
            serviceRef.compareAndSet(null, buildService(accountName, containerName));
            final AdlsFileService service = serviceRef.get();

            LOG.info("Transfer request received — executing...");
            final TransferResult result = service.readFile(fileName);

            if (result.success()) {
                final String body = String.format(
                    "╔══════════════════════════════════════╗%n" +
                    "║      TRANSFER SUCCESSFUL  ✅          ║%n" +
                    "╚══════════════════════════════════════╝%n" +
                    "File      : %s%n" +
                    "Bytes     : %d%n" +
                    "Timestamp : %s%n" +
                    "Preview   : %s%n",
                    result.fileName(),
                    result.bytesTransferred(),
                    result.timestamp(),
                    result.contentPreview());

                LOG.info("✅ Transfer successful — " + result.bytesTransferred() + " bytes");
                sendResponse(exchange, 200, body);

            } else {
                final String body = String.format(
                    "TRANSFER FAILED ❌%n" +
                    "File  : %s%n" +
                    "Error : %s%n",
                    result.fileName(),
                    result.errorMessage());

                LOG.severe("❌ Transfer failed — " + result.errorMessage());
                sendResponse(exchange, 500, body);
            }
        });

        // Virtual threads — Java 21, lightweight concurrency
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        LOG.info(String.format("✅ Server listening on port %d", port));
        LOG.info("   GET /        → trigger ADLS file transfer");
        LOG.info("   GET /health  → liveness probe");
    }

    /**
     * Builds MSI-authenticated ADLS service.
     * Called lazily — never blocks server startup.
     */
    private static AdlsFileService buildService(
            final String accountName,
            final String containerName) {
        LOG.info("Initializing ADLS client with Managed Identity...");
        final AdlsClientFactory factory = new AdlsClientFactory(accountName);
        return new AdlsFileService(factory.getFileSystemClient(containerName));
    }

    /**
     * Resolves port — App Service injects PORT env var dynamically.
     * Fallback to 8080 for local testing.
     */
    private static int resolvePort() {
        final String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                return Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException ex) {
                LOG.warning("Invalid PORT env var — falling back to 8080");
            }
        }
        return 8080;
    }

    /**
     * Sends plain text HTTP response.
     */
    private static void sendResponse(
            final HttpExchange exchange,
            final int status,
            final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (final OutputStream os = exchange.getResponseBody()) {
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
