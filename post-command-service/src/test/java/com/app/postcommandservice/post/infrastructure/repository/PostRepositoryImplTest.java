package com.app.postcommandservice.post.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.mapper.PostMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostRepositoryImpl}, using a real {@link PostMapper} (a pure, dependency-free
 * component) alongside a mocked {@link PostJpaRepository}, so the assertions exercise the actual
 * entity/media reconciliation logic rather than a stub of it.
 *
 * <p>{@code save} is only ever called by {@code CreatePostUseCase} for a brand-new post (never persisted
 * before), so it must insert directly without any existence check. {@code saveAndFlush} is only ever
 * called for posts that are already persisted, so it must load-and-update unconditionally.
 */
@ExtendWith(MockitoExtension.class)
class PostRepositoryImplTest {

    @Mock
    private PostJpaRepository postJpaRepository;

    private final PostMapper postMapper = new PostMapper();

    private PostRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new PostRepositoryImpl(postJpaRepository, postMapper);
    }

    @Test
    void shouldInsertNewPostDirectlyWithoutCheckingWhetherItAlreadyExists() {
        var postId = UUID.randomUUID();
        var post = newPost(postId, "brand new post", List.of(
                PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, 1)
        ));

        when(postJpaRepository.save(any(PostEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = repository.save(post);

        assertThat(saved.getId()).isEqualTo(post.getId());
        assertThat(saved.getDescription().value()).isEqualTo("brand new post");
        verify(postJpaRepository, never()).findById(any());
        verify(postJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenSaveAndFlushTargetsAPostThatDoesNotExist() {
        var postId = UUID.randomUUID();
        var post = newPost(postId, "orphan update", List.of(
                PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, 1)
        ));

        when(postJpaRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.saveAndFlush(post))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(postId.toString());

        verify(postJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldReconcileMediaAndApplyScalarChangesWhenUpdatingAnExistingPost() {
        var postId = UUID.randomUUID();
        var keptMediaId = UUID.randomUUID();
        var removedMediaId = UUID.randomUUID();

        var existing = PostEntity.builder()
                .id(postId)
                .userId(UUID.randomUUID())
                .collabId(null)
                .postType(PostType.BASIC)
                .description("before")
                .taggedUsers(new java.util.ArrayList<>(List.of("alice")))
                .tags(new java.util.ArrayList<>(List.of("old-tag")))
                .status(PostStatus.ACTIVE)
                .build();
        existing.addMedia(PostMediaEntity.builder()
                .id(keptMediaId)
                .url("https://cdn/kept-old-url.jpg")
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        existing.addMedia(PostMediaEntity.builder()
                .id(removedMediaId)
                .url("https://cdn/removed.jpg")
                .mediaType(MediaType.IMAGE)
                .mediaOrder(2)
                .build());

        when(postJpaRepository.findById(postId)).thenReturn(Optional.of(existing));
        when(postJpaRepository.saveAndFlush(existing)).thenReturn(existing);

        var newMediaId = UUID.randomUUID();
        var post = new Post(
                new PostId(postId),
                new UserId(existing.getUserId()),
                null,
                new PostInfo(
                        new PostDescription("after"),
                        new PostTaggedUsers(Set.of("bob")),
                        new PostTags(Set.of("new-tag")),
                        PostType.BASIC
                ),
                List.of(
                        new PostMedia(keptMediaId, postId, "https://cdn/kept-new-url.jpg", null, MediaType.IMAGE, null, 1),
                        new PostMedia(newMediaId, postId, "https://cdn/new.jpg", null, MediaType.IMAGE, null, 2)
                ),
                PostStatus.ACTIVE,
                null,
                null
        );

        var result = repository.saveAndFlush(post);

        // saveAndFlush is called twice: once to stage out existing media orders before
        // reconciliation (avoiding a unique constraint violation), once to commit the final state.
        verify(postJpaRepository, org.mockito.Mockito.times(2)).saveAndFlush(existing);

        assertThat(result.getDescription().value()).isEqualTo("after");
        assertThat(result.getTaggedUsers().value()).containsExactly("bob");
        assertThat(result.getTags().value()).containsExactly("new-tag");

        assertThat(existing.getMedia()).hasSize(2);
        assertThat(existing.getMedia()).extracting(PostMediaEntity::getId)
                .containsExactlyInAnyOrder(keptMediaId, newMediaId);
        assertThat(existing.getMedia())
                .filteredOn(mediaEntity -> mediaEntity.getId().equals(keptMediaId))
                .first()
                .satisfies(mediaEntity -> {
                    assertThat(mediaEntity.getUrl()).isEqualTo("https://cdn/kept-new-url.jpg");
                    assertThat(mediaEntity.getMediaOrder()).isEqualTo(1);
                });
        assertThat(existing.getMedia())
                .noneMatch(mediaEntity -> mediaEntity.getId().equals(removedMediaId));
    }

    private Post newPost(UUID postId, String description, List<PostMedia> media) {
        return Post.create(
                new PostId(postId),
                new UserId(UUID.randomUUID()),
                null,
                new PostInfo(
                        new PostDescription(description),
                        new PostTaggedUsers(Set.of()),
                        new PostTags(Set.of("java")),
                        PostType.BASIC
                ),
                media
        );
    }
}
