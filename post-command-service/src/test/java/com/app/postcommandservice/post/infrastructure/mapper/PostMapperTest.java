package com.app.postcommandservice.post.infrastructure.mapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PostMapperTest {

    private final PostMapper postMapper = new PostMapper();

    @Test
    void shouldMapPostAndPostMediaEntitiesToDomainModelsWithoutCounterFields() {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PostEntity entity = PostEntity.builder()
                .id(postId)
                .userId(userId)
                .collabId(null)
                .postType(PostType.BASIC)
                .description("description")
                .tags(List.of("java", "spring"))
                .status(PostStatus.ACCEPTED)
                .createdAt(now)
                .updatedAt(now)
                .build();

        entity.addMedia(PostMediaEntity.builder()
                .id(UUID.randomUUID())
                .url("https://cdn/image.jpg")
                .thumbnailUrl("https://cdn/image.jpg")
                .mediaType(MediaType.IMAGE)
                .taggedUsers(List.of("alice"))
                .mediaOrder(1)
                .build());
        entity.addMedia(PostMediaEntity.builder()
                .id(UUID.randomUUID())
                .url("https://cdn/video.mp4")
                .thumbnailUrl("https://cdn/thumb.jpg")
                .mediaType(MediaType.VIDEO)
                .duration(30)
                .mediaOrder(2)
                .build());

        Post post = postMapper.toDomain(entity);

        assertThat(post.getId().value()).isEqualTo(postId);
        assertThat(post.getUserId().value()).isEqualTo(userId);
        assertThat(post.getDescription().value()).isEqualTo("description");
        assertThat(post.getTags().value()).containsExactlyInAnyOrder("java", "spring");
        assertThat(post.getPostType()).isEqualTo(PostType.BASIC);
        assertThat(post.getStatus()).isEqualTo(PostStatus.ACCEPTED);
        assertThat(post.getCreatedAt()).isEqualTo(now);
        assertThat(post.getUpdatedAt()).isEqualTo(now);

        List<PostMedia> media = post.getMedia();
        assertThat(media).hasSize(2);
        assertThat(media.get(0).getOrder()).isEqualTo(1);
        assertThat(media.get(0).getMediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(media.get(0).getThumbnailUrl()).isEqualTo("https://cdn/image.jpg");
        assertThat(media.get(0).getDuration()).isNull();
        assertThat(media.get(0).getTaggedUsers()).containsExactly("alice");
        assertThat(media.get(1).getOrder()).isEqualTo(2);
        assertThat(media.get(1).getMediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(media.get(1).getThumbnailUrl()).isEqualTo("https://cdn/thumb.jpg");
        assertThat(media.get(1).getDuration()).isEqualTo(30);
        assertThat(media.get(1).getTaggedUsers()).isEmpty();

        assertThat(fieldNames(Post.class))
                .noneMatch(name -> name.toLowerCase().contains("count"));
        assertThat(fieldNames(PostMedia.class))
                .noneMatch(name -> name.toLowerCase().contains("count"));
    }

    @Test
    void shouldMapPostDomainModelToEntityWithoutCounterFields() {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Post post = Post.create(
                new com.app.postcommandservice.post.domain.model.valueobj.PostId(postId),
                new com.app.postcommandservice.shared.domain.model.user.valueobj.UserId(userId),
                null,
                new com.app.postcommandservice.post.domain.model.PostInfo(
                        new com.app.postcommandservice.post.domain.model.valueobj.PostDescription("description"),
                        new com.app.postcommandservice.post.domain.model.valueobj.PostTags(Set.of("java")),
                        PostType.BASIC
                ),
                List.of(PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of("alice"), 1))
        );

        PostEntity entity = postMapper.toEntity(post);

        assertThat(entity.getId()).isEqualTo(postId);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getTags()).containsExactly("java");
        assertThat(entity.getMedia()).hasSize(1);
        assertThat(entity.getMedia().get(0).getTaggedUsers()).containsExactly("alice");
        assertThat(fieldNames(PostEntity.class))
                .noneMatch(name -> name.toLowerCase().contains("count"));
        assertThat(fieldNames(PostMediaEntity.class))
                .noneMatch(name -> name.toLowerCase().contains("count"));
    }

    private static List<String> fieldNames(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .toList();
    }
}
