package com.app.postcommandservice.view.infrastructure;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;
import com.app.postcommandservice.view.infrastructure.repository.PostViewJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostViewFlowTest {

    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostViewJpaRepository postViewJpaRepository;

    @Autowired
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        postViewJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldReturnAcceptedAndPublishProcessPostViewCommand() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        var queueName = "test.post.view.command." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess());
        var postId = seedPost(UUID.randomUUID()).getId();
        var viewId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "viewId", viewId,
                "postId", postId,
                "context", Map.of("source", "home_feed", "feedPosition", 1),
                "playbackMetrics", Map.of(
                        "durationMs", 2000,
                        "timeWatchedMs", 1500,
                        "completionPercent", 75,
                        "exitReason", "scroll_next"
                )
        ));

        mockMvc.perform(post("/api/posts/{postId}/views", postId)
                        .with(jwtFor(VIEWER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var commandPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(commandPayload.get("viewId")).isEqualTo(viewId.toString());
        assertThat(commandPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(commandPayload.get("userId")).isEqualTo(VIEWER_ID.toString());
        assertThat(commandPayload.get("source")).isEqualTo("HOME_FEED");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldProcessCommandPersistViewAndPublishViewedEvent() throws Exception {
        var post = seedPost(UUID.randomUUID());
        var queueName = "test.post.viewed." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getViewed());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(post.getId(), VIEWER_ID, UUID.randomUUID())
        );

        waitUntil(() -> postViewJpaRepository.count() == 1);

        assertThat(postViewJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventsRepository.count()).isEqualTo(1);
        var persistedView = postViewJpaRepository.findAll().getFirst();
        assertThat(persistedView.getPostId()).isEqualTo(post.getId());
        assertThat(persistedView.getUserId()).isEqualTo(VIEWER_ID);
        assertThat(persistedView.getServerTimestamp()).isNotNull();
        assertThat(persistedView.getReplayCount()).isEqualTo(1);

        var eventMessage = receiveMessage(queueName);
        assertThat(eventMessage).isNotNull();
        var eventPayload = objectMapper.readValue(eventMessage.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(eventPayload.get("userId")).isEqualTo(VIEWER_ID.toString());
        assertThat(eventPayload.get("serverTimestamp")).isNotNull();
        assertThat(eventPayload.get("replayCount")).isEqualTo(1);

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldDiscardViewCommandWhenPostDoesNotExist() throws Exception {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(UUID.randomUUID(), VIEWER_ID, UUID.randomUUID())
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postViewJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldDiscardViewCommandWhenPostIsInactive() throws Exception {
        var inactivePost = postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .description("inactive")
                .status(PostStatus.DELETED)
                .build());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(inactivePost.getId(), VIEWER_ID, UUID.randomUUID())
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postViewJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldDiscardViewCommandWhenUsersAreBlocked() throws Exception {
        var ownerId = UUID.randomUUID();
        var post = seedPost(ownerId);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(ownerId, VIEWER_ID),
                Instant.now()
        ));

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(post.getId(), VIEWER_ID, UUID.randomUUID())
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postViewJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldIncrementReplayCountForSequentialViewsOfTheSameUserAndPost() throws Exception {
        var post = seedPost(UUID.randomUUID());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(post.getId(), VIEWER_ID, UUID.randomUUID())
        );
        waitUntil(() -> postViewJpaRepository.count() == 1);

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getView().getProcess(),
                command(post.getId(), VIEWER_ID, UUID.randomUUID())
        );
        waitUntil(() -> postViewJpaRepository.count() == 2);

        var replayCounts = postViewJpaRepository.findAll().stream()
                .sorted(Comparator.comparingInt(view -> view.getReplayCount()))
                .map(view -> view.getReplayCount())
                .toList();

        assertThat(replayCounts).containsExactly(1, 2);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(processedEventsRepository.count()).isEqualTo(2);
    }

    private PostEntity seedPost(UUID ownerId) {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .description("active")
                .status(PostStatus.ACTIVE)
                .build());
    }

    private ProcessPostViewCommand command(UUID postId, UUID userId, UUID viewId) {
        return new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                viewId,
                postId,
                userId,
                PostViewSource.HOME_FEED,
                1,
                2000,
                1500,
                75,
                PostViewExitReason.SCROLL_NEXT
        );
    }

    private void bindQueue(RabbitAdmin rabbitAdmin, String queueName, String exchangeName, String routingKey) {
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(exchangeName))
                .with(routingKey));
    }

    private Message receiveMessage(String queueName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        Message message;
        do {
            message = rabbitTemplate.receive(queueName);
            if (message != null) {
                return message;
            }
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private void waitUntil(Check condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isMet()) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("Condition was not met within timeout");
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }

    @FunctionalInterface
    private interface Check {
        boolean isMet();
    }
}
