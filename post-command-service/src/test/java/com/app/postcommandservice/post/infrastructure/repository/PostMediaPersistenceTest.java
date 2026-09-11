package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
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
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaPersistenceTest {

    @Autowired private PostRepository postRepository;
    @Autowired private PostJpaRepository postJpaRepository;
    @Autowired private PostMediaJpaRepository mediaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear() {
        mediaRepository.deleteAll();
        postJpaRepository.deleteAll();
    }

    @Test
    void shouldRoundTripMixedMediaReorderWithoutUuidChurnAndRemoveOrphans() {
        var one = media(MediaType.IMAGE, 1);
        var two = media(MediaType.VIDEO, 2);
        var three = media(MediaType.IMAGE, 3);
        var saved = postRepository.save(post(UUID.randomUUID(), List.of(one, two, three)));

        var loaded = postRepository.findById(saved.getId().value()).orElseThrow();
        assertThat(loaded.getInfo().title()).isEqualTo("title");
        assertThat(loaded.getMedia()).extracting(PostMedia::id).containsExactly(one.id(), two.id(), three.id());
        assertThat(loaded.getMedia().get(1).thumbnailUrl()).isEqualTo(two.thumbnailUrl());

        var reversed = List.of(withOrder(three, 1), withOrder(two, 2), withOrder(one, 3));
        var reordered = postRepository.saveAndFlush(loaded.update(loaded.getInfo(), reversed).post());
        assertThat(reordered.getMedia()).extracting(PostMedia::id).containsExactly(three.id(), two.id(), one.id());

        var replacement = media(MediaType.VIDEO, 1);
        postRepository.saveAndFlush(reordered.update(reordered.getInfo(), List.of(replacement)).post());
        assertThat(mediaRepository.count()).isEqualTo(1);
        assertThat(mediaRepository.findById(replacement.id())).isPresent();
        assertThat(mediaRepository.findById(one.id())).isEmpty();
    }

    @Test
    void shouldRejectCrossPostMediaIdAndCascadeRawPostDelete() {
        var shared = media(MediaType.IMAGE, 1);
        var first = postRepository.save(post(UUID.randomUUID(), List.of(shared)));
        var secondId = UUID.randomUUID();

        assertThatThrownBy(() -> postRepository.save(post(secondId, List.of(shared))))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("belongs to another post");

        jdbc.update("DELETE FROM posts WHERE id = ?", first.getId().value());
        assertThat(mediaRepository.findById(shared.id())).isEmpty();
    }

    @Test
    void shouldEnforceMediaOrderAndRejectForeignMediaOnUpdate() {
        var firstMedia = media(MediaType.IMAGE, 1);
        var first = postRepository.save(post(UUID.randomUUID(), List.of(firstMedia)));
        var second = postRepository.save(post(UUID.randomUUID(), List.of(media(MediaType.IMAGE, 1))));

        var duplicate = PostMediaEntity.builder().id(UUID.randomUUID())
                .post(postJpaRepository.findById(first.getId().value()).orElseThrow())
                .url("https://cdn.test/duplicate").mediaType(MediaType.IMAGE).order(1).build();
        assertThatThrownBy(() -> mediaRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> postRepository.saveAndFlush(
                second.update(second.getInfo(), List.of(withOrder(firstMedia, 1))).post()))
                .isInstanceOf(InvalidPostMediaException.class)
                .hasMessageContaining("belongs to another post");
    }

    @Test
    void shouldRollbackStagedReorderAndClearMediaOnSoftDelete() {
        var one = media(MediaType.IMAGE, 1);
        var two = media(MediaType.VIDEO, 2);
        var saved = postRepository.saveAndFlush(post(UUID.randomUUID(), List.of(one, two)));
        var rollback = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> rollback.executeWithoutResult(status -> {
            postRepository.saveAndFlush(saved.update(saved.getInfo(),
                    List.of(withOrder(two, 1), withOrder(one, 2))).post());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        var afterRollback = postRepository.findById(saved.getId().value()).orElseThrow();
        assertThat(afterRollback.getMedia()).extracting(PostMedia::id).containsExactly(one.id(), two.id());
        postRepository.saveAndFlush(afterRollback.delete());
        assertThat(mediaRepository.count()).isZero();
    }

    @Test
    void shouldAdvanceVersionForMediaOnlyEditAndNormalizeNullDescription() {
        var original = media(MediaType.IMAGE, 1);
        var saved = postRepository.saveAndFlush(post(UUID.randomUUID(), List.of(original)));
        var before = postJpaRepository.findById(saved.getId().value()).orElseThrow();
        var beforeVersion = before.getVersion();
        var beforeUpdatedAt = before.getUpdatedAt();

        var changed = postRepository.saveAndFlush(saved.update(saved.getInfo(), List.of(new PostMedia(
                original.id(), "https://cdn.test/replaced", null, MediaType.IMAGE, null, 1))).post());
        var after = postJpaRepository.findById(saved.getId().value()).orElseThrow();
        assertThat(after.getVersion()).isGreaterThan(beforeVersion);
        assertThat(after.getUpdatedAt()).isAfterOrEqualTo(beforeUpdatedAt);
        assertThat(changed.getUpdatedAt()).isNotNull();

        jdbc.update("UPDATE posts SET description = NULL WHERE id = ?", saved.getId().value());
        assertThat(postRepository.findById(saved.getId().value()).orElseThrow().getDescription().value()).isEmpty();
    }

    private Post post(UUID id, List<PostMedia> media) {
        return new Post(new PostId(id), new UserId(UUID.randomUUID()), null,
                new PostInfo("title", new PostDescription("description"), new PostTaggedUsers(Set.of("alice")),
                        new PostTags(Set.of("java")), PostType.BASIC), media, PostStatus.ACTIVE, Instant.now(), Instant.now());
    }

    private PostMedia media(MediaType type, int order) {
        return new PostMedia(UUID.randomUUID(), "https://cdn.test/" + UUID.randomUUID(),
                type == MediaType.VIDEO ? "https://cdn.test/thumb.jpg" : null, type,
                type == MediaType.VIDEO ? 10 : null, order);
    }

    private PostMedia withOrder(PostMedia item, int order) {
        return new PostMedia(item.id(), item.url(), item.thumbnailUrl(), item.mediaType(), item.duration(), order);
    }
}
