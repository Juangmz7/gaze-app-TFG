package com.app.postcommandservice.post.infrastructure.mapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class PostMapper {

    public PostEntity toEntity(Post post) {
        PostEntity entity = PostEntity.builder()
                .id(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(new ArrayList<>(post.getTaggedUsers().value()))
                .tags(new ArrayList<>(post.getTags().value()))
                .status(post.getStatus())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();

        for (PostMedia postMedia : post.getMedia()) {
            entity.addMedia(toMediaEntity(postMedia));
        }

        return entity;
    }

    public Post toDomain(PostEntity entity) {
        List<PostMedia> media = new ArrayList<>();
        for (PostMediaEntity mediaEntity : entity.getMedia()) {
            media.add(toDomainMedia(mediaEntity));
        }
        media.sort(Comparator.comparingInt(PostMedia::getOrder));

        return new Post(
                new PostId(entity.getId()),
                new UserId(entity.getUserId()),
                entity.getCollabId(),
                new PostInfo(
                        new PostDescription(entity.getDescription()),
                        new PostTaggedUsers(new LinkedHashSet<>(entity.getTaggedUsers())),
                        new PostTags(new LinkedHashSet<>(entity.getTags())),
                        entity.getPostType()
                ),
                media,
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public PostMediaEntity toMediaEntity(PostMedia postMedia) {
        return PostMediaEntity.builder()
                .id(postMedia.getId())
                .url(postMedia.getUrl())
                .thumbnailUrl(postMedia.getThumbnailUrl())
                .mediaType(postMedia.getMediaType())
                .duration(postMedia.getDuration())
                .mediaOrder(postMedia.getOrder())
                .build();
    }

    public PostMedia toDomainMedia(PostMediaEntity entity) {
        UUID postId = entity.getPost() != null ? entity.getPost().getId() : null;
        return new PostMedia(
                entity.getId(),
                postId,
                entity.getUrl(),
                entity.getThumbnailUrl(),
                entity.getMediaType(),
                entity.getDuration(),
                entity.getMediaOrder()
        );
    }
}
