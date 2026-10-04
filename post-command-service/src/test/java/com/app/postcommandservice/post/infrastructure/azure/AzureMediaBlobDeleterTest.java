package com.app.postcommandservice.post.infrastructure.azure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AzureMediaBlobDeleterTest {

    @Mock
    private AzureBlobDeleteClient blobDeleteClient;

    @Test
    void shouldDelegateToTheSeamForAValidBlobUrl() {
        var deleter = new AzureMediaBlobDeleter(blobDeleteClient);

        deleter.deleteIfExists("https://cdn/container/blob.jpg");

        verify(blobDeleteClient).deleteIfExists("https://cdn/container/blob.jpg");
    }

    @Test
    void shouldRejectBlankBlobUrlWithoutCallingTheSeam() {
        var deleter = new AzureMediaBlobDeleter(blobDeleteClient);

        assertThatThrownBy(() -> deleter.deleteIfExists("   "))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(blobDeleteClient);
    }

    @Test
    void shouldRejectNullSeamDependency() {
        assertThatThrownBy(() -> new AzureMediaBlobDeleter(null))
                .isInstanceOf(NullPointerException.class);
    }
}
