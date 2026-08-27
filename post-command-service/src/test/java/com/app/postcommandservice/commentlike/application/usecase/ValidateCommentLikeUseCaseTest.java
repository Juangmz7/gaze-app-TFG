package com.app.postcommandservice.commentlike.application.usecase;

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

import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.repository.CommentLikeValidationRepository;
import com.app.postcommandservice.commentlike.application.repository.PostCommentLikeRepository;
import com.app.postcommandservice.commentlike.domain.events.PostCommentLikeCreatedDomainEvent;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeContext;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.commentlike.domain.model.PostCommentLike;
import com.app.postcommandservice.commentlike.infrastructure.events.PostCommentLikeCreatedEvent;
import com.app.postcommandservice.commentlike.infrastructure.mapper.PostCommentLikeEventMapper;
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
class ValidateCommentLikeUseCaseTest {

    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final CommentLikeSource SOURCE = CommentLikeSource.HOME_FEED;
    private static final int FEED_POSITION = 3;

    @Mock
    private PostCommentLikeRepository postCommentLikeRepository;

    @Mock
    private CommentLikeValidationRepository commentLikeValidationRepository;

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
    private ValidateCommentLikeUseCase validateCommentLikeUseCase;

    @Test
    void shouldPersistCommentLikeAndPublishCreatedEventWhenCommandIsValid() {
        var command = command();
        var savedLike = new PostCommentLike(
                new CommentId(COMMENT_ID),
                new UserId(USER_ID),
                new CommentLikeContext(SOURCE, FEED_POSITION),
                Instant.now()
        );
        var event = PostCommentLikeCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .postId(POST_ID)
                .commentId(COMMENT_ID)
                .userId(USER_ID)
                .source(SOURCE)
                .feedPosition(FEED_POSITION)
                .createdAt(savedLike.getCreatedAt())
                .build();

        when(commentLikeValidationRepository.findActiveComment(POST_ID, COMMENT_ID))
                .thenReturn(Optional.of(new CommentLikeValidationRepository.ActiveComment(POST_ID, COMMENT_ID, OWNER_ID)));
        when(commentLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(false);
        when(postCommentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, USER_ID)).thenReturn(false);
        when(postCommentLikeRepository.save(any(PostCommentLike.class))).thenReturn(savedLike);
        when(postCommentLikeEventMapper.toPostCommentLikeCreatedEvent(any(UUID.class), eq(CORRELATION_ID),
                eq(POST_ID), eq(savedLike), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"payload\":true}");

        validateCommentLikeUseCase.validateAndCreateLike(command);

        verify(postCommentLikeRepository).save(any(PostCommentLike.class));
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxEventCaptor.getValue().getEventType())
                .isEqualTo(PostCommentLikeCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostCommentLikeCreatedDomainEvent.class));
    }

    @Test
    void shouldStopGracefullyWhenCommentLikeAlreadyExists() {
        when(commentLikeValidationRepository.findActiveComment(POST_ID, COMMENT_ID))
                .thenReturn(Optional.of(new CommentLikeValidationRepository.ActiveComment(POST_ID, COMMENT_ID, OWNER_ID)));
        when(commentLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(false);
        when(postCommentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, USER_ID)).thenReturn(true);

        validateCommentLikeUseCase.validateAndCreateLike(command());

        verify(postCommentLikeRepository, never()).save(any(PostCommentLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCommentLikeCreatedDomainEvent.class));
    }

    @Test
    void shouldAbortProcessingWhenCommentDoesNotExistOrIsInactive() {
        when(commentLikeValidationRepository.findActiveComment(POST_ID, COMMENT_ID)).thenReturn(Optional.empty());

        validateCommentLikeUseCase.validateAndCreateLike(command());

        verify(commentLikeValidationRepository, never()).existsBlockRelationship(any(UUID.class), any(UUID.class));
        verify(postCommentLikeRepository, never()).save(any(PostCommentLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCommentLikeCreatedDomainEvent.class));
    }

    @Test
    void shouldAbortProcessingWhenBlockRelationshipExists() {
        when(commentLikeValidationRepository.findActiveComment(POST_ID, COMMENT_ID))
                .thenReturn(Optional.of(new CommentLikeValidationRepository.ActiveComment(POST_ID, COMMENT_ID, OWNER_ID)));
        when(commentLikeValidationRepository.existsBlockRelationship(USER_ID, OWNER_ID)).thenReturn(true);

        validateCommentLikeUseCase.validateAndCreateLike(command());

        verify(postCommentLikeRepository, never()).existsByCommentIdAndUserId(any(UUID.class), any(UUID.class));
        verify(postCommentLikeRepository, never()).save(any(PostCommentLike.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCommentLikeCreatedDomainEvent.class));
    }

    private ValidateCommentLikeCommand command() {
        return new ValidateCommentLikeCommand(
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
