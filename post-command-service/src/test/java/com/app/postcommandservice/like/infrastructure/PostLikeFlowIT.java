package com.app.postcommandservice.like.infrastructure;

import java.time.Instant;
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
import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.like.infrastructure.repository.PostLikeJpaRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostLikeFlowIT {

    private static final UUID LIKER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostLikeJpaRepository postLikeJpaRepository;

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
        postLikeJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldReturnAcceptedAndPublishValidatePostLikeCommand() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        var queueName = "test.post.like.command." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate());
        var postId = seedActivePost(UUID.randomUUID()).getId();

        mockMvc.perform(post("/api/posts/{postId}/like", postId)
                        .with(jwtFor(LIKER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(likeRequest("home_feed", 5)))
                .andExpect(status().isAccepted());

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var commandPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(commandPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(commandPayload.get("userId")).isEqualTo(LIKER_ID.toString());
        assertThat(commandPayload.get("source")).isEqualTo("HOME_FEED");
        assertThat(commandPayload.get("feedPosition")).isEqualTo(5);
        waitUntil(() -> outboxEventRepository.findAll().stream()
                .anyMatch(outboxEvent -> ValidatePostLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())));
        var commandOutboxEvent = outboxEventRepository.findAll().stream()
                .filter(outboxEvent -> ValidatePostLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(commandOutboxEvent.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(commandOutboxEvent.getCorrelationId()).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldProcessCommandPersistLikeAndPublishCreatedEvent() throws Exception {
        var post = seedActivePost(UUID.randomUUID());
        var queueName = "test.post.like.created." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getLike().getCreated());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                command(post.getId(), LIKER_ID)
        );

        waitUntil(() -> postLikeJpaRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventsRepository.count()).isEqualTo(1);

        var eventMessage = receiveMessage(queueName);
        assertThat(eventMessage).isNotNull();
        var eventPayload = objectMapper.readValue(eventMessage.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(eventPayload.get("userId")).isEqualTo(LIKER_ID.toString());
        assertThat(eventPayload.get("source")).isEqualTo("SEARCH");
        assertThat(eventPayload.get("feedPosition")).isEqualTo(7);
        assertThat(eventPayload.get("createdAt")).isNotNull();
        var persistedLike = postLikeJpaRepository.findAll().getFirst();
        assertThat(persistedLike.getSource()).isEqualTo(PostLikeSource.SEARCH);
        assertThat(persistedLike.getFeedPosition()).isEqualTo(7);

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldDiscardDuplicateLikeCommandWithoutPublishingAnotherEvent() throws Exception {
        var post = seedActivePost(UUID.randomUUID());
        var firstCommand = command(post.getId(), LIKER_ID);
        var secondCommand = command(post.getId(), LIKER_ID);

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                firstCommand
        );
        waitUntil(() -> postLikeJpaRepository.count() == 1);

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                secondCommand
        );
        waitUntil(() -> processedEventsRepository.count() == 2);

        assertThat(postLikeJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldDiscardLikeCommandWhenPostDoesNotExistOrIsInactive() throws Exception {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                command(UUID.randomUUID(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);

        processedEventsRepository.deleteAll();
        var inactivePost = postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .description("inactive")
                .status(PostStatus.DELETED)
                .build());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                command(inactivePost.getId(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldDiscardLikeCommandWhenUsersAreBlocked() throws Exception {
        var ownerId = UUID.randomUUID();
        var post = seedActivePost(ownerId);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(LIKER_ID, ownerId),
                Instant.now()
        ));

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getLike().getValidate(),
                command(post.getId(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    private PostEntity seedActivePost(UUID ownerId) {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .description("active")
                .status(PostStatus.ACTIVE)
                .build());
    }

    private ValidatePostLikeCommand command(UUID postId, UUID userId) {
        return new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                postId,
                userId,
                PostLikeSource.SEARCH,
                7
        );
    }

    private String likeRequest(String source, int feedPosition) {
        return String.format("""
                {
                  "context": {
                    "source": "%s",
                    "feedPosition": %d
                  }
                }
                """, source, feedPosition);
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
