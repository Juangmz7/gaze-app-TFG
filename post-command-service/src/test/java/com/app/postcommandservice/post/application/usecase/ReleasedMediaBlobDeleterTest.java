package com.app.postcommandservice.post.application.usecase;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.application.port.MediaBlobDeleter;
import com.app.postcommandservice.post.domain.events.PostMediaBlobsReleasedEvent;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleasedMediaBlobDeleterTest {

    @Mock
    private MediaBlobDeleter mediaBlobDeleter;

    @InjectMocks
    private ReleasedMediaBlobDeleter releasedMediaBlobDeleter;

    @Test
    void shouldDeleteEveryReleasedBlob() {
        when(mediaBlobDeleter.deleteIfExists("https://blob/a")).thenReturn(true);
        when(mediaBlobDeleter.deleteIfExists("https://blob/b")).thenReturn(false);

        releasedMediaBlobDeleter.onPostMediaBlobsReleased(
                new PostMediaBlobsReleasedEvent(UUID.randomUUID(), List.of("https://blob/a", "https://blob/b")));

        verify(mediaBlobDeleter).deleteIfExists("https://blob/a");
        verify(mediaBlobDeleter).deleteIfExists("https://blob/b");
    }

    @Test
    void shouldLogAndContinueWhenABlobDeletionFails() {
        when(mediaBlobDeleter.deleteIfExists("https://blob/a")).thenThrow(new IllegalStateException("storage down"));
        when(mediaBlobDeleter.deleteIfExists("https://blob/b")).thenReturn(true);

        assertThatNoException().isThrownBy(() -> releasedMediaBlobDeleter.onPostMediaBlobsReleased(
                new PostMediaBlobsReleasedEvent(UUID.randomUUID(), List.of("https://blob/a", "https://blob/b"))));

        verify(mediaBlobDeleter).deleteIfExists("https://blob/b");
    }
}
