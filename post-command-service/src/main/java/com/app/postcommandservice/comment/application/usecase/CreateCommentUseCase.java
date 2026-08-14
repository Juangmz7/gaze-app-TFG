package com.app.postcommandservice.comment.application.usecase;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.CreateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.repository.CommentRelationshipValidationRepository;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.exception.CommentBlockedException;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.model.Comment;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Service
@RequiredArgsConstructor
public class CreateCommentUseCase {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final CommentRelationshipValidationRepository commentRelationshipValidationRepository;

    @Transactional
    public CommentResponse createComment(CreateCommentCommand command) {
        var post = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        validatePostIsActive(post.getId().value(), post.getStatus());
        validateBlockedRelationship(command.currentUserId(), post.getUserId().value(), "post owner");
        validateReplyTarget(command.postId(), command.currentUserId(), command.replyTo());

        var comment = Comment.create(
                new CommentId(UUID.randomUUID()),
                new PostId(command.postId()),
                new UserId(command.currentUserId()),
                new CommentContent(command.content()),
                command.replyTo()
        );

        return toResponse(commentRepository.save(comment));
    }

    private void validatePostIsActive(UUID postId, PostStatus status) {
        if (status != PostStatus.ACTIVE) {
            throw new PostNotActiveException(postId, status);
        }
    }

    private void validateReplyTarget(UUID postId, UUID currentUserId, UUID replyTo) {
        if (replyTo == null) {
            return;
        }

        var parentComment = commentRepository.findByIdAndPostId(replyTo, postId)
                .orElseThrow(() -> new CommentNotFoundException(replyTo));

        validateBlockedRelationship(currentUserId, parentComment.getUserId().value(), "parent comment author");
    }

    private void validateBlockedRelationship(UUID requesterUserId, UUID targetUserId, String target) {
        if (requesterUserId.equals(targetUserId)) {
            return;
        }
        if (commentRelationshipValidationRepository.existsBlockRelationship(requesterUserId, targetUserId)) {
            throw new CommentBlockedException(target);
        }
    }

    private CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId().value(),
                comment.getPostId().value(),
                comment.getUserId().value(),
                comment.getContent().value(),
                comment.getReplyTo(),
                comment.getUpdatedAt(),
                comment.getCreatedAt()
        );
    }
}
