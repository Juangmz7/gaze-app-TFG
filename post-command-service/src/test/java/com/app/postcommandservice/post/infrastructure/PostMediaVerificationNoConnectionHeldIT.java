package com.app.postcommandservice.post.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.application.port.MediaVerifier;
import com.app.postcommandservice.post.application.usecase.PostMediaVerificationService;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Proves that {@code PostMediaVerificationService.process(...)} holds no physical database
 * connection while {@code MediaVerifier.verify(...)} is executing — the entire point of
 * {@code findPostSnapshot} returning an immutable projection in its own short read-only
 * transaction instead of a managed entity. {@code MediaVerifier} itself is mocked here (it is
 * exercised against a real Azurite emulator in {@code PostMediaVerificationFlowIT}); this test's
 * boundary is purely about this service's own transaction management around a slow external
 * call, observed via the real {@code HikariDataSource} bean's pool metrics.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaVerificationNoConnectionHeldIT {

    private static final UUID AUTHOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostMediaVerificationService postMediaVerificationService;

    @Autowired
    private HikariDataSource hikariDataSource;

    @MockitoBean
    private MediaVerifier mediaVerifier;

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
    }

    @Test
    void shouldNotHoldADbConnectionWhileMediaVerifierExecutes() throws Exception {
        var mediaId = UUID.randomUUID();
        var post = seedPendingPost(mediaId);

        var verifierEntered = new CountDownLatch(1);
        var releaseVerifier = new CountDownLatch(1);
        var activeConnectionsDuringVerify = new AtomicInteger(-1);

        when(mediaVerifier.verify(any())).thenAnswer(invocation -> {
            verifierEntered.countDown();
            releaseVerifier.await(10, TimeUnit.SECONDS);
            return MediaVerificationResult.success(Map.of());
        });

        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(post.getId())
                .media(List.of(PostMediaUploadedMediaPayload.builder()
                        .id(mediaId)
                        .url("https://cdn/blob.jpg")
                        .thumbnailUrl("https://cdn/blob.jpg")
                        .mediaType(MediaType.IMAGE)
                        .order(1)
                        .build()))
                .build();

        Thread worker = new Thread(() -> postMediaVerificationService.process(event));
        worker.start();

        assertThat(verifierEntered.await(5, TimeUnit.SECONDS)).isTrue();
        // Give Hikari a brief moment to reflect a connection if one were (incorrectly) held.
        Thread.sleep(300L);
        activeConnectionsDuringVerify.set(hikariDataSource.getHikariPoolMXBean().getActiveConnections());

        releaseVerifier.countDown();
        worker.join(10000);

        assertThat(activeConnectionsDuringVerify.get()).isZero();
        assertThat(postJpaRepository.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.ACCEPTED);
    }

    private PostEntity seedPendingPost(UUID mediaId) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("pending post")
                .tags(List.of("test"))
                .status(PostStatus.PENDING)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url("https://cdn/blob.jpg")
                .thumbnailUrl("https://cdn/blob.jpg")
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        return postJpaRepository.save(postEntity);
    }
}
