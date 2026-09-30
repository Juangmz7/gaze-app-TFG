package com.app.postcommandservice.post.application.mapper;

import java.util.List;
import java.util.UUID;

import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.application.dto.PostMediaResponse;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostMedia;

public final class PostApplicationMapper {

    private PostApplicationMapper() {
    }

    public static List<PostMedia> toDomainMedia(UUID postId, List<PostMediaCommand> mediaCommands) {
        if (mediaCommands == null) {
            return List.of();
        }
        return mediaCommands.stream()
                .map(command -> PostMedia.create(
                        postId,
                        command.url(),
                        command.thumbnailUrl(),
                        command.mediaType(),
                        command.duration(),
                        command.order()
                ))
                .toList();
    }

    public static PostResponse toResponse(Post post) {
        return new PostResponse(
                post.getId().value(),
                post.getUserId().value(),
                post.getCollabId(),
                post.getPostType(),
                post.getDescription().value(),
                post.getTaggedUsers().value(),
                post.getTags().value(),
                toMediaResponse(post.getMedia()),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }

    public static List<PostMediaResponse> toMediaResponse(List<PostMedia> media) {
        return media.stream()
                .map(postMedia -> new PostMediaResponse(
                        postMedia.getId(),
                        postMedia.getUrl(),
                        postMedia.getThumbnailUrl(),
                        postMedia.getMediaType(),
                        postMedia.getDuration(),
                        postMedia.getOrder()
                ))
                .toList();
    }
}
