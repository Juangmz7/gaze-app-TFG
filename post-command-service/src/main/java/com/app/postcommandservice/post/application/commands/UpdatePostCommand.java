package com.app.postcommandservice.post.application.commands;

import java.util.Set;
import java.util.UUID;
import java.util.List;
import com.app.postcommandservice.post.domain.model.PostMedia;

public record UpdatePostCommand(
        UUID postId,
        UUID currentUserId,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        String title,
        List<PostMedia> media
) {
    public UpdatePostCommand(UUID postId, UUID currentUserId, String description, Set<String> taggedUsers, Set<String> postTags) {
        this(postId, currentUserId, description, taggedUsers, postTags, null, null);
    }
}
