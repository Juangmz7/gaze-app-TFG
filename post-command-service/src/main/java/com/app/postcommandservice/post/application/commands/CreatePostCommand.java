package com.app.postcommandservice.post.application.commands;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;

public record CreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> postTags,
        List<PostMediaCommand> media
) {

    public CreatePostCommand {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(currentUserId, "currentUserId must not be null");
        Objects.requireNonNull(postType, "postType must not be null");
        Objects.requireNonNull(postTags, "postTags must not be null");
        postTags = Set.copyOf(postTags);
    }
}
