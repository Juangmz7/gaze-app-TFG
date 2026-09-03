package com.app.postcommandservice.post.infrastructure.mapper;

import java.util.LinkedHashSet;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class PostMapper {

    public PostEntity toEntity(Post post) {
        return PostEntity.builder()
                .id(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(new java.util.ArrayList<>(post.getTaggedUsers().value()))
                .tags(new java.util.ArrayList<>(post.getTags().value()))
                .status(post.getStatus())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    public Post toDomain(PostEntity entity) {
        return new Post(
                new PostId(entity.getId()),
                new UserId(entity.getUserId()),
                entity.getCollabId(),
                entity.getPostType(),
                new PostDescription(entity.getDescription()),
                new PostTaggedUsers(new LinkedHashSet<>(entity.getTaggedUsers())),
                new PostTags(new LinkedHashSet<>(entity.getTags())),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
