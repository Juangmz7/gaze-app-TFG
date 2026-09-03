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

        var result = post.update(
                new PostDescription("description"),
                new PostTaggedUsers(Set.of("alice")),
                new PostTags(Set.of("java"))
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.post()).isSameAs(post);
        assertThat(result.newlyTaggedUsers()).isEmpty();
    }

    @Test
    void shouldReturnChangedResultAndOnlyNewlyAddedTaggedUsersWhenStateChanges() {
        var post = existingPost("description", Set.of("alice"), Set.of("java"));

        var result = post.update(
                new PostDescription("updated"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice", "bob"))),
                new PostTags(Set.of("spring"))
        );

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
                PostType.BASIC,
                new PostDescription("description"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice"))),
                new PostTags(new LinkedHashSet<>(Set.of("java"))),
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
                PostType.COLAB,
                new PostDescription("description"),
                new PostTaggedUsers(Set.of()),
                new PostTags(Set.of()),
                PostStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collab posts must reference a collab");
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
                PostType.BASIC,
                new PostDescription(description),
                new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                new PostTags(new LinkedHashSet<>(tags)),
                PostStatus.ACTIVE,
                now,
                now
        );
    }
}
