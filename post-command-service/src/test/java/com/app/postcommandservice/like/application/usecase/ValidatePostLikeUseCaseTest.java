package com.app.postcommandservice.like.application.usecase;

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

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeRepository;
import com.app.postcommandservice.like.application.repository.PostLikeValidationRepository;
import com.app.postcommandservice.like.domain.events.PostLikeCreatedDomainEvent;
import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.infrastructure.events.PostLikeCreatedEvent;
import com.app.postcommandservice.like.infrastructure.mapper.PostLikeEventMapper;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
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
class ValidatePostLikeUseCaseTest {

    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostLikeRepository postLikeRepository;

    @Mock
    private PostLikeValidationRepository postLikeValidationRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostLikeEventMapper postLikeEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private ValidatePostLikeUseCase validatePostLikeUseCase;

    @Test
    void shouldPersistLikeAndPublishPostLikeCreatedEventWhenCommandIsValid() {
        var command = command();
        var savedLike = new PostLike(new PostId(POST_ID), new UserId(USER_ID), Instant.now());
        var event = PostLikeCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .userId(USER_ID)
                .createdAt(savedLike.getCreatedAt())
                .build();

        when(postLikeValidationRepository.findActivePost(POST_ID))
                .thenReturn(Optional.of(new PostLikeValidationRepository.ActivePost(POST_ID, OWNER_ID)));
        when(postLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(false);
        when(postLikeRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);
        when(postLikeRepository.save(any(PostLike.class))).thenReturn(savedLike);
        when(postLikeEventMapper.toPostLikeCreatedEvent(any(UUID.class), eq(CORRELATION_ID), eq(savedLike), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        validatePostLikeUseCase.validateAndCreateLike(command);

        verify(postLikeRepository).save(any(PostLike.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostLikeCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostLikeCreatedDomainEvent.class));
    }

    @Test
    void shouldStopGracefullyWhenLikeAlreadyExists() {
        when(postLikeValidationRepository.findActivePost(POST_ID))
                .thenReturn(Optional.of(new PostLikeValidationRepository.ActivePost(POST_ID, OWNER_ID)));
        when(postLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(false);
        when(postLikeRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(true);

        validatePostLikeUseCase.validateAndCreateLike(command());

        verify(postLikeRepository, never()).save(any(PostLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostLikeCreatedDomainEvent.class));
    }

    @Test
    void shouldAbortProcessingWhenPostDoesNotExistOrIsInactive() {
        when(postLikeValidationRepository.findActivePost(POST_ID)).thenReturn(Optional.empty());

        validatePostLikeUseCase.validateAndCreateLike(command());

        verify(postLikeValidationRepository, never()).existsBlockRelationship(any(UUID.class), any(UUID.class));
        verify(postLikeRepository, never()).save(any(PostLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldAbortProcessingWhenBlockRelationshipExists() {
        when(postLikeValidationRepository.findActivePost(POST_ID))
                .thenReturn(Optional.of(new PostLikeValidationRepository.ActivePost(POST_ID, OWNER_ID)));
        when(postLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(true);

        validatePostLikeUseCase.validateAndCreateLike(command());

        verify(postLikeRepository, never()).existsByPostIdAndUserId(any(UUID.class), any(UUID.class));
        verify(postLikeRepository, never()).save(any(PostLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private ValidatePostLikeCommand command() {
        return new ValidatePostLikeCommand(COMMAND_ID, CORRELATION_ID, Instant.now(), POST_ID, USER_ID);
    }
}
