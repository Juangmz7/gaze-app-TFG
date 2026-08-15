package com.app.postcommandservice.comment.application.usecase;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.UpdateCommentCommand;
import com.app.postcommandservice.comment.application.dto.CommentResponse;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;

@Service
@RequiredArgsConstructor
public class UpdateCommentUseCase {

    private final CommentRepository commentRepository;

    @Transactional
    public CommentResponse updateComment(UpdateCommentCommand command) {
        var existingComment = commentRepository.findByIdAndPostId(command.commentId(), command.postId())
                .orElseThrow(() -> new CommentNotFoundException(command.commentId()));

        assertOwnership(existingComment, command.currentUserId());

        var updateResult = existingComment.update(command.content());
        if (!updateResult.changed()) {
            return toResponse(existingComment);
        }

        return toResponse(commentRepository.saveAndFlush(updateResult.comment()));
    }

    private void assertOwnership(Comment comment, UUID currentUserId) {
        if (!comment.getUserId().value().equals(currentUserId)) {
            throw new CommentOwnershipException(comment.getId().value(), currentUserId);
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
