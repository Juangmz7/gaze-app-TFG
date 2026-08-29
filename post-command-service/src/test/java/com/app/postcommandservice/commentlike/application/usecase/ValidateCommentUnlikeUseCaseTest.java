package com.app.postcommandservice.commentlike.application.usecase;

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

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeRepository;
import com.app.postcommandservice.commentlike.domain.events.PostCommentLikeDeletedDomainEvent;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeDeletedEvent;
import com.app.postcommandservice.commentlike.infrastructure.mapper.PostCommentLikeEventMapper;
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
class ValidateCommentUnlikeUseCaseTest {

    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final CommentLikeSource SOURCE = CommentLikeSource.SEARCH;
    private static final int FEED_POSITION = 8;

    @Mock
    private PostCommentLikeRepository postCommentLikeRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostCommentLikeEventMapper postCommentLikeEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private ValidateCommentUnlikeUseCase validateCommentUnlikeUseCase;

    @Test
    void shouldDeleteLikeAndPublishCommentLikeDeletedEventWhenCommandIsValid() {
        var command = command();
        var event = PostCommentLikeDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .commentId(COMMENT_ID)
                .userId(USER_ID)
                .source(SOURCE)
                .feedPosition(FEED_POSITION)
                .build();

        when(postCommentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, USER_ID)).thenReturn(true);
        when(postCommentLikeEventMapper.toPostCommentLikeDeletedEvent(
                any(UUID.class),
                eq(CORRELATION_ID),
                eq(COMMENT_ID),
                eq(USER_ID),
                eq(SOURCE),
                eq(FEED_POSITION),
                any(Instant.class)
        )).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        validateCommentUnlikeUseCase.validateAndDeleteLike(command);

        verify(postCommentLikeRepository).deleteByCommentIdAndUserId(COMMENT_ID, USER_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxEventCaptor.getValue().getEventType())
                .isEqualTo(PostCommentLikeDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostCommentLikeDeletedDomainEvent.class));
    }

    @Test
    void shouldStopGracefullyWhenLikeDoesNotExist() {
        when(postCommentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, USER_ID)).thenReturn(false);

        validateCommentUnlikeUseCase.validateAndDeleteLike(command());

        verify(postCommentLikeRepository, never()).deleteByCommentIdAndUserId(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCommentLikeDeletedDomainEvent.class));
    }

    @Test
    void shouldOnlyValidateByCompositeKeyDuringUnlikeProcessing() {
        when(postCommentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, USER_ID)).thenReturn(false);

        validateCommentUnlikeUseCase.validateAndDeleteLike(command());

        verify(postCommentLikeRepository).existsByCommentIdAndUserId(COMMENT_ID, USER_ID);
        verify(postCommentLikeRepository, never()).deleteByCommentIdAndUserId(any(UUID.class), any(UUID.class));
    }

    private ValidateCommentUnlikeCommand command() {
        return new ValidateCommentUnlikeCommand(
                COMMAND_ID,
                CORRELATION_ID,
                Instant.now(),
                POST_ID,
                COMMENT_ID,
                USER_ID,
                SOURCE,
                FEED_POSITION
        );
    }
}
