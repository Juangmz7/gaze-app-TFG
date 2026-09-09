package com.app.postcommandservice.post.application.commands;

import java.util.Set;
import java.util.UUID;

import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.PostMedia;
import java.util.List;

public record CreatePostCommand(
        UUID correlationId,
        UUID currentUserId,
        UUID collabId,
        PostType postType,
        String description,
        Set<String> taggedUsers,
        Set<String> postTags,
        String title,
        List<PostMedia> media
) {
}
