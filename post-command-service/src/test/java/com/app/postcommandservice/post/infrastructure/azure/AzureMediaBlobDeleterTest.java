package com.app.postcommandservice.post.infrastructure.azure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureMediaBlobDeleterTest {

    private static final String BLOB_URL = "http://127.0.0.1:10000/devstoreaccount1/post-media/abc";

    @Mock
    private AzureBlobContentClient azureBlobContentClient;

    @InjectMocks
    private AzureMediaBlobDeleter azureMediaBlobDeleter;

    @Test
    void shouldReturnTrueWhenTheBlobExistedAndWasDeleted() {
        when(azureBlobContentClient.deleteIfExists(BLOB_URL)).thenReturn(true);

        assertThat(azureMediaBlobDeleter.deleteIfExists(BLOB_URL)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenTheBlobDoesNotExist() {
        when(azureBlobContentClient.deleteIfExists(BLOB_URL)).thenReturn(false);

        assertThat(azureMediaBlobDeleter.deleteIfExists(BLOB_URL)).isFalse();
    }

    @Test
    void shouldRejectABlankBlobUrl() {
        assertThatThrownBy(() -> azureMediaBlobDeleter.deleteIfExists(" "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(azureBlobContentClient);
    }
}
