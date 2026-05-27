package com.poc.adls;

import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;

import java.util.Objects;

/**
 * Factory for ADLS Gen2 clients authenticated via Managed Identity.
 * Uses ManagedIdentityCredential directly — skips DefaultAzureCredential
 * chain of 6+ providers, significantly faster cold start on App Service.
 */
public final class AdlsClientFactory {

    private final DataLakeServiceClient serviceClient;

    public AdlsClientFactory(final String accountName) {
        Objects.requireNonNull(accountName, "accountName must not be null");
        if (accountName.isBlank()) {
            throw new IllegalArgumentException("accountName must not be blank");
        }

        // Direct MSI — no credential chain, faster than DefaultAzureCredential
        final ManagedIdentityCredential credential =
                new ManagedIdentityCredentialBuilder().build();

        final String endpoint =
                String.format("https://%s.dfs.core.windows.net", accountName);

        this.serviceClient = new DataLakeServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
    }

    public DataLakeFileSystemClient getFileSystemClient(final String containerName) {
        Objects.requireNonNull(containerName, "containerName must not be null");
        if (containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        return serviceClient.getFileSystemClient(containerName);
    }
}
