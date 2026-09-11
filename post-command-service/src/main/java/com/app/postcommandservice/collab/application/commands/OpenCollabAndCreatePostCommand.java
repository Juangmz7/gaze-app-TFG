package com.app.postcommandservice.collab.application.commands;

import java.util.Set;
import java.util.UUID;
import java.util.List;
import com.app.postcommandservice.post.domain.model.PostMedia;

public record OpenCollabAndCreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        String title,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        List<PostMedia> media
) {
}
