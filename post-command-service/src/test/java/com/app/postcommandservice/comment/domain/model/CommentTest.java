package com.app.postcommandservice.comment.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.comment.domain.exception.CommentNotActiveException;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentContent;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentId;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentTest {

    @Test
    void shouldReturnUnchangedResultWhenContentMatchesExistingContent() {
        var comment = persistedComment("same content", CommentStatus.ACTIVE);

        var result = comment.update("same content");

        assertThat(result.changed()).isFalse();
        assertThat(result.comment()).isSameAs(comment);
    }

    @Test
    void shouldReturnUpdatedCommentWhenContentChanges() {
        var comment = persistedComment("before", CommentStatus.ACTIVE);

        var result = comment.update("after");

        assertThat(result.changed()).isTrue();
        assertThat(result.comment()).isNotSameAs(comment);
        assertThat(result.comment().getContent().value()).isEqualTo("after");
        assertThat(result.comment().getCreatedAt()).isEqualTo(comment.getCreatedAt());
        assertThat(result.comment().getReplyTo()).isEqualTo(comment.getReplyTo());
        assertThat(result.comment().getStatus()).isEqualTo(comment.getStatus());
    }

    @Test
    void shouldThrowCommentNotActiveExceptionWhenCommentIsDeletedOrBanned() {
        assertThatThrownBy(() -> persistedComment("before", CommentStatus.DELETED).update("after"))
                .isInstanceOf(CommentNotActiveException.class);

        assertThatThrownBy(() -> persistedComment("before", CommentStatus.BANNED).update("after"))
                .isInstanceOf(CommentNotActiveException.class);
    }

    private Comment persistedComment(String content, CommentStatus status) {
        var now = Instant.now();
        return new Comment(
                new CommentId(UUID.randomUUID()),
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new CommentContent(content),
                null,
                status,
                now,
                now,
                status == CommentStatus.ACTIVE ? null : now
        );
    }
}
