package com.app.postcommandservice.post.infrastructure.azure;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.app.postcommandservice.post.application.port.SignedUploadUrl;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
import com.app.postcommandservice.shared.infrastructure.azure.config.AzureStorageProperties;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureMediaUploadUrlSignerTest {

    private static final String BLOB_URL = "https://myaccount.blob.core.windows.net/post-media/11111111-1111-1111-1111-111111111111";

    @Mock
    private AzureBlobSasClient blobSasClient;

    private AzureStorageProperties storageProperties;
    private PostMediaProperties mediaProperties;
    private AzureMediaUploadUrlSigner signer;

    @BeforeEach
    void setUp() {
        storageProperties = new AzureStorageProperties();
        storageProperties.setAccountUrl("https://myaccount.blob.core.windows.net");
        storageProperties.setContainer("post-media");
        storageProperties.setUploadSasTtl(Duration.ofMinutes(15));
        storageProperties.setUseAccountKey(true);

        mediaProperties = new PostMediaProperties();
        mediaProperties.setUploadWindow(Duration.ofHours(48));

        signer = new AzureMediaUploadUrlSigner(blobSasClient, storageProperties, mediaProperties);
    }

    @Test
    void shouldSignWithWriteOnlyPermissionAndExpiryEqualToTtlWhenTtlIsTheBindingConstraint() {
        ArgumentCaptor<BlobServiceSasSignatureValues> captor = ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        when(blobSasClient.generateAccountKeySas(anyString(), captor.capture())).thenReturn("sv=2024-01-01&sp=cw&sig=fake");

        Instant postCreatedAt = Instant.now().minus(Duration.ofHours(1)); // window expiry far in the future
        Instant before = Instant.now();

        SignedUploadUrl result = signer.sign(BLOB_URL, postCreatedAt);

        Instant ttlExpiry = before.plus(Duration.ofMinutes(15));
        assertThat(result.expiresAt()).isBetween(ttlExpiry.minusSeconds(2), ttlExpiry.plusSeconds(2));
        assertThat(result.url()).startsWith(BLOB_URL + "?");
        assertThat(result.url()).contains("sig=fake");

        String permissions = captor.getValue().getPermissions();
        assertThat(permissions).contains("c").contains("w");
    }

    @Test
    void shouldCapExpiryAtPostCreatedAtPlusUploadWindowWhenThatIsEarlierThanTtl() {
        ArgumentCaptor<BlobServiceSasSignatureValues> captor = ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        when(blobSasClient.generateAccountKeySas(anyString(), captor.capture())).thenReturn("sv=2024-01-01&sp=cw&sig=fake");

        // Upload window expires in 5 minutes, well before the 15-minute TTL.
        Instant postCreatedAt = Instant.now().minus(Duration.ofHours(48)).plus(Duration.ofMinutes(5));

        SignedUploadUrl result = signer.sign(BLOB_URL, postCreatedAt);

        Instant expectedExpiry = postCreatedAt.plus(Duration.ofHours(48));
        assertThat(result.expiresAt()).isBetween(expectedExpiry.minusSeconds(2), expectedExpiry.plusSeconds(2));
    }

    @Test
    void shouldNotIncludeReadDeleteOrListPermissionsInTheSas() {
        ArgumentCaptor<BlobServiceSasSignatureValues> captor = ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        when(blobSasClient.generateAccountKeySas(anyString(), captor.capture())).thenReturn("sv=2024-01-01&sp=cw&sig=fake");

        signer.sign(BLOB_URL, Instant.now().minus(Duration.ofHours(1)));

        String permissions = captor.getValue().getPermissions();
        assertThat(permissions).doesNotContain("r");
        assertThat(permissions).doesNotContain("d");
        assertThat(permissions).doesNotContain("l");
    }

    @Test
    void shouldThrowWhenTheUploadWindowHasAlreadyExpired() {
        Instant postCreatedAt = Instant.now().minus(Duration.ofHours(49)); // window (48h) already elapsed

        assertThatThrownBy(() -> signer.sign(BLOB_URL, postCreatedAt))
                .isInstanceOf(MediaUploadWindowExpiredException.class);
    }

    @Test
    void shouldRejectBlankBlobUrl() {
        assertThatThrownBy(() -> signer.sign("  ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullPostCreatedAt() {
        assertThatThrownBy(() -> signer.sign(BLOB_URL, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldUseUserDelegationSasWhenNotUsingAccountKey() {
        storageProperties.setUseAccountKey(false);
        UserDelegationKey delegationKey = new UserDelegationKey().setValue("fake-delegation-key-value");
        when(blobSasClient.fetchUserDelegationKey(any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(delegationKey);
        when(blobSasClient.generateUserDelegationSas(anyString(), any(BlobServiceSasSignatureValues.class), eq(delegationKey)))
                .thenReturn("sv=2024-01-01&sp=cw&sig=delegated");

        SignedUploadUrl result = signer.sign(BLOB_URL, Instant.now().minus(Duration.ofHours(1)));

        assertThat(result.url()).contains("sig=delegated");
        verify(blobSasClient, never()).generateAccountKeySas(anyString(), any());
    }

    @Test
    void shouldCacheTheUserDelegationKeyAcrossMultipleSignatures() {
        storageProperties.setUseAccountKey(false);
        UserDelegationKey delegationKey = new UserDelegationKey().setValue("fake-delegation-key-value");
        when(blobSasClient.fetchUserDelegationKey(any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(delegationKey);
        when(blobSasClient.generateUserDelegationSas(anyString(), any(BlobServiceSasSignatureValues.class), eq(delegationKey)))
                .thenReturn("sv=2024-01-01&sp=cw&sig=delegated");

        signer.sign(BLOB_URL, Instant.now().minus(Duration.ofHours(1)));
        signer.sign(BLOB_URL, Instant.now().minus(Duration.ofHours(1)));
        signer.sign(BLOB_URL, Instant.now().minus(Duration.ofHours(1)));

        verify(blobSasClient, times(1)).fetchUserDelegationKey(any(OffsetDateTime.class), any(OffsetDateTime.class));
    }
}
