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

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRelationshipValidationRepository;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.exception.CommentBlockedException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private CommentRelationshipValidationRepository commentRelationshipValidationRepository;

    @Captor
    private ArgumentCaptor<Comment> commentCaptor;

    @InjectMocks
    private CreateCommentUseCase createCommentUseCase;

    @Test
    void shouldCreateCommentSuccessfullyWhenTargetPostIsActive() {
        var command = new CreateCommentCommand(POST_ID, USER_ID, "hello comment", null);
        var post = activePost(POST_OWNER_ID);
        var persistedComment = persistedComment(command.currentUserId(), command.content(), command.replyTo());

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        when(commentRepository.save(any(Comment.class))).thenReturn(persistedComment);

        var response = createCommentUseCase.createComment(command);

        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.content()).isEqualTo("hello comment");
        assertThat(response.replyTo()).isNull();

        verify(commentRepository).save(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getStatus()).isEqualTo(CommentStatus.ACTIVE);
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, POST_OWNER_ID);
    }

    @Test
    void shouldCreateReplyCommentWhenParentBelongsToSamePostAndNoBlockExists() {
        var parentCommentId = UUID.randomUUID();
        var parentAuthorId = UUID.randomUUID();
        var command = new CreateCommentCommand(POST_ID, USER_ID, "reply", parentCommentId);
        var post = activePost(POST_OWNER_ID);
        var parentComment = persistedComment(parentAuthorId, "parent", null);
        var persistedComment = persistedComment(command.currentUserId(), command.content(), parentCommentId);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.of(parentComment));
        when(commentRepository.save(any(Comment.class))).thenReturn(persistedComment);

        var response = createCommentUseCase.createComment(command);

        assertThat(response.replyTo()).isEqualTo(parentCommentId);
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, POST_OWNER_ID);
        verify(commentRelationshipValidationRepository).existsBlockRelationship(USER_ID, parentAuthorId);
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenTargetPostDoesNotExist() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createCommentUseCase.createComment(new CreateCommentCommand(POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowPostNotActiveExceptionWhenTargetPostIsNotActive() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithStatus(POST_OWNER_ID, PostStatus.DELETED)));

        assertThatThrownBy(() -> createCommentUseCase.createComment(new CreateCommentCommand(POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(PostNotActiveException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowCommentNotFoundExceptionWhenReplyTargetDoesNotExistForPost() {
        var parentCommentId = UUID.randomUUID();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(POST_ID, USER_ID, "reply", parentCommentId)))
                .isInstanceOf(CommentNotFoundException.class)
                .hasMessageContaining(parentCommentId.toString());

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    void shouldThrowCommentBlockedExceptionWhenSenderIsBlockedByPostOwnerOrViceVersa() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, POST_OWNER_ID)).thenReturn(true);

        assertThatThrownBy(() -> createCommentUseCase.createComment(new CreateCommentCommand(POST_ID, USER_ID, "hello", null)))
                .isInstanceOf(CommentBlockedException.class)
                .hasMessageContaining("post owner");

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    void shouldThrowCommentBlockedExceptionWhenSenderIsBlockedByParentCommentAuthorOrViceVersa() {
        var parentCommentId = UUID.randomUUID();
        var parentAuthorId = UUID.randomUUID();
        var parentComment = persistedComment(parentAuthorId, "parent", null);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(activePost(POST_OWNER_ID)));
        when(commentRepository.findByIdAndPostId(parentCommentId, POST_ID)).thenReturn(Optional.of(parentComment));
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, POST_OWNER_ID)).thenReturn(false);
        when(commentRelationshipValidationRepository.existsBlockRelationship(USER_ID, parentAuthorId)).thenReturn(true);

        assertThatThrownBy(() -> createCommentUseCase.createComment(
                new CreateCommentCommand(POST_ID, USER_ID, "reply", parentCommentId)))
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
}
