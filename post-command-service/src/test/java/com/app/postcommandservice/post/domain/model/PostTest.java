package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostTest {

    @Test
    void shouldReturnUnchangedResultWhenIncomingStateMatchesCurrentState() {
        var post = existingPost("description", Set.of("alice"), Set.of("java"));

        var result = post.update(info("description", Set.of("alice"), Set.of("java")), post.getMedia());

        assertThat(result.changed()).isFalse();
        assertThat(result.post()).isSameAs(post);
        assertThat(result.newlyTaggedUsers()).isEmpty();
    }

    @Test
    void shouldReturnChangedResultAndOnlyNewlyAddedTaggedUsersWhenStateChanges() {
        var post = existingPost("description", Set.of("alice"), Set.of("java"));

        var result = post.update(info("updated", new LinkedHashSet<>(Set.of("alice", "bob")), Set.of("spring")), post.getMedia());

        assertThat(result.changed()).isTrue();
        assertThat(result.post()).isNotSameAs(post);
        assertThat(result.post().getDescription().value()).isEqualTo("updated");
        assertThat(result.post().getTaggedUsers().value()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(result.newlyTaggedUsers()).containsExactly("bob");
    }

    @Test
    void shouldReturnDeletedCopyWhenDeletingAnActivePost() {
        var post = existingPost("description", Set.of("alice"), Set.of("java"));

        var deletedPost = post.delete();

        assertThat(deletedPost).isNotSameAs(post);
        assertThat(deletedPost.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(deletedPost.getId()).isEqualTo(post.getId());
    }

    @Test
    void shouldThrowWhenDeletingANonActivePost() {
        var now = Instant.now();
        var post = new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                null,
                info("description", Set.of("alice"), Set.of("java")),
                java.util.List.of(media()),
                PostStatus.DELETED,
                now,
                now
        );

        assertThatThrownBy(post::delete)
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.PostNotActiveException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void shouldRequireCollabIdWhenPostTypeIsColab() {
        assertThatThrownBy(() -> new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                null,
                info("description", Set.of(), Set.of(), PostType.COLAB),
                java.util.List.of(media()),
                PostStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collab posts must reference a collab");
    }

    @Test
    void shouldRejectActivePostWithoutMedia() {
        var now = Instant.now();

        assertThatThrownBy(() -> new Post(new PostId(UUID.randomUUID()), new UserId(UUID.randomUUID()), null,
                info("description", Set.of(), Set.of()), java.util.List.of(), PostStatus.ACTIVE, now, now))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("at least one media");
    }

    @Test
    void shouldRejectDuplicateOrNonContiguousMediaOrder() {
        var now = Instant.now();
        var first = media();
        var duplicateOrder = new PostMedia(UUID.randomUUID(), "https://cdn.test/second.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1);

        assertThatThrownBy(() -> new Post(new PostId(UUID.randomUUID()), new UserId(UUID.randomUUID()), null,
                info("description", Set.of(), Set.of()), java.util.List.of(first, duplicateOrder), PostStatus.ACTIVE, now, now))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void shouldAllowImageMediaWithoutThumbnailOrDuration() {
        assertThat(new PostMedia(UUID.randomUUID(), "https://cdn.test/image.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1).thumbnailUrl()).isNull();
    }

    @Test
    void shouldRejectMediaWithoutIdOrAbsoluteHttpUrl() {
        assertThatThrownBy(() -> new PostMedia(null, "https://cdn.test/image.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("id");

        assertThatThrownBy(() -> new PostMedia(UUID.randomUUID(), "relative/image.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("absolute HTTP(S)");
    }

    @Test
    void shouldRejectInvalidMediaDurationForItsType() {
        assertThatThrownBy(() -> new PostMedia(UUID.randomUUID(), "https://cdn.test/image.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, 1, 1))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("image duration");

        assertThatThrownBy(() -> new PostMedia(UUID.randomUUID(), "https://cdn.test/video.mp4", "https://cdn.test/cover.jpg",
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.VIDEO, 0, 1))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostMediaException.class)
                .hasMessageContaining("duration must be positive");
    }

    @Test
    void shouldRejectTitleLongerThanPersistenceLimit() {
        assertThatThrownBy(() -> new PostInfo("x".repeat(256), new PostDescription("description"),
                new PostTaggedUsers(Set.of()), new PostTags(Set.of()), PostType.BASIC))
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.InvalidPostInfoException.class)
                .hasMessageContaining("255");
    }

    @Test
    void shouldLinkPostToCollabAndForceColabPostType() {
        var post = existingPost("description", Set.of("alice"), Set.of("java"));
        var collabId = UUID.randomUUID();

        var linkedPost = post.linkToCollab(collabId);

        assertThat(linkedPost).isNotSameAs(post);
        assertThat(linkedPost.getCollabId()).isEqualTo(collabId);
        assertThat(linkedPost.getPostType()).isEqualTo(PostType.COLAB);
        assertThat(linkedPost.getDescription()).isEqualTo(post.getDescription());
    }

    private Post existingPost(String description, Set<String> taggedUsers, Set<String> tags) {
        var now = Instant.now();
        return new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                null,
                info(description, taggedUsers, tags),
                java.util.List.of(media()),
                PostStatus.ACTIVE,
                now,
                now
        );
    }

    private PostInfo info(String description, Set<String> taggedUsers, Set<String> tags) {
        return info(description, taggedUsers, tags, PostType.BASIC);
    }

    private PostInfo info(String description, Set<String> taggedUsers, Set<String> tags, PostType type) {
        return new PostInfo(null, new PostDescription(description), new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                new PostTags(new LinkedHashSet<>(tags)), type);
    }

    private PostMedia media() {
        return new PostMedia(UUID.randomUUID(), "https://cdn.test/post.jpg", null,
                com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1);
    }
}
