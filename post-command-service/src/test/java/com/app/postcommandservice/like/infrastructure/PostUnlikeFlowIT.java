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
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeEntity;
import com.app.postcommandservice.like.infrastructure.entity.PostLikeId;
import com.app.postcommandservice.like.infrastructure.repository.PostLikeJpaRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostUnlikeFlowIT {

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
    void shouldReturnAcceptedAndPublishValidatePostUnlikeCommand() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        var queueName = "test.post.unlike.command." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getUnlike().getValidate());
        var postId = seedActivePost(UUID.randomUUID()).getId();

        mockMvc.perform(delete("/api/posts/{postId}/like", postId)
                        .with(jwtFor(LIKER_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var commandPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(commandPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(commandPayload.get("userId")).isEqualTo(LIKER_ID.toString());

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldProcessCommandDeleteLikeAndPublishDeletedEvent() throws Exception {
        var post = seedActivePost(UUID.randomUUID());
        seedLike(post.getId(), LIKER_ID);
        var queueName = "test.post.like.deleted." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getLike().getDeleted());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getUnlike().getValidate(),
                command(post.getId(), LIKER_ID)
        );

        waitUntil(() -> postLikeJpaRepository.count() == 0);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventsRepository.count()).isEqualTo(1);

        var eventMessage = receiveMessage(queueName);
        assertThat(eventMessage).isNotNull();
        var eventPayload = objectMapper.readValue(eventMessage.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(eventPayload.get("userId")).isEqualTo(LIKER_ID.toString());
        assertThat(eventPayload.get("occurredAt")).isNotNull();
        assertThat(eventPayload).doesNotContainKey("createdAt");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldDiscardUnlikeCommandForNonExistentLikeWithoutPublishingEvent() throws Exception {
        var post = seedActivePost(UUID.randomUUID());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getUnlike().getValidate(),
                command(post.getId(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldDeleteLikeEvenWhenUsersAreBlocked() throws Exception {
        var ownerId = UUID.randomUUID();
        var post = seedActivePost(ownerId);
        seedLike(post.getId(), LIKER_ID);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(LIKER_ID, ownerId),
                Instant.now()
        ));

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getUnlike().getValidate(),
                command(post.getId(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(postLikeJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    private PostEntity seedActivePost(UUID ownerId) {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .description("active")
                .status(PostStatus.ACTIVE)
                .build());
    }

    private void seedLike(UUID postId, UUID userId) {
        postLikeJpaRepository.save(PostLikeEntity.builder()
                .id(new PostLikeId(postId, userId))
                .createdAt(Instant.now())
                .build());
    }

    private ValidatePostUnlikeCommand command(UUID postId, UUID userId) {
        return new ValidatePostUnlikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                postId,
                userId
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
