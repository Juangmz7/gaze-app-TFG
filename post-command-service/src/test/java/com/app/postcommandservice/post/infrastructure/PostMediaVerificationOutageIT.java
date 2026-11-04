package com.app.postcommandservice.post.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the task-37 infrastructure-failure path: this test deliberately does NOT start an
 * Azurite container, so the real {@code AzureMediaVerifier} bean (wired to the unreachable
 * placeholder {@code azure.storage.account-url=http://127.0.0.1:10000/...} from {@code
 * application-test.yaml}) fails with a genuine connection error when {@code
 * PostMediaUploadedListener} delegates to it — simulating an Azure outage without mocking
 * {@code MediaVerifier} itself. The listener container's retry policy ({@code
 * RabbitMQConfig#retryInterceptor}) then exhausts its bounded attempts and routes the message
 * to {@code q.post-command-service.post.media.dlq}, leaving the post {@code PENDING}.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostMediaVerificationOutageIT {

    private static final UUID AUTHOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldRouteMessageToDlqAfterRetriesAreExhaustedAndKeepPostPending() throws Exception {
        var mediaId = UUID.randomUUID();
        var post = seedPendingPost(mediaId);

        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(post.getId())
                .media(List.of(PostMediaUploadedMediaPayload.builder()
                        .id(mediaId)
                        .url("https://cdn/unreachable-azure/blob.jpg")
                        .thumbnailUrl("https://cdn/unreachable-azure/blob.jpg")
                        .mediaType(MediaType.IMAGE)
                        .order(1)
                        .build()))
                .build();

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getMedia().getUploaded(),
                event
        );

        // Each of the listener's 3 attempts first exhausts the Azure SDK's own internal HTTP
        // retry policy against the unreachable host (~40s of client-side backoff) before the
        // listener container's own retry interceptor backs off (2s, then 4s) and tries again,
        // so the whole cycle can comfortably take over two minutes.
        var dlqQueueName = rabbitMQProperties.getQueue().getPostMedia() + ".dlq";
        var dlqMessage = receiveMessage(dlqQueueName, 240000);

        assertThat(dlqMessage).isNotNull();
        assertThat(postJpaRepository.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.PENDING);
        assertThat(outboxEventRepository.count()).isZero();
    }

    private PostEntity seedPendingPost(UUID mediaId) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("pending post")
                .status(PostStatus.PENDING)
                .build();
        postEntity.addMedia(PostMediaEntity.builder()
                .id(mediaId)
                .url("https://cdn/unreachable-azure/blob.jpg")
                .thumbnailUrl("https://cdn/unreachable-azure/blob.jpg")
                .mediaType(MediaType.IMAGE)
                .mediaOrder(1)
                .build());
        return postJpaRepository.save(postEntity);
    }

    private Message receiveMessage(String queueName, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Message message;
        do {
            message = rabbitTemplate.receive(queueName);
            if (message != null) {
                return message;
            }
            Thread.sleep(300L);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }
}
