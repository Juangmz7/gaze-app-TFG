package com.app.postcommandservice.post.infrastructure.azure;

import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AzureBlobSasClientImpl implements AzureBlobSasClient {

    private final BlobServiceClient blobServiceClient;
    private final BlobContainerClient blobContainerClient;

    @Override
    public String generateAccountKeySas(String blobName, BlobServiceSasSignatureValues sasValues) {
        return blobContainerClient.getBlobClient(blobName).generateSas(sasValues);
    }

    @Override
    public UserDelegationKey fetchUserDelegationKey(OffsetDateTime start, OffsetDateTime expiry) {
        return blobServiceClient.getUserDelegationKey(start, expiry);
    }

    @Override
    public String generateUserDelegationSas(String blobName, BlobServiceSasSignatureValues sasValues, UserDelegationKey key) {
        return blobContainerClient.getBlobClient(blobName).generateUserDelegationSas(sasValues, key);
    }
}
