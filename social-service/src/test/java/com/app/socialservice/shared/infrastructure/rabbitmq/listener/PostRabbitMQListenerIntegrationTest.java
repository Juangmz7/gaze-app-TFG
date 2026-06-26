package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.post.infrastructure.events.PostCreatedEvent;
import com.app.socialservice.post.infrastructure.events.PostDeletedEvent;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostRabbitMQListenerIntegrationTest {

    private static final String USER_STATS_KEY_PATTERN = "user:stats:%s:%s";

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private PostRabbitMQListener postRabbitMQListener;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @jakarta.annotation.Resource
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
        flushRedis();
    }

    @Test
    void postRabbitMqListenerProcessesPostCreatedEventsAndIncrementsRedisPostCount() {
        var userId = UUID.randomUUID();
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();

        postRabbitMQListener.onPostCreated(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(readCounter(userId, "postCount")).isEqualTo(1L);
    }

    @Test
    void postRabbitMqListenerCorrectlyIgnoresDuplicatePostCreatedEvents() {
        var userId = UUID.randomUUID();
        var event = PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();

        postRabbitMQListener.onPostCreated(event);
        postRabbitMQListener.onPostCreated(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(readCounter(userId, "postCount")).isEqualTo(1L);
    }

    @Test
    void postRabbitMqListenerProcessesPostDeletedEventsAndDecrementsRedisPostCount() {
        var userId = UUID.randomUUID();
        stringRedisTemplate.opsForValue().set(buildCounterKey(userId, "postCount"), "1");
        var event = PostDeletedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .userId(userId)
                .build();

        postRabbitMQListener.onPostDeleted(event);

        assertThat(processedEventsRepository.findById(event.id())).isPresent();
        assertThat(readCounter(userId, "postCount")).isZero();
    }

    private void flushRedis() {
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    private long readCounter(UUID userId, String counterName) {
        var rawValue = stringRedisTemplate.opsForValue().get(buildCounterKey(userId, counterName));
        if (rawValue == null) {
            return 0L;
        }
        return Long.parseLong(rawValue);
    }

    private String buildCounterKey(UUID userId, String counterName) {
        return String.format(USER_STATS_KEY_PATTERN, userId, counterName);
    }
}
