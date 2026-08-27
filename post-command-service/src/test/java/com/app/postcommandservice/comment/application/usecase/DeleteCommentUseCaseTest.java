package com.app.postcommandservice.comment.application.usecase;

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

import com.app.postcommandservice.comment.application.commands.DeleteCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.events.CommentDeletedDomainEvent;
import com.app.postcommandservice.comment.domain.exception.CommentNotActiveException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.events.CommentDeletedEvent;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentEventMapper;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteCommentUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private CommentEventMapper commentEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<Comment> commentCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<CommentDeletedDomainEvent> domainEventCaptor;

    @InjectMocks
    private DeleteCommentUseCase deleteCommentUseCase;

    @Test
    void shouldSuccessfullyChangeStatusToDeletedSetDeletedAtAndPublishCommentDeletedEventWhenOwnerDeletesAnActiveComment() {
        var existingComment = persistedComment(OWNER_ID, CommentStatus.ACTIVE, null);
        var deletedAt = Instant.now();
        var deletedComment = persistedComment(OWNER_ID, CommentStatus.DELETED, deletedAt);
        var event = CommentDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .commentId(COMMENT_ID)
                .postId(POST_ID)
                .userId(OWNER_ID)
                .occurredAt(deletedAt)
                .build();

        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID)).thenReturn(Optional.of(existingComment));
        when(commentRepository.saveAndFlush(any(Comment.class))).thenReturn(deletedComment);
        when(commentEventMapper.toCommentDeletedEvent(any(UUID.class), any(UUID.class), eq(COMMENT_ID), eq(POST_ID),
                eq(OWNER_ID), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{json}");

        deleteCommentUseCase.deleteComment(new DeleteCommentCommand(POST_ID, COMMENT_ID, OWNER_ID));

        verify(commentRepository).saveAndFlush(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(commentCaptor.getValue().getDeletedAt()).isNotNull();
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CommentDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{json}");
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isNotNull();
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxEventCaptor.getValue().getId());
    }

    @Test
    void shouldThrowCommentOwnershipExceptionWhenTheUserIsNotTheCommentOwner() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(persistedComment(UUID.randomUUID(), CommentStatus.ACTIVE, null)));

        assertThatThrownBy(() -> deleteCommentUseCase.deleteComment(new DeleteCommentCommand(POST_ID, COMMENT_ID, OWNER_ID)))
                .isInstanceOf(CommentOwnershipException.class)
                .hasMessageContaining(COMMENT_ID.toString());

        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentDeletedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentNotFoundExceptionWhenTheCommentDoesNotExist() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deleteCommentUseCase.deleteComment(new DeleteCommentCommand(POST_ID, COMMENT_ID, OWNER_ID)))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(COMMENT_ID.toString());

        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentDeletedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentNotActiveExceptionWhenTryingToDeleteAnAlreadyDeletedComment() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(persistedComment(OWNER_ID, CommentStatus.DELETED, Instant.now())));

        assertThatThrownBy(() -> deleteCommentUseCase.deleteComment(new DeleteCommentCommand(POST_ID, COMMENT_ID, OWNER_ID)))
                .isInstanceOf(CommentNotActiveException.class)
                .hasMessageContaining("ACTIVE");

        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentDeletedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentNotActiveExceptionWhenTryingToDeleteABannedComment() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(persistedComment(OWNER_ID, CommentStatus.BANNED, Instant.now())));

        assertThatThrownBy(() -> deleteCommentUseCase.deleteComment(new DeleteCommentCommand(POST_ID, COMMENT_ID, OWNER_ID)))
                .isInstanceOf(CommentNotActiveException.class)
                .hasMessageContaining("ACTIVE");

        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentDeletedDomainEvent.class));
    }

    private Comment persistedComment(UUID authorId, CommentStatus status, Instant deletedAt) {
        var now = Instant.now();
        return new Comment(
                new CommentId(COMMENT_ID),
                new PostId(POST_ID),
                new UserId(authorId),
                new CommentContent("comment"),
                null,
                status,
                now,
                now,
                deletedAt
        );
    }
}
