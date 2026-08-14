package com.app.postcommandservice.like.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.like.domain.model.PostLike;
import com.app.postcommandservice.like.domain.model.PostLikeContext;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeEntity;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeId;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class PostLikeMapperTest {

    private final PostLikeMapper mapper = new PostLikeMapper();

    @Test
    void shouldMapContextBetweenDomainAndEntity() {
        var createdAt = Instant.now();
        var domain = new PostLike(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new PostLikeContext(PostLikeSource.USER_PROFILE, 6),
                createdAt
        );

        PostLikeEntity entity = mapper.toEntity(domain);
        PostLike mappedDomain = mapper.toDomain(entity);

        assertThat(entity.getSource()).isEqualTo(PostLikeSource.USER_PROFILE);
        assertThat(entity.getFeedPosition()).isEqualTo(6);
        assertThat(mappedDomain.getContext().source()).isEqualTo(PostLikeSource.USER_PROFILE);
        assertThat(mappedDomain.getContext().feedPosition()).isEqualTo(6);
        assertThat(mappedDomain.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void shouldMapEntityContextBackToDomain() {
        var createdAt = Instant.now();
        var postId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = PostLikeEntity.builder()
                .id(new PostLikeId(postId, userId))
                .source(PostLikeSource.SEARCH)
                .feedPosition(9)
                .createdAt(createdAt)
                .build();

        PostLike domain = mapper.toDomain(entity);

        assertThat(domain.getPostId().value()).isEqualTo(postId);
        assertThat(domain.getUserId().value()).isEqualTo(userId);
        assertThat(domain.getContext().source()).isEqualTo(PostLikeSource.SEARCH);
        assertThat(domain.getContext().feedPosition()).isEqualTo(9);
    }
}
