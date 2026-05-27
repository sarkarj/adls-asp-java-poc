package com.poc.adls;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for ADLS Gen2 → App Service file transfer PoC.
 *
 * Design principles:
 *   - Zero secrets in code — all config via environment variables
 *   - Fail-fast on missing config — no silent defaults
 *   - MSI authentication — no credentials stored anywhere
 *   - Parameterized — fileName, container, account all injectable
 *   - Single responsibility — orchestration only, delegates to service layer
 */
public final class AdlsFileTransfer {

    private static final Logger LOG =
            Logger.getLogger(AdlsFileTransfer.class.getName());

    // Private constructor — static entry point only, not instantiable
    private AdlsFileTransfer() {}

    public static void main(final String[] args) {

        LOG.info("╔══════════════════════════════════════════╗");
        LOG.info("║   ADLS → ASP File Transfer PoC — START  ║");
        LOG.info("╚══════════════════════════════════════════╝");

        try {
            // --- 1. Load + validate all config (fail-fast, no null defaults) ---
            final String accountName   = requireEnv("AZURE_STORAGE_ACCOUNT_NAME");
            final String containerName = requireEnv("AZURE_STORAGE_CONTAINER_NAME");
            final String fileName      = requireEnv("AZURE_STORAGE_FILE_NAME");

            LOG.info(String.format("Config loaded — account: %s | container: %s | file: %s",
                accountName, containerName, fileName));

            // --- 2. Build MSI-authenticated ADLS client ---
            final AdlsClientFactory factory  = new AdlsClientFactory(accountName);
            final AdlsFileService    service  =
                    new AdlsFileService(factory.getFileSystemClient(containerName));

            // --- 3. Execute parameterized file transfer ---
            final TransferResult result = service.readFile(fileName);

            // --- 4. Report outcome ---
            if (result.success()) {
                LOG.info("╔══════════════════════════════════════════╗");
                LOG.info("║           TRANSFER SUCCESSFUL  ✅         ║");
                LOG.info("╚══════════════════════════════════════════╝");
                LOG.info(String.format("  File      : %s", result.fileName()));
                LOG.info(String.format("  Bytes     : %d", result.bytesTransferred()));
                LOG.info(String.format("  Timestamp : %s", result.timestamp()));
                LOG.info(String.format("  Preview   : %s", result.contentPreview()));
            } else {
                LOG.severe("╔══════════════════════════════════════════╗");
                LOG.severe("║           TRANSFER FAILED  ❌             ║");
                LOG.severe("╚══════════════════════════════════════════╝");
                LOG.severe(String.format("  File  : %s", result.fileName()));
                LOG.severe(String.format("  Error : %s", result.errorMessage()));
                System.exit(1); // Non-zero signals failure to CI/CD + App Service
            }

        } catch (Exception ex) {
            LOG.log(Level.SEVERE,
                "Fatal error during transfer — {0}", ex.getMessage());
            System.exit(1);
        }

        LOG.info("═══════════════════════════════════════════");
        LOG.info("  ADLS → ASP File Transfer PoC — END");
        LOG.info("═══════════════════════════════════════════");
    }

    /**
     * Reads a required environment variable.
     * Throws immediately if absent or blank — no silent misconfiguration.
     *
     * @param key environment variable name
     * @return non-blank value
     * @throws NullPointerException     if variable is not set
     * @throws IllegalStateException    if variable is blank
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
