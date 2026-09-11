package com.app.postcommandservice.post.infrastructure.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class PostEventMapperTest {

    private final PostEventMapper mapper = new PostEventMapper();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldRetainTitleAndOrderedMediaInCreatedAndUpdatedEventJson() throws Exception {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var post = new Post(new PostId(UUID.randomUUID()), new UserId(UUID.randomUUID()), null,
                new PostInfo("A title", new PostDescription("description"), new PostTaggedUsers(Set.of("alice")),
                        new PostTags(Set.of("java")), PostType.BASIC),
                List.of(new PostMedia(secondId, "https://cdn.test/video.mp4", "https://cdn.test/video.jpg", MediaType.VIDEO, 42, 2),
                        new PostMedia(firstId, "https://cdn.test/image.jpg", null, MediaType.IMAGE, null, 1)),
                PostStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));

        var created = mapper.toPostCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), post, Instant.now());
        var updated = mapper.toPostUpdatedEvent(UUID.randomUUID(), UUID.randomUUID(), post, Instant.now());
        assertThat(created.title()).isEqualTo("A title");
        assertThat(updated.title()).isEqualTo("A title");
        var createdJson = objectMapper.readTree(objectMapper.writeValueAsString(created.media()));
        var updatedJson = objectMapper.readTree(objectMapper.writeValueAsString(updated.media()));

        for (var event : List.of(createdJson, updatedJson)) {
            assertThat(event).hasSize(2);
            assertThat(event.get(0).path("id").asText()).isEqualTo(firstId.toString());
            assertThat(event.get(0).path("order").asInt()).isEqualTo(1);
            assertThat(event.get(0).path("thumbnailUrl").isNull()).isTrue();
            assertThat(event.get(1).path("id").asText()).isEqualTo(secondId.toString());
            assertThat(event.get(1).path("thumbnailUrl").asText()).isEqualTo("https://cdn.test/video.jpg");
            assertThat(event.get(1).path("duration").asInt()).isEqualTo(42);
        }
    }
}
