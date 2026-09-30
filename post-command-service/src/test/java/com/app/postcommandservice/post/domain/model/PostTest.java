package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.exception.InvalidPostTagException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
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
    void shouldBuildPostWithPostInfoAndValidOrderedMediaList() {
        var postId = UUID.randomUUID();
        var media = List.of(
                imageMedia(postId, 1),
                videoMedia(postId, 2)
        );

        var post = existingPost("description", Set.of("alice"), Set.of("java"), media);

        assertThat(post.getMedia()).hasSize(2);
        assertThat(post.getMedia().get(0).getOrder()).isEqualTo(1);
        assertThat(post.getMedia().get(1).getOrder()).isEqualTo(2);
        assertThat(post.getPostInfo().postType()).isEqualTo(PostType.BASIC);
    }

    @Test
    void shouldThrowWhenCreatingPostWithEmptyMediaList() {
        assertThatThrownBy(() -> existingPost("description", Set.of(), Set.of("java"), List.of()))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("at least one media item");
    }

    @Test
    void shouldThrowWhenTwoPostMediaItemsShareTheSameOrderIndex() {
        var postId = UUID.randomUUID();
        var media = List.of(
                imageMedia(postId, 1),
                imageMedia(postId, 1)
        );

        assertThatThrownBy(() -> existingPost("description", Set.of(), Set.of("java"), media))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void shouldThrowWhenMediaOrderValuesAreNotContiguous() {
        var postId = UUID.randomUUID();
        var media = List.of(
                imageMedia(postId, 1),
                imageMedia(postId, 3)
        );

        assertThatThrownBy(() -> existingPost("description", Set.of(), Set.of("java"), media))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void shouldAllowNullDurationAndThumbnailUrlForImageMediaType() {
        var postId = UUID.randomUUID();
        var media = PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, 1);

        assertThat(media.getThumbnailUrl()).isNull();
        assertThat(media.getDuration()).isNull();
    }

    @Test
    void shouldThrowWhenImageMediaHasNonNullDuration() {
        var postId = UUID.randomUUID();

        assertThatThrownBy(() -> PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, 10, 1))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("IMAGE");
    }

    @Test
    void shouldReturnUnchangedResultWhenIncomingStateMatchesCurrentState() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1));
        var post = existingPost("description", Set.of("alice"), Set.of("java"), media);

        var result = post.update(post.getPostInfo());

        assertThat(result.changed()).isFalse();
        assertThat(result.post()).isSameAs(post);
        assertThat(result.newlyTaggedUsers()).isEmpty();
    }

    @Test
    void shouldReturnChangedResultAndOnlyNewlyAddedTaggedUsersWhenStateChanges() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1));
        var post = existingPost("description", Set.of("alice"), Set.of("java"), media);

        var newInfo = new PostInfo(
                new PostDescription("updated"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice", "bob"))),
                new PostTags(Set.of("spring")),
                PostType.BASIC
        );

        var result = post.update(newInfo);

        assertThat(result.changed()).isTrue();
        assertThat(result.post()).isNotSameAs(post);
        assertThat(result.post().getDescription().value()).isEqualTo("updated");
        assertThat(result.post().getTaggedUsers().value()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(result.newlyTaggedUsers()).containsExactly("bob");
    }

    @Test
    void shouldKeepExistingMediaUnchangedWhenUpdatingPostInfo() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1), videoMedia(postId, 2));
        var post = existingPost("description", Set.of("alice"), Set.of("java"), media);

        var newInfo = new PostInfo(
                new PostDescription("updated description"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice", "bob"))),
                new PostTags(Set.of("spring")),
                PostType.BASIC
        );

        var result = post.update(newInfo);

        assertThat(result.changed()).isTrue();
        assertThat(result.post().getMedia()).isEqualTo(post.getMedia());
        assertThat(result.post().getMedia()).hasSize(2);
        assertThat(result.post().getMedia().get(0).getOrder()).isEqualTo(1);
        assertThat(result.post().getMedia().get(1).getOrder()).isEqualTo(2);
    }

    @Test
    void shouldReturnDeletedCopyWhenDeletingAnActivePost() {
        var postId = UUID.randomUUID();
        var post = existingPost("description", Set.of("alice"), Set.of("java"), List.of(imageMedia(postId, 1)));

        var deletedPost = post.delete();

        assertThat(deletedPost).isNotSameAs(post);
        assertThat(deletedPost.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(deletedPost.getId()).isEqualTo(post.getId());
        assertThat(deletedPost.getMedia()).isEmpty();
    }

    @Test
    void shouldThrowWhenDeletingANonActivePost() {
        var now = Instant.now();
        var postId = UUID.randomUUID();
        var post = new Post(
                new PostId(postId),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription("description"),
                        new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice"))),
                        new PostTags(new LinkedHashSet<>(Set.of("java"))),
                        PostType.BASIC
                ),
                List.of(),
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
        var postId = UUID.randomUUID();

        assertThatThrownBy(() -> new Post(
                new PostId(postId),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription("description"),
                        new PostTaggedUsers(Set.of()),
                        new PostTags(Set.of("java")),
                        PostType.COLLAB
                ),
                List.of(imageMedia(postId, 1)),
                PostStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collab posts must reference a collab");
    }

    @Test
    void shouldThrowWhenPostTagsIsConstructedWithAnEmptySet() {
        assertThatThrownBy(() -> new PostTags(Set.of()))
                .isInstanceOf(InvalidPostTagException.class)
                .hasMessageContaining("at least one tag");
    }

    @Test
    void shouldLinkPostToCollabAndForceColabPostType() {
        var postId = UUID.randomUUID();
        var post = existingPost("description", Set.of("alice"), Set.of("java"), List.of(imageMedia(postId, 1)));
        var collabId = UUID.randomUUID();

        var linkedPost = post.linkToCollab(collabId);

        assertThat(linkedPost).isNotSameAs(post);
        assertThat(linkedPost.getCollabId()).isEqualTo(collabId);
        assertThat(linkedPost.getPostType()).isEqualTo(PostType.COLLAB);
        assertThat(linkedPost.getDescription()).isEqualTo(post.getDescription());
    }

    private PostMedia imageMedia(UUID postId, int order) {
        return PostMedia.create(postId, "https://cdn/image-" + order + ".jpg", null, MediaType.IMAGE, null, order);
    }

    private PostMedia videoMedia(UUID postId, int order) {
        return PostMedia.create(postId, "https://cdn/video-" + order + ".mp4", "https://cdn/thumb-" + order + ".jpg",
                MediaType.VIDEO, 30, order);
    }

    private Post existingPost(String description, Set<String> taggedUsers, Set<String> tags, List<PostMedia> media) {
        var now = Instant.now();
        return new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription(description),
                        new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                        new PostTags(new LinkedHashSet<>(tags)),
                        PostType.BASIC
                ),
                media,
                PostStatus.ACTIVE,
                now,
                now
        );
    }
}
