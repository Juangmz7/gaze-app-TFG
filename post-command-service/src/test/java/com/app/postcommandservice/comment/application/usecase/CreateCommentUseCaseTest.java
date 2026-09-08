package com.app.postcommandservice.comment.application.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRelationshipValidationRepository;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.application.repository.CommentRequestIdempotencyRepository;
import com.app.postcommandservice.comment.domain.events.CommentCreatedDomainEvent;
import com.app.postcommandservice.comment.domain.exception.CommentBlockedException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.events.CommentCreatedEvent;
import com.app.postcommandservice.comment.infrastructure.mapper.CommentEventMapper;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateCommentUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID POST_OWNER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private CommentRequestIdempotencyRepository commentRequestIdempotencyRepository;

    @Mock
    private CommentRelationshipValidationRepository commentRelationshipValidationRepository;

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
    private ArgumentCaptor<CommentCreatedDomainEvent> domainEventCaptor;

    @InjectMocks
    private CreateCommentUseCase createCommentUseCase;

    @Test
    void shouldCreateCommentSuccessfullyWhenTargetPostIsActive() {
        var command = new CreateCommentCommand(UUID.randomUUID(), POST_ID, USER_ID, "hello comment", null);
        var post = activePost(POST_OWNER_ID);
        var persistedComment = persistedComment(command.currentUserId(), command.content(), command.replyTo());

        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(command.correlationId()))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        when(commentRepository.save(any(Comment.class))).thenReturn(persistedComment);
        stubCreatedEvent(persistedComment);

        var response = createCommentUseCase.createComment(command);

        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.content()).isEqualTo("hello comment");
        assertThat(response.replyTo()).isNull();

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getStatus()).isEqualTo(CommentStatus.ACTIVE);
        verify(commentRequestIdempotencyRepository).save(command.correlationId(), persistedComment.getId().value());
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, POST_OWNER_ID);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(command.correlationId());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CommentCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getPayload()).isEqualTo("{json}");
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxEventCaptor.getValue().getId());
    }

    @Test
    void shouldAcquireCorrelationLockBeforeCheckingExistingIdempotencyRecord() {
        var command = new CreateCommentCommand(UUID.randomUUID(), POST_ID, USER_ID, "hello comment", null);
        var post = activePost(POST_OWNER_ID);
        var persistedComment = persistedComment(command.currentUserId(), command.content(), command.replyTo());

        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(command.correlationId()))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        when(commentRepository.save(any(Comment.class))).thenReturn(persistedComment);
        stubCreatedEvent(persistedComment);

        createCommentUseCase.createComment(command);

        InOrder inOrder = inOrder(commentRequestIdempotencyRepository);
        inOrder.verify(commentRequestIdempotencyRepository).acquireCorrelationLock(command.correlationId());
        inOrder.verify(commentRequestIdempotencyRepository).findCommentIdByCorrelationId(command.correlationId());
    }

    @Test
    void shouldReturnPreviouslyCreatedCommentWithoutSideEffectsWhenCorrelationIdAlreadyExists() {
        var correlationId = UUID.randomUUID();
        var existingComment = persistedComment(POST_OWNER_ID, "existing", null);

        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId))
                .thenReturn(Optional.of(existingComment.getId().value()));
        when(commentRepository.findById(existingComment.getId().value())).thenReturn(Optional.of(existingComment));

        var response = createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "new content", UUID.randomUUID()));

        assertThat(response.commentId()).isEqualTo(existingComment.getId().value());
        assertThat(response.postId()).isEqualTo(existingComment.getPostId().value());
        assertThat(response.userId()).isEqualTo(existingComment.getUserId().value());
        assertThat(response.content()).isEqualTo("existing");
        assertThat(response.replyTo()).isEqualTo(existingComment.getReplyTo());
        verify(postRepository, never()).findById(any(UUID.class));
        verify(commentRepository, never()).findByIdAndPostId(any(UUID.class), any(UUID.class));
        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRequestIdempotencyRepository, never()).save(any(UUID.class), any(UUID.class));
        verify(commentRelationshipValidationRepository, never()).existsBlockRelationship(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CommentCreatedDomainEvent.class));
    }

    @Test
    void shouldCreateReplyCommentWhenParentBelongsToSamePostAndNoBlockExists() {
        var parentCommentId = UUID.randomUUID();
        var parentAuthorId = UUID.randomUUID();
        var command = new CreateCommentCommand(UUID.randomUUID(), POST_ID, USER_ID, "reply", parentCommentId);
        var post = activePost(POST_OWNER_ID);
        var parentComment = persistedComment(parentAuthorId, "parent", null);
        var persistedComment = persistedComment(command.currentUserId(), command.content(), parentCommentId);

        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(command.correlationId()))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.of(parentComment));
        when(commentRepository.save(any(Comment.class))).thenReturn(persistedComment);
        stubCreatedEvent(persistedComment);

        var response = createCommentUseCase.createComment(command);

        assertThat(response.replyTo()).isEqualTo(parentCommentId);
        verify(commentRequestIdempotencyRepository).save(command.correlationId(), persistedComment.getId().value());
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, POST_OWNER_ID);
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, parentAuthorId);
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenTargetPostDoesNotExist() {
        var correlationId = UUID.randomUUID();
        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId)).thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowPostNotActiveExceptionWhenTargetPostIsNotActive() {
        var correlationId = UUID.randomUUID();
        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId)).thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithStatus(POST_OWNER_ID, PostStatus.DELETED)));

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(PostNotActiveException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowCommentNotFoundExceptionWhenReplyTargetDoesNotExistForPost() {
        var correlationId = UUID.randomUUID();
        var parentCommentId = UUID.randomUUID();
        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId)).thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "reply", parentCommentId)))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(parentCommentId.toString());

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    void shouldThrowCommentBlockedExceptionWhenSenderIsBlockedByPostOwnerOrViceVersa() {
        var correlationId = UUID.randomUUID();
        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId)).thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, POST_OWNER_ID)).thenReturn(true);

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(CommentBlockedException.class)
                .hasMessageContaining("post owner");

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    void shouldThrowCommentBlockedExceptionWhenSenderIsBlockedByParentCommentAuthorOrViceVersa() {
        var correlationId = UUID.randomUUID();
        var parentCommentId = UUID.randomUUID();
        var parentAuthorId = UUID.randomUUID();
        var parentComment = persistedComment(parentAuthorId, "parent", null);

        when(commentRequestIdempotencyRepository.findCommentIdByCorrelationId(correlationId)).thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.of(parentComment));
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, POST_OWNER_ID)).thenReturn(false);
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, parentAuthorId)).thenReturn(true);

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(correlationId, POST_ID, USER_ID, "reply", parentCommentId)))
                .isInstanceOf(CommentBlockedException.class)
                .hasMessageContaining("parent comment author");

        verify(commentRepository, never()).save(any(Comment.class));
    }

    private Post activePost(UUID ownerId) {
        return postWithStatus(ownerId, PostStatus.ACTIVE);
    }

    private Post postWithStatus(UUID ownerId, PostStatus status) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                null,
                PostType.BASIC,
                new PostDescription("post"),
                new PostTaggedUsers(java.util.Set.of()),
                new PostTags(java.util.Set.of()),
                status,
                now,
                now
        );
    }

    private Comment persistedComment(UUID authorId, String content, UUID replyTo) {
        var now = Instant.now();
        return new Comment(
                new CommentId(UUID.randomUUID()),
                new PostId(POST_ID),
                new UserId(authorId),
                new CommentContent(content),
                replyTo,
                CommentStatus.ACTIVE,
                now,
                now,
                null
        );
    }

    private void stubCreatedEvent(Comment comment) {
        var event = CommentCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .commentId(comment.getId().value())
                .postId(comment.getPostId().value())
                .userId(comment.getUserId().value())
                .content(comment.getContent().value())
                .replyTo(comment.getReplyTo())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
        when(commentEventMapper.toCommentCreatedEvent(any(UUID.class), any(UUID.class), eq(comment), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{json}");
    }
}
