package com.app.postcommandservice.post.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.exception.InvalidPostTagException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.exception.DomainException;
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

        var post = existingPost("description", Set.of("java"), media);

        assertThat(post.getMedia()).hasSize(2);
        assertThat(post.getMedia().get(0).getOrder()).isEqualTo(1);
        assertThat(post.getMedia().get(1).getOrder()).isEqualTo(2);
        assertThat(post.getPostInfo().postType()).isEqualTo(PostType.BASIC);
    }

    @Test
    void shouldThrowWhenCreatingPostWithEmptyMediaList() {
        assertThatThrownBy(() -> existingPost("description", Set.of("java"), List.of()))
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

        assertThatThrownBy(() -> existingPost("description", Set.of("java"), media))
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

        assertThatThrownBy(() -> existingPost("description", Set.of("java"), media))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void shouldAllowNullDurationForImageMediaType() {
        var postId = UUID.randomUUID();
        var media = PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of(), 1);

        assertThat(media.getThumbnailUrl()).isEqualTo("https://cdn/image.jpg");
        assertThat(media.getDuration()).isNull();
    }

    @Test
    void shouldThrowWhenImageMediaHasNonNullDuration() {
        var postId = UUID.randomUUID();

        assertThatThrownBy(() -> PostMedia.create(
                postId, "https://cdn/image.jpg", null, MediaType.IMAGE, 10, Set.of(), 1))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("IMAGE");
    }

    @Test
    void shouldSetThumbnailUrlEqualToUrlForImageMedia() {
        var postId = UUID.randomUUID();
        var media = PostMedia.create(
                postId, "https://cdn/image.jpg", "https://cdn/ignored-thumb.jpg", MediaType.IMAGE, null, Set.of(), 1);

        assertThat(media.getThumbnailUrl()).isEqualTo(media.getUrl());
    }

    @Test
    void shouldCreatePendingPostWithVideoMediaAndNullDuration() {
        var postId = new PostId(UUID.randomUUID());
        var now = Instant.now();
        var media = List.of(PostMedia.create(
                postId.value(), "https://cdn/video.mp4", "https://cdn/thumb.jpg", MediaType.VIDEO, null, Set.of(), 1));

        var post = new Post(
                postId,
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(new PostDescription("description"), new PostTags(Set.of("java")), PostType.BASIC),
                media,
                PostStatus.PENDING,
                now,
                now
        );

        assertThat(post.getStatus()).isEqualTo(PostStatus.PENDING);
        assertThat(post.getMedia().get(0).getDuration()).isNull();
        assertThat(post.getMedia().get(0).getMediaType()).isEqualTo(MediaType.VIDEO);
    }

    @Test
    void shouldHoldTaggedUsersOnPostMediaAndNotOnPostInfo() {
        var postId = UUID.randomUUID();
        var media = PostMedia.create(
                postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of("alice", "bob"), 1);

        assertThat(media.getTaggedUsers()).containsExactlyInAnyOrder("alice", "bob");

        var post = existingPost("description", Set.of("java"), List.of(media));
        assertThat(post.getPostInfo().getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("taggedUsers");
    }

    @Test
    void shouldTransitionPendingToAccepted() {
        var post = pendingPost();

        var acceptedPost = post.acceptMediaUpload();

        assertThat(acceptedPost).isNotSameAs(post);
        assertThat(acceptedPost.getStatus()).isEqualTo(PostStatus.ACCEPTED);
    }

    @Test
    void shouldTransitionPendingToMediaUploadFailed() {
        var post = pendingPost();

        var failedPost = post.failMediaUpload();

        assertThat(failedPost).isNotSameAs(post);
        assertThat(failedPost.getStatus()).isEqualTo(PostStatus.MEDIA_UPLOAD_FAILED);
    }

    @Test
    void shouldThrowDomainExceptionWhenAcceptingMediaUploadFromNonPendingStatus() {
        var postId = UUID.randomUUID();
        var post = existingPost("description", Set.of("java"), List.of(imageMedia(postId, 1)));

        assertThatThrownBy(post::acceptMediaUpload)
                .isInstanceOf(DomainException.class)
                .isInstanceOf(PostNotPendingException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void shouldThrowDomainExceptionWhenFailingMediaUploadFromNonPendingStatus() {
        var postId = UUID.randomUUID();
        var post = existingPost("description", Set.of("java"), List.of(imageMedia(postId, 1)));

        assertThatThrownBy(post::failMediaUpload)
                .isInstanceOf(DomainException.class)
                .isInstanceOf(PostNotPendingException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void shouldReturnUnchangedResultWhenIncomingStateMatchesCurrentState() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1));
        var post = existingPost("description", Set.of("java"), media);

        var result = post.update(post.getPostInfo());

        assertThat(result.changed()).isFalse();
        assertThat(result.post()).isSameAs(post);
    }

    @Test
    void shouldReturnChangedResultWhenStateChanges() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1));
        var post = existingPost("description", Set.of("java"), media);

        var newInfo = new PostInfo(
                new PostDescription("updated"),
                new PostTags(Set.of("spring")),
                PostType.BASIC
        );

        var result = post.update(newInfo);

        assertThat(result.changed()).isTrue();
        assertThat(result.post()).isNotSameAs(post);
        assertThat(result.post().getDescription().value()).isEqualTo("updated");
    }

    @Test
    void shouldKeepExistingMediaUnchangedWhenUpdatingPostInfo() {
        var postId = UUID.randomUUID();
        var media = List.of(imageMedia(postId, 1), videoMedia(postId, 2));
        var post = existingPost("description", Set.of("java"), media);

        var newInfo = new PostInfo(
                new PostDescription("updated description"),
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
    void shouldReturnDeletedCopyWhenDeletingAnAcceptedPost() {
        var postId = UUID.randomUUID();
        var post = existingPost("description", Set.of("java"), List.of(imageMedia(postId, 1)));

        var deletedPost = post.delete();

        assertThat(deletedPost).isNotSameAs(post);
        assertThat(deletedPost.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(deletedPost.getId()).isEqualTo(post.getId());
        assertThat(deletedPost.getMedia()).isEmpty();
    }

    @Test
    void shouldThrowWhenDeletingANonAcceptedPost() {
        var now = Instant.now();
        var postId = UUID.randomUUID();
        var post = new Post(
                new PostId(postId),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription("description"),
                        new PostTags(new LinkedHashSet<>(Set.of("java"))),
                        PostType.BASIC
                ),
                List.of(),
                PostStatus.DELETED,
                now,
                now
        );

        assertThatThrownBy(post::delete)
                .isInstanceOf(com.app.postcommandservice.post.domain.exception.PostNotAcceptedException.class)
                .hasMessageContaining("ACCEPTED");
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
                        new PostTags(Set.of("java")),
                        PostType.COLLAB
                ),
                List.of(imageMedia(postId, 1)),
                PostStatus.ACCEPTED,
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
        var post = existingPost("description", Set.of("java"), List.of(imageMedia(postId, 1)));
        var collabId = UUID.randomUUID();

        var linkedPost = post.linkToCollab(collabId);

        assertThat(linkedPost).isNotSameAs(post);
        assertThat(linkedPost.getCollabId()).isEqualTo(collabId);
        assertThat(linkedPost.getPostType()).isEqualTo(PostType.COLLAB);
        assertThat(linkedPost.getDescription()).isEqualTo(post.getDescription());
    }

    private PostMedia imageMedia(UUID postId, int order) {
        return PostMedia.create(postId, "https://cdn/image-" + order + ".jpg", null, MediaType.IMAGE, null, Set.of(), order);
    }

    private PostMedia videoMedia(UUID postId, int order) {
        return PostMedia.create(postId, "https://cdn/video-" + order + ".mp4", "https://cdn/thumb-" + order + ".jpg",
                MediaType.VIDEO, 30, Set.of(), order);
    }

    private Post pendingPost() {
        var postId = new PostId(UUID.randomUUID());
        var now = Instant.now();
        var media = List.of(imageMedia(postId.value(), 1));
        return new Post(
                postId,
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(new PostDescription("description"), new PostTags(Set.of("java")), PostType.BASIC),
                media,
                PostStatus.PENDING,
                now,
                now
        );
    }

    private Post existingPost(String description, Set<String> tags, List<PostMedia> media) {
        var now = Instant.now();
        return new Post(
                new PostId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription(description),
                        new PostTags(new LinkedHashSet<>(tags)),
                        PostType.BASIC
                ),
                media,
                PostStatus.ACCEPTED,
                now,
                now
        );
    }
}
