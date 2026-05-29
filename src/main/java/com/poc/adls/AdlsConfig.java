package com.poc.adls;

import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Azure infrastructure configuration.
 *
 * Separates infrastructure wiring from business logic.
 * DataLakeFileSystemClient is a Spring-managed singleton —
 * eliminates AtomicReference race condition from Phase 1.
 *
 * Security:
 *   - final class — not subclassable, prevents override attacks (F6)
 *   - ManagedIdentityCredential — direct MSI, skips 6-provider chain
 *   - HTTPS enforced by dfs.core.windows.net endpoint
 *   - Config values injected from Azure App Settings via @Value
 *   - Fail-fast: Spring context fails to start if config is missing
 */
@Configuration
public final class AdlsConfig {  // F6 FIX: added final — prevents subclassing

    private static final Logger LOG = LoggerFactory.getLogger(AdlsConfig.class);

    /**
     * Creates MSI-authenticated ADLS Gen2 filesystem client.
     * Spring manages lifecycle — singleton, thread-safe, no manual locking.
     *
     * @param accountName   Azure Storage account name (from AZURE_STORAGE_ACCOUNT_NAME)
     * @param containerName ADLS container name (from AZURE_STORAGE_CONTAINER_NAME)
     * @return authenticated DataLakeFileSystemClient scoped to the container
     */
    @Bean
    public DataLakeFileSystemClient dataLakeFileSystemClient(
            @Value("${AZURE_STORAGE_ACCOUNT_NAME}") final String accountName,
            @Value("${AZURE_STORAGE_CONTAINER_NAME}") final String containerName) {

        validateConfig(accountName,   "AZURE_STORAGE_ACCOUNT_NAME");
        validateConfig(containerName, "AZURE_STORAGE_CONTAINER_NAME");

        // Log config category only — not specific values (internal infra detail)
        LOG.info("Initializing ADLS client with Managed Identity...");

        // ManagedIdentityCredential — direct MSI token acquisition
        // Significantly faster than DefaultAzureCredential (skips 6-provider chain)
        // Azure manages token lifecycle and rotation automatically
        final ManagedIdentityCredential credential =
                new ManagedIdentityCredentialBuilder().build();

        // HTTPS enforced by endpoint — dfs.core.windows.net is TLS-only
        final String endpoint =
                String.format("https://%s.dfs.core.windows.net", accountName);

        final DataLakeServiceClient serviceClient =
                new DataLakeServiceClientBuilder()
                        .endpoint(endpoint)
                        .credential(credential)
                        .buildClient();

        LOG.info("ADLS client initialized successfully");
        return serviceClient.getFileSystemClient(containerName);
    }

    /**
     * Validates required config value — fails fast at startup.
     * Prevents silent misconfiguration in any environment.
     *
     * @param value config value to validate
     * @param key   config key name — used in error message only
     * @throws IllegalStateException if value is null or blank
     */
    private static void validateConfig(final String value, final String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "FATAL: Required App Setting not configured: " + key);
        }
    }
}
