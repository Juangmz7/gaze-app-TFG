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

import com.app.postcommandservice.comment.application.commands.UpdateCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.events.CommentUpdatedDomainEvent;
import com.app.postcommandservice.comment.domain.exception.CommentNotActiveException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.events.CommentUpdatedEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateCommentUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

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
    private ArgumentCaptor<CommentUpdatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private UpdateCommentUseCase updateCommentUseCase;

    @Test
    void shouldUpdateCommentWhenOwnerChangesContent() {
        var command = new UpdateCommentCommand(POST_ID, COMMENT_ID, USER_ID, "updated");
        var existingComment = persistedComment(COMMENT_ID, POST_ID, USER_ID, "before", CommentStatus.ACTIVE);
        var savedComment = persistedComment(
                COMMENT_ID,
                POST_ID,
                USER_ID,
                "updated",
                CommentStatus.ACTIVE,
                existingComment.getCreatedAt(),
                existingComment.getUpdatedAt().plusSeconds(1)
        );

        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID)).thenReturn(Optional.of(existingComment));
        when(commentRepository.saveAndFlush(any(Comment.class))).thenReturn(savedComment);
        stubUpdatedEvent(savedComment);

        var response = updateCommentUseCase.updateComment(command);

        assertThat(response.commentId()).isEqualTo(COMMENT_ID);
        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.content()).isEqualTo("updated");
        assertThat(response.updatedAt()).isEqualTo(savedComment.getUpdatedAt());

        verify(commentRepository).saveAndFlush(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getContent().value()).isEqualTo("updated");
        assertThat(commentCaptor.getValue().getUpdatedAt()).isEqualTo(existingComment.getUpdatedAt());
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isNotNull();
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CommentUpdatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{json}");
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxEventCaptor.getValue().getId());
    }

    @Test
    void shouldReturnExistingCommentWithoutSavingWhenContentIsUnchanged() {
        var command = new UpdateCommentCommand(POST_ID, COMMENT_ID, USER_ID, "same");
        var existingComment = persistedComment(COMMENT_ID, POST_ID, USER_ID, "same", CommentStatus.ACTIVE);

        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID)).thenReturn(Optional.of(existingComment));

        var response = updateCommentUseCase.updateComment(command);

        assertThat(response.commentId()).isEqualTo(COMMENT_ID);
        assertThat(response.content()).isEqualTo("same");
        assertThat(response.updatedAt()).isEqualTo(existingComment.getUpdatedAt());
        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentUpdatedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentNotFoundExceptionWhenCommentDoesNotExistForPost() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateCommentUseCase.updateComment(
                new UpdateCommentCommand(POST_ID, COMMENT_ID, USER_ID, "updated")))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(COMMENT_ID.toString());

        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentUpdatedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentOwnershipExceptionWhenRequesterIsNotTheAuthor() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(persistedComment(COMMENT_ID, POST_ID, OTHER_USER_ID, "before", CommentStatus.ACTIVE)));

        assertThatThrownBy(() -> updateCommentUseCase.updateComment(
                new UpdateCommentCommand(POST_ID, COMMENT_ID, USER_ID, "updated")))
                .isInstanceOf(CommentOwnershipException.class)
                .hasMessageContaining(COMMENT_ID.toString());

        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentUpdatedDomainEvent.class));
    }

    @Test
    void shouldThrowCommentNotActiveExceptionWhenCommentIsDeletedOrBanned() {
        when(commentRepository.findByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(persistedComment(COMMENT_ID, POST_ID, USER_ID, "before", CommentStatus.DELETED)));

        assertThatThrownBy(() -> updateCommentUseCase.updateComment(
                new UpdateCommentCommand(POST_ID, COMMENT_ID, USER_ID, "updated")))
                .isInstanceOf(CommentNotActiveException.class)
                .hasMessageContaining(COMMENT_ID.toString());

        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).saveAndFlush(any(Comment.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentUpdatedDomainEvent.class));
    }

    private Comment persistedComment(UUID commentId, UUID postId, UUID authorId, String content, CommentStatus status) {
        var now = Instant.now();
        return persistedComment(commentId, postId, authorId, content, status, now, now);
    }

    private Comment persistedComment(
            UUID commentId,
            UUID postId,
            UUID authorId,
            String content,
            CommentStatus status,
            Instant createdAt,
            Instant updatedAt) {
        return new Comment(
                new CommentId(commentId),
                new PostId(postId),
                new UserId(authorId),
                new CommentContent(content),
                null,
                status,
                createdAt,
                updatedAt,
                status == CommentStatus.ACTIVE ? null : updatedAt
        );
    }

    private void stubUpdatedEvent(Comment comment) {
        var event = CommentUpdatedEvent.builder()
                .commentId(comment.getId().value())
                .postId(comment.getPostId().value())
                .userId(comment.getUserId().value())
                .content(comment.getContent().value())
                .replyTo(comment.getReplyTo())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
        when(commentEventMapper.toCommentUpdatedEvent(comment)).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{json}");
    }
}
