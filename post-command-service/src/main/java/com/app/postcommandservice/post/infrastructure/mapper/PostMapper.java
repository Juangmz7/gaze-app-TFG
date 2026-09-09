package com.app.postcommandservice.post.infrastructure.mapper;

import java.util.LinkedHashSet;

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
        var entity = PostEntity.builder()
                .id(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(new java.util.ArrayList<>(post.getTaggedUsers().value()))
                .tags(new java.util.ArrayList<>(post.getTags().value()))
                .title(post.getInfo().title())
                .status(post.getStatus())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
        entity.replaceMedia(post.getMedia().stream().map(media -> PostMediaEntity.builder()
                .id(media.id()).url(media.url()).thumbnailUrl(media.thumbnailUrl()).mediaType(media.mediaType())
                .duration(media.duration()).order(media.order()).post(entity).build()).toList());
        return entity;
    }

    public Post toDomain(PostEntity entity) {
        return new Post(
                new PostId(entity.getId()),
                new UserId(entity.getUserId()),
                entity.getCollabId(),
                new PostInfo(entity.getTitle(), new PostDescription(entity.getDescription()),
                        new PostTaggedUsers(new LinkedHashSet<>(entity.getTaggedUsers())),
                        new PostTags(new LinkedHashSet<>(entity.getTags())), entity.getPostType()),
                entity.getMedia().stream().map(media -> new PostMedia(media.getId(), media.getUrl(), media.getThumbnailUrl(),
                        media.getMediaType(), media.getDuration(), media.getOrder())).toList(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
