package com.app.postcommandservice.comment.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.comment.application.commands.DeleteCommentCommand;
import com.app.postcommandservice.comment.application.repository.CommentRepository;
import com.app.postcommandservice.comment.domain.exception.CommentNotFoundException;
import com.app.postcommandservice.comment.domain.exception.CommentOwnershipException;
import com.app.postcommandservice.comment.domain.model.Comment;

@Service
@RequiredArgsConstructor
public class DeleteCommentUseCase {

    private final CommentRepository commentRepository;

    @Transactional
    public void deleteComment(DeleteCommentCommand command) {
        var existingComment = commentRepository.findByIdAndPostId(command.commentId(), command.postId())
                .orElseThrow(() -> new CommentNotFoundException(command.commentId()));

        assertOwnership(existingComment, command.currentUserId());

        var deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        commentRepository.saveAndFlush(existingComment.delete(deletedAt));
    }

    private void assertOwnership(Comment comment, UUID currentUserId) {
        if (!comment.getUserId().value().equals(currentUserId)) {
            throw new CommentOwnershipException(comment.getId().value(), currentUserId);
        }
    }
}
