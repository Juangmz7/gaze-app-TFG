package com.app.postcommandservice.like.application.usecase;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.application.repository.PostLikeRepository;
import com.app.postcommandservice.like.domain.events.PostLikeDeletedDomainEvent;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.like.infrastructure.events.PostLikeDeletedEvent;
import com.app.postcommandservice.like.infrastructure.mapper.PostLikeEventMapper;
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
class ValidatePostUnlikeUseCaseTest {

    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final PostLikeSource SOURCE = PostLikeSource.SEARCH;
    private static final int FEED_POSITION = 1;

    @Mock
    private PostLikeRepository postLikeRepository;

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
    private ValidatePostUnlikeUseCase validatePostUnlikeUseCase;

    @Test
    void shouldDeleteLikeAndPublishPostLikeDeletedEventWhenCommandIsValid() {
        var command = command();
        var event = PostLikeDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .userId(USER_ID)
                .source(SOURCE)
                .feedPosition(FEED_POSITION)
                .build();

        when(postLikeRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(true);
        when(postLikeEventMapper.toPostLikeDeletedEvent(
                any(UUID.class),
                eq(CORRELATION_ID),
                eq(POST_ID),
                eq(USER_ID),
                eq(SOURCE),
                eq(FEED_POSITION),
                any(Instant.class)
        )).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        validatePostUnlikeUseCase.validateAndDeleteLike(command);

        verify(postLikeRepository).deleteByPostIdAndUserId(POST_ID, USER_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostLikeDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostLikeDeletedDomainEvent.class));
    }

    @Test
    void shouldStopGracefullyWhenLikeDoesNotExist() {
        when(postLikeRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

        validatePostUnlikeUseCase.validateAndDeleteLike(command());

        verify(postLikeRepository, never()).deleteByPostIdAndUserId(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostLikeDeletedDomainEvent.class));
    }

    @Test
    void shouldNotPerformBlockRelationshipChecksDuringUnlikeProcessing() {
        when(postLikeRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

        validatePostUnlikeUseCase.validateAndDeleteLike(command());

        verify(postLikeRepository).existsByPostIdAndUserId(POST_ID, USER_ID);
        verify(postLikeRepository, never()).deleteByPostIdAndUserId(any(UUID.class), any(UUID.class));
    }

    private ValidatePostUnlikeCommand command() {
        return new ValidatePostUnlikeCommand(
                COMMAND_ID,
                CORRELATION_ID,
                Instant.now(),
                POST_ID,
                USER_ID,
                SOURCE,
                FEED_POSITION
        );
    }
}
