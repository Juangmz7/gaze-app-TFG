package com.app.postcommandservice.post.infrastructure.repository;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@code PostEntity.version} (added for concurrency safety) causes Hibernate to
 * reject a save based on a stale in-memory copy once another transaction has already persisted a
 * newer version of the same row.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostRepositoryImplOptimisticLockingIT {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Test
    void shouldThrowObjectOptimisticLockingFailureExceptionWhenSavingAStaleInMemoryCopy() {
        var postId = UUID.randomUUID();
        var seed = PostEntity.builder()
                .id(postId)
                .userId(UUID.randomUUID())
                .collabId(null)
                .postType(PostType.BASIC)
                .description("before")
                .status(PostStatus.ACCEPTED)
                .build();
        seed.addMedia(PostMediaEntity.builder()
                .id(UUID.randomUUID())
                .url("https://cdn/image.jpg")
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        postJpaRepository.saveAndFlush(seed);

        // Two independent reads, each in its own transaction/persistence context, simulate two
        // concurrent requests that both observed the same initial version.
        var firstReaderCopy = postJpaRepository.findById(postId).orElseThrow();
        var secondReaderCopy = postJpaRepository.findById(postId).orElseThrow();

        firstReaderCopy.setDescription("updated by first writer");
        postJpaRepository.saveAndFlush(firstReaderCopy);

        var persistedAfterFirstWrite = postJpaRepository.findById(postId).orElseThrow();
        assertThat(persistedAfterFirstWrite.getDescription()).isEqualTo("updated by first writer");
        assertThat(persistedAfterFirstWrite.getVersion()).isGreaterThan(secondReaderCopy.getVersion());

        secondReaderCopy.setDescription("updated by second writer");

        assertThatThrownBy(() -> postJpaRepository.saveAndFlush(secondReaderCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        var finalState = postJpaRepository.findById(postId).orElseThrow();
        assertThat(finalState.getDescription()).isEqualTo("updated by first writer");
    }
}
