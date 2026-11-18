package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostRabbitMQListenerIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private PostRabbitMQListener postRabbitMQListener;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @jakarta.annotation.Resource
    private UserStatsRepository userStatsRepository;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
    }

    @Test
    void postRabbitMqListenerRecordsPostCreatedEventsAndIncrementsThePostCount() {
        var userId = UUID.randomUUID();
        var event = postCreatedEvent(userId);

        postRabbitMQListener.onPostCreated(event);

        assertThat(processedEventsRepository.findByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).isPresent();
        assertThat(postCount(userId)).isEqualTo(1L);
    }

    @Test
    void postRabbitMqListenerCorrectlyIgnoresDuplicatePostCreatedEvents() {
        var userId = UUID.randomUUID();
        var event = postCreatedEvent(userId);

        postRabbitMQListener.onPostCreated(event);
        postRabbitMQListener.onPostCreated(event);

        assertThat(processedEventsRepository.findByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).isPresent();
        assertThat(postCount(userId)).isEqualTo(1L);
    }

    @Test
    void postRabbitMqListenerCountsConcurrentRedeliveriesOfTheSameEventOnce() throws Exception {
        var userId = UUID.randomUUID();
        var event = postCreatedEvent(userId);
        var executor = Executors.newFixedThreadPool(8);

        try {
            var deliveries = IntStream.range(0, 16)
                    .<Callable<Void>>mapToObj(index -> () -> {
                        postRabbitMQListener.onPostCreated(event);
                        return null;
                    })
                    .toList();
            for (var future : executor.invokeAll(deliveries)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(postCount(userId)).isEqualTo(1L);
    }

    @Test
    void postRabbitMqListenerRecordsPostDeletedEventsAndDecrementsThePostCount() {
        var userId = UUID.randomUUID();
        postRabbitMQListener.onPostCreated(postCreatedEvent(userId));
        var event = PostDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();

        postRabbitMQListener.onPostDeleted(event);

        assertThat(processedEventsRepository.findByIdAndTargetDatabase(event.id(), TargetDatabase.POSTGRES)).isPresent();
        assertThat(postCount(userId)).isZero();
    }

    private long postCount(UUID userId) {
        return userStatsRepository.getStats(userId).postCount();
    }

    private PostCreatedEvent postCreatedEvent(UUID userId) {
        return PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();
    }
}
