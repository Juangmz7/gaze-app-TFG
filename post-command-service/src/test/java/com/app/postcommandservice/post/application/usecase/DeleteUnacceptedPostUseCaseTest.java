package com.app.postcommandservice.post.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabDeletedDomainEvent;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabDeletedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.repository.UnacceptedPostDeletionRepository;
import com.app.postcommandservice.post.application.repository.UnacceptedPostDeletionRepository.PostDeletionSnapshot;
import com.app.postcommandservice.post.domain.events.PostDeletedDomainEvent;
import com.app.postcommandservice.post.domain.events.PostMediaBlobsReleasedEvent;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteUnacceptedPostUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final List<String> BLOB_URLS = List.of("https://blob/video.mp4", "https://blob/video-thumb.jpg");

    @Mock
    private UnacceptedPostDeletionRepository unacceptedPostDeletionRepository;

    @Mock
    private CollabRepository collabRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<Object> publishedEventCaptor;

    private DeleteUnacceptedPostUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteUnacceptedPostUseCase(
                unacceptedPostDeletionRepository,
                collabRepository,
                outboxEventRepository,
                new CollabEventMapper(),
                new JsonMapper(new tools.jackson.databind.ObjectMapper()),
                applicationEventPublisher);
    }

    @Test
    void shouldDeleteAMediaUploadFailedPostAndItsIdempotencyRowWithoutAPostEvent() {
        givenSnapshot(PostStatus.MEDIA_UPLOAD_FAILED, null, BLOB_URLS);
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome).isEqualTo(new DeleteUnacceptedPostUseCase.Outcome(true, false));
        verify(unacceptedPostDeletionRepository).deleteRequestIdempotency(POST_ID);
        verifyNoInteractions(outboxEventRepository, collabRepository);
        verify(applicationEventPublisher).publishEvent(publishedEventCaptor.capture());
        assertThat(publishedEventCaptor.getAllValues())
                .noneMatch(PostDeletedDomainEvent.class::isInstance)
                .singleElement()
                .isEqualTo(new PostMediaBlobsReleasedEvent(POST_ID, BLOB_URLS));
    }

    @Test
    void shouldDeleteAPendingPostAndNotReleaseBlobsWhenTheyWereAlreadyPurged() {
        givenSnapshot(PostStatus.PENDING, null, List.of());
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome.postDeleted()).isTrue();
        verify(unacceptedPostDeletionRepository).deleteRequestIdempotency(POST_ID);
        verifyNoInteractions(applicationEventPublisher, outboxEventRepository);
    }

    @Test
    void shouldDeleteTheCollabAndPublishCollabDeletedEventWhenItHasNoOtherPost() {
        givenSnapshot(PostStatus.PENDING, COLLAB_ID, BLOB_URLS);
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);
        when(unacceptedPostDeletionRepository.collabHasPosts(COLLAB_ID)).thenReturn(false);
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab()));

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome).isEqualTo(new DeleteUnacceptedPostUseCase.Outcome(true, true));
        verify(unacceptedPostDeletionRepository).deleteCollab(COLLAB_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        var outboxEvent = outboxEventCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo(CollabDeletedEvent.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEvent.getPayload())
                .contains("\"collabId\":\"" + COLLAB_ID + "\"")
                .contains("\"actionedBy\":\"" + OWNER_ID + "\"");
        verify(applicationEventPublisher, times(2)).publishEvent(publishedEventCaptor.capture());
        assertThat(publishedEventCaptor.getAllValues())
                .containsExactlyInAnyOrder(
                        new CollabDeletedDomainEvent(outboxEvent.getId()),
                        new PostMediaBlobsReleasedEvent(POST_ID, BLOB_URLS));
    }

    @Test
    void shouldKeepTheCollabWhenAnotherPostStillReferencesIt() {
        givenSnapshot(PostStatus.PENDING, COLLAB_ID, List.of());
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);
        when(unacceptedPostDeletionRepository.collabHasPosts(COLLAB_ID)).thenReturn(true);

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome).isEqualTo(new DeleteUnacceptedPostUseCase.Outcome(true, false));
        verify(unacceptedPostDeletionRepository, never()).deleteCollab(any());
        verifyNoInteractions(outboxEventRepository, collabRepository);
    }

    @Test
    void shouldHardDeleteAnAlreadyDeletedCollabWithoutPublishingCollabDeletedAgain() {
        givenSnapshot(PostStatus.MEDIA_UPLOAD_FAILED, COLLAB_ID, List.of());
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);
        when(unacceptedPostDeletionRepository.collabHasPosts(COLLAB_ID)).thenReturn(false);
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(openCollab().delete()));

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome.collabDeleted()).isTrue();
        verify(unacceptedPostDeletionRepository).deleteCollab(COLLAB_ID);
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher);
    }

    @Test
    void shouldWriteNothingWhenTheConditionalDeleteAffectsNoRow() {
        givenSnapshot(PostStatus.PENDING, COLLAB_ID, BLOB_URLS);
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(false);

        var outcome = useCase.delete(POST_ID);

        assertThat(outcome).isEqualTo(DeleteUnacceptedPostUseCase.Outcome.NOT_DELETED);
        verify(unacceptedPostDeletionRepository, never()).deleteRequestIdempotency(any());
        verify(unacceptedPostDeletionRepository, never()).deleteCollab(any());
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher, collabRepository);
    }

    @Test
    void shouldStillLetTheConditionalDeleteDecideWhenTheSnapshotIsAlreadyAccepted() {
        givenSnapshot(PostStatus.ACCEPTED, COLLAB_ID, BLOB_URLS);
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(false);

        assertThat(useCase.delete(POST_ID)).isEqualTo(DeleteUnacceptedPostUseCase.Outcome.NOT_DELETED);

        // The conditional delete is always issued: it also clears the caller's persistence context
        verify(unacceptedPostDeletionRepository).deleteIfUnaccepted(POST_ID);
        verify(unacceptedPostDeletionRepository, never()).deleteRequestIdempotency(any());
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher, collabRepository);
    }

    @Test
    void shouldIssueTheConditionalDeleteAndReturnNotDeletedForAMissingPost() {
        when(unacceptedPostDeletionRepository.findSnapshot(POST_ID)).thenReturn(Optional.empty());
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(false);

        assertThat(useCase.delete(POST_ID)).isEqualTo(DeleteUnacceptedPostUseCase.Outcome.NOT_DELETED);

        verify(unacceptedPostDeletionRepository).deleteIfUnaccepted(POST_ID);
        verify(unacceptedPostDeletionRepository, never()).deleteRequestIdempotency(any());
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher, collabRepository);
    }

    @Test
    void shouldDeleteTheIdempotencyRowsButNoCollabOrBlobsWhenThereWasNoSnapshot() {
        when(unacceptedPostDeletionRepository.findSnapshot(POST_ID)).thenReturn(Optional.empty());
        when(unacceptedPostDeletionRepository.deleteIfUnaccepted(POST_ID)).thenReturn(true);

        assertThat(useCase.delete(POST_ID)).isEqualTo(new DeleteUnacceptedPostUseCase.Outcome(true, false));

        verify(unacceptedPostDeletionRepository).deleteRequestIdempotency(POST_ID);
        verify(unacceptedPostDeletionRepository, never()).deleteCollab(any());
        verifyNoInteractions(outboxEventRepository, applicationEventPublisher, collabRepository);
    }

    @Test
    void shouldRejectANullPostId() {
        assertThatThrownBy(() -> useCase.delete(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("postId");
    }

    private void givenSnapshot(PostStatus status, UUID collabId, List<String> blobUrls) {
        when(unacceptedPostDeletionRepository.findSnapshot(POST_ID))
                .thenReturn(Optional.of(new PostDeletionSnapshot(POST_ID, OWNER_ID, collabId, status, blobUrls)));
    }

    private static Collab openCollab() {
        return Collab.open(COLLAB_ID, new CollabTitle("Summer collab"), new UserId(OWNER_ID));
    }
}
