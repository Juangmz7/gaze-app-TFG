package com.app.socialservice.shared.infrastructure.azure.sas;

import java.util.Objects;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

/**
 * Service SAS signed with the account key of the container client (Azurite only).
 */
public class AccountKeyBlobSasSigner implements BlobSasSigner {

    private final BlobContainerClient blobContainerClient;

    public AccountKeyBlobSasSigner(BlobContainerClient blobContainerClient) {
        this.blobContainerClient = Objects.requireNonNull(blobContainerClient, "blobContainerClient must not be null");
    }

    @Override
    public String sign(String blobName, BlobServiceSasSignatureValues values, SasValidity keyValidity) {
        requireBlobName(blobName);
        Objects.requireNonNull(values, "values must not be null");
        return blobContainerClient.getBlobClient(blobName).generateSas(values);
    }

    static void requireBlobName(String blobName) {
        if (blobName == null || blobName.isBlank()) {
            throw new IllegalArgumentException("blobName must not be blank");
        }
    }
}
