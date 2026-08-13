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
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Post existingPost(String description, Set<String> taggedUsers, Set<String> tags) {
        var now = Instant.now();
        return new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new PostDescription(description),
                new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                new PostTags(new LinkedHashSet<>(tags)),
                PostStatus.ACTIVE,
                now,
                now
        );
    }
}
