package com.app.postcommandservice.like.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeEntity;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeId;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class PostLikeMapper {

    public PostLikeEntity toEntity(PostLike postLike) {
        return PostLikeEntity.builder()
                .id(new PostLikeId(postLike.getPostId().value(), postLike.getUserId().value()))
                .createdAt(postLike.getCreatedAt())
                .build();
    }

    public PostLike toDomain(PostLikeEntity entity) {
        return new PostLike(
                new PostId(entity.getId().getPostId()),
                new UserId(entity.getId().getUserId()),
                entity.getCreatedAt()
        );
    }
}
