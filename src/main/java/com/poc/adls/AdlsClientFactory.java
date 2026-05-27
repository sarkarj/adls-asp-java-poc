package com.poc.adls;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;

import java.util.Objects;

/**
 * Factory for ADLS Gen2 clients authenticated via Managed Identity (MSI).
 * Encapsulates all credential and client construction logic.
 * Final + package-private constructor — not extensible, not instantiable externally.
 */
public final class AdlsClientFactory {

    private final DataLakeServiceClient serviceClient;

    /**
     * Constructs factory with MSI credential wired to the given storage account.
     * DefaultAzureCredential resolves MSI automatically when running in App Service.
     *
     * @param accountName Azure Storage account name — must not be null or blank
     */
    public AdlsClientFactory(final String accountName) {
        Objects.requireNonNull(accountName, "accountName must not be null");
        if (accountName.isBlank()) {
            throw new IllegalArgumentException("accountName must not be blank");
        }

        // MSI credential — no secrets, Azure manages token lifecycle
        final DefaultAzureCredential credential =
                new DefaultAzureCredentialBuilder().build();

        // HTTPS enforced by dfs.core.windows.net endpoint — TLS only
        final String endpoint =
                String.format("https://%s.dfs.core.windows.net", accountName);

        this.serviceClient = new DataLakeServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
    }

    /**
     * Returns a filesystem (container) client for the given container.
     *
     * @param containerName ADLS container name — must not be null or blank
     * @return scoped DataLakeFileSystemClient
     */
    public DataLakeFileSystemClient getFileSystemClient(final String containerName) {
        Objects.requireNonNull(containerName, "containerName must not be null");
        if (containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        return serviceClient.getFileSystemClient(containerName);
    }
}
