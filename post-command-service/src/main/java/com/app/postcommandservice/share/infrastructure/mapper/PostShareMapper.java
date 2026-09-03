package com.app.postcommandservice.share.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.share.domain.model.PostShare;
import com.app.postcommandservice.share.infrastructure.entity.PostShareEntity;
import com.app.postcommandservice.share.infrastructure.entity.PostShareId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

@Component
public class PostShareMapper {

    public PostShareEntity toEntity(PostShare postShare) {
        return PostShareEntity.builder()
                .id(new PostShareId(postShare.getPostId().value(), postShare.getUserId().value()))
                .createdAt(postShare.getCreatedAt())
                .build();
    }

    public PostShare toDomain(PostShareEntity entity) {
        return new PostShare(
                new PostId(entity.getId().getPostId()),
                new UserId(entity.getId().getUserId()),
                entity.getCreatedAt()
        );
    }
}
