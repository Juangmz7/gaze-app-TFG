package com.app.postcommandservice.share.application.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.share.application.repository.PostShareRepository;
import com.app.postcommandservice.share.application.repository.PostShareValidationRepository;
import com.app.postcommandservice.share.domain.events.PostSharedDomainEvent;
import com.app.postcommandservice.share.domain.model.PostShare;
import com.app.postcommandservice.share.infrastructure.events.PostSharedEvent;
import com.app.postcommandservice.share.infrastructure.mapper.PostShareEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePostShareUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID SHARER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostShareRepository postShareRepository;

    @Mock
    private PostShareValidationRepository postShareValidationRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostShareEventMapper postShareEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private CreatePostShareUseCase createPostShareUseCase;

    @Test
    void shouldPersistPostShareAndPublishEventWhenPostIsValidUsersDifferAndNoBlocksExist() {
        var command = command(POST_ID, SHARER_ID);
        var savedShare = new PostShare(new PostId(POST_ID), new UserId(SHARER_ID), Instant.now());
        var event = PostSharedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .userId(SHARER_ID)
                .createdAt(savedShare.getCreatedAt())
                .build();

        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, OWNER_ID, PostStatus.ACTIVE)));
        when(postShareValidationRepository.existsBlockRelationship(SHARER_ID, OWNER_ID)).thenReturn(false);
        when(postShareRepository.existsByPostIdAndUserId(POST_ID, SHARER_ID)).thenReturn(false);
        when(postShareRepository.save(any(PostShare.class))).thenReturn(savedShare);
        when(postShareEventMapper.toPostSharedEvent(any(UUID.class), any(UUID.class), eq(savedShare), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        createPostShareUseCase.share(command);

        verify(postShareRepository).save(any(PostShare.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostSharedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        verify(applicationEventPublisher).publishEvent(any(PostSharedDomainEvent.class));
    }

    @Test
    void shouldDiscardSelfShareGracefully() {
        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, SHARER_ID, PostStatus.ACTIVE)));

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(postShareValidationRepository, never()).existsBlockRelationship(any(UUID.class), any(UUID.class));
        verify(postShareRepository, never()).save(any(PostShare.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldDiscardShareWhenBlockRelationExistsInEitherDirection() {
        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, OWNER_ID, PostStatus.ACTIVE)));
        when(postShareValidationRepository.existsBlockRelationship(SHARER_ID, OWNER_ID)).thenReturn(true);

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(postShareRepository, never()).existsByPostIdAndUserId(any(UUID.class), any(UUID.class));
        verify(postShareRepository, never()).save(any(PostShare.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldReturnGracefullyWhenShareAlreadyExistsWithoutCreatingDuplicateEvent() {
        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, OWNER_ID, PostStatus.ACTIVE)));
        when(postShareValidationRepository.existsBlockRelationship(SHARER_ID, OWNER_ID)).thenReturn(false);
        when(postShareRepository.existsByPostIdAndUserId(POST_ID, SHARER_ID)).thenReturn(true);

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(postShareRepository, never()).save(any(PostShare.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostSharedDomainEvent.class));
    }

    @Test
    void shouldDiscardMissingOrInactivePostGracefully() {
        when(postShareValidationRepository.findPost(POST_ID)).thenReturn(Optional.empty());

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(postShareRepository, never()).save(any(PostShare.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));

        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, OWNER_ID, PostStatus.DELETED)));

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(postShareValidationRepository, never()).existsBlockRelationship(any(UUID.class), any(UUID.class));
    }

    @Test
    void shouldReturnGracefullyWhenConcurrentDuplicateInsertTriggersConstraintViolation() {
        when(postShareValidationRepository.findPost(POST_ID))
                .thenReturn(Optional.of(new PostShareValidationRepository.ShareablePost(POST_ID, OWNER_ID, PostStatus.ACTIVE)));
        when(postShareValidationRepository.existsBlockRelationship(SHARER_ID, OWNER_ID)).thenReturn(false);
        when(postShareRepository.existsByPostIdAndUserId(POST_ID, SHARER_ID)).thenReturn(false);
        when(postShareRepository.save(any(PostShare.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        createPostShareUseCase.share(command(POST_ID, SHARER_ID));

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostSharedDomainEvent.class));
    }

    private CreatePostShareCommand command(UUID postId, UUID userId) {
        return new CreatePostShareCommand(
                COMMAND_ID,
                CORRELATION_ID,
                Instant.now(),
                postId,
                userId
        );
    }
}
