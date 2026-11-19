package com.app.socialservice.shared.infrastructure.azure.sas;

import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

/**
 * Signs a SAS for one blob of the media container. Signing is a local HMAC: the only network
 * call an implementation may make is fetching a user delegation key, never one per URL.
 */
public interface BlobSasSigner {

    /**
     * @param blobName    blob of the configured container
     * @param values      SAS values (permissions, start, expiry, protocol)
     * @param keyValidity the validity of the signing key to use; it must cover the SAS
     * @return the SAS query string, without a leading {@code ?}
     * @throws SasSigningKeyUnavailableException when no key covering {@code keyValidity} can be obtained
     */
    String sign(String blobName, BlobServiceSasSignatureValues values, SasValidity keyValidity);
}
