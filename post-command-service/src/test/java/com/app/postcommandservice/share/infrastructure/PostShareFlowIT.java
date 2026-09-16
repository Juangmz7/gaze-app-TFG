package com.app.postcommandservice.share.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.share.infrastructure.repository.PostShareJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostShareFlowIT {

    private static final UUID SHARER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostShareJpaRepository postShareJpaRepository;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        postShareJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldReturnAcceptedAndPublishCreatePostShareCommand() throws Exception {
        var postId = seedActivePost(UUID.randomUUID()).getId();
        var queueName = "test.post.share.command." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getShare().getCreate().getValidate());

        mockMvc.perform(post("/api/posts/{postId}/share", postId)
                        .with(jwtFor(SHARER_ID)))
                .andExpect(status().isAccepted());

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var commandPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(commandPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(commandPayload.get("userId")).isEqualTo(SHARER_ID.toString());
        waitUntil(() -> outboxEventRepository.findAll().stream()
                .anyMatch(outboxEvent -> CreatePostShareCommand.class.getSimpleName().equals(outboxEvent.getEventType())));
        var commandOutboxEvent = outboxEventRepository.findAll().stream()
                .filter(outboxEvent -> CreatePostShareCommand.class.getSimpleName().equals(outboxEvent.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(commandOutboxEvent.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(commandOutboxEvent.getCorrelationId()).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldProcessCommandPersistShareAndPublishPostSharedEvent() throws Exception {
        var ownerId = UUID.randomUUID();
        var postId = seedActivePost(ownerId).getId();
        var queueName = "test.post.share.created." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getShare().getCreated());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getShare().getCreate().getValidate(),
                command(postId, SHARER_ID)
        );

        waitUntil(() -> postShareJpaRepository.count() == 1);

        assertThat(postShareJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventsRepository.count()).isEqualTo(1);
        var persistedShare = postShareJpaRepository.findAll().getFirst();
        assertThat(persistedShare.getId().getPostId()).isEqualTo(postId);
        assertThat(persistedShare.getId().getUserId()).isEqualTo(SHARER_ID);
        assertThat(persistedShare.getCreatedAt()).isNotNull();

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(SHARER_ID.toString());
        assertThat(eventPayload.get("createdAt")).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldDiscardDuplicateShareCommandWithoutCreatingDuplicateRowOrEvent() throws Exception {
        var postId = seedActivePost(UUID.randomUUID()).getId();
        var firstCommand = command(postId, SHARER_ID);
        var secondCommand = command(postId, SHARER_ID);

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getShare().getCreate().getValidate(),
                firstCommand
        );
        waitUntil(() -> postShareJpaRepository.count() == 1);

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getShare().getCreate().getValidate(),
                secondCommand
        );
        waitUntil(() -> processedEventsRepository.count() == 2);

        assertThat(postShareJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldDeleteOwnShareAndPublishPostShareDeletedEvent() throws Exception {
        var ownerId = UUID.randomUUID();
        var postId = seedActivePost(ownerId).getId();
        postShareJpaRepository.save(new com.app.postcommandservice.share.infrastructure.entity.PostShareEntity(
                new com.app.postcommandservice.share.infrastructure.entity.PostShareId(postId, SHARER_ID),
                Instant.now()
        ));
        var queueName = "test.post.share.deleted." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getShare().getDeleted());

        mockMvc.perform(delete("/api/posts/{postId}/share", postId)
                        .with(jwtFor(SHARER_ID)))
                .andExpect(status().isNoContent());

        waitUntil(() -> postShareJpaRepository.count() == 0 && outboxEventRepository.count() == 1);

        assertThat(postShareJpaRepository.count()).isEqualTo(0);
        var outboxEvent = outboxEventRepository.findAll().getFirst();
        assertThat(outboxEvent.getEventType()).isEqualTo("PostShareDeletedEvent");

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(postId.toString());
        assertThat(eventPayload.get("userId")).isEqualTo(SHARER_ID.toString());
        assertThat(eventPayload.get("id")).isNotNull();
        assertThat(eventPayload.get("correlationId")).isNotNull();
        assertThat(eventPayload.get("occurredAt")).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldReturnNoContentWithoutDeletingAnotherUsersShareOrPublishingEvent() throws Exception {
        var ownerId = UUID.randomUUID();
        var postId = seedActivePost(ownerId).getId();
        var otherUserId = UUID.randomUUID();
        postShareJpaRepository.save(new com.app.postcommandservice.share.infrastructure.entity.PostShareEntity(
                new com.app.postcommandservice.share.infrastructure.entity.PostShareId(postId, otherUserId),
                Instant.now()
        ));

        mockMvc.perform(delete("/api/posts/{postId}/share", postId)
                        .with(jwtFor(SHARER_ID)))
                .andExpect(status().isNoContent());

        Thread.sleep(500L);

        assertThat(postShareJpaRepository.findAll())
                .extracting(share -> share.getId().getPostId(), share -> share.getId().getUserId())
                .containsExactly(tuple(postId, otherUserId));
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldReturnNoContentWhenShareDoesNotExistWithoutPublishingEvent() throws Exception {
        var ownerId = UUID.randomUUID();
        var postId = seedActivePost(ownerId).getId();

        mockMvc.perform(delete("/api/posts/{postId}/share", postId)
                        .with(jwtFor(SHARER_ID)))
                .andExpect(status().isNoContent());

        Thread.sleep(500L);

        assertThat(postShareJpaRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(0);
    }

    private PostEntity seedActivePost(UUID ownerId) {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .postType(PostType.BASIC)
                .description("active")
                .status(PostStatus.ACTIVE)
                .build());
    }

    private CreatePostShareCommand command(UUID postId, UUID userId) {
        return new CreatePostShareCommand(
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
