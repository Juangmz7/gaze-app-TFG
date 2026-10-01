package com.app.postcommandservice.post.infrastructure.events;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the wire-format acceptance criteria for task 31 using a real Jackson {@link ObjectMapper}
 * (the same {@code tools.jackson.databind.ObjectMapper} type the production {@code JsonMapper} wraps),
 * not the Java object graph. Mocked-{@code JsonMapper} publisher tests would not catch a regression
 * here, since they never actually serialize anything.
 */
class PostEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldOmitDurationFromJsonWhenNullAndIncludeItWhenSet() {
        var imageMedia = PostMediaEventPayload.builder()
                .id(UUID.randomUUID())
                .url("https://cdn/image.jpg")
                .thumbnailUrl("https://cdn/image.jpg")
                .mediaType(MediaType.IMAGE)
                .duration(null)
                .taggedUsers(Set.of())
                .order(1)
                .build();
        var videoMedia = PostMediaEventPayload.builder()
                .id(UUID.randomUUID())
                .url("https://cdn/video.mp4")
                .thumbnailUrl("https://cdn/thumb.jpg")
                .mediaType(MediaType.VIDEO)
                .duration(30)
                .taggedUsers(Set.of())
                .order(2)
                .build();

        JsonNode imageNode = objectMapper.readTree(objectMapper.writeValueAsString(imageMedia));
        JsonNode videoNode = objectMapper.readTree(objectMapper.writeValueAsString(videoMedia));

        assertThat(imageNode.has("duration")).isFalse();
        assertThat(videoNode.has("duration")).isTrue();
        assertThat(videoNode.get("duration").asInt()).isEqualTo(30);
    }

    @Test
    void shouldSerializePostCreatedEventWithTaggedUsersNestedInEachMediaAndNotAtTopLevel() {
        var now = Instant.now();
        var media = List.of(
                PostMediaEventPayload.builder()
                        .id(UUID.randomUUID())
                        .url("https://cdn/image.jpg")
                        .thumbnailUrl("https://cdn/image.jpg")
                        .mediaType(MediaType.IMAGE)
                        .duration(null)
                        .taggedUsers(Set.of("alice", "bob"))
                        .order(1)
                        .build(),
                PostMediaEventPayload.builder()
                        .id(UUID.randomUUID())
                        .url("https://cdn/video.mp4")
                        .thumbnailUrl("https://cdn/thumb.jpg")
                        .mediaType(MediaType.VIDEO)
                        .duration(30)
                        .taggedUsers(Set.of())
                        .order(2)
                        .build()
        );
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(now)
                .postId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .collabId(null)
                .postType(PostType.BASIC)
                .description("hello")
                .postTags(Set.of("java"))
                .media(media)
                .createdAt(now)
                .updatedAt(now)
                .build();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(root.has("taggedUsers")).isFalse();
        assertThat(root.path("media")).hasSize(2);
        assertThat(root.path("media").get(0).has("taggedUsers")).isTrue();
        assertThat(root.path("media").get(0).path("taggedUsers"))
                .extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("alice", "bob");
        assertThat(root.path("media").get(0).has("duration")).isFalse();
        assertThat(root.path("media").get(1).has("duration")).isTrue();
        assertThat(root.path("media").get(1).get("duration").asInt()).isEqualTo(30);
        assertThat(root.path("media").get(1).path("taggedUsers")).isEmpty();
    }
}
