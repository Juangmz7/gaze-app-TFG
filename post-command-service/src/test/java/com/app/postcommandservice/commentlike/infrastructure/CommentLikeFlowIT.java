package com.app.postcommandservice.commentlike.infrastructure;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;
import com.app.postcommandservice.comment.infrastructure.repository.CommentJpaRepository;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.domain.model.CommentLikeSource;
import com.app.postcommandservice.commentlike.infrastructure.repository.CommentLikeJpaRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
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
class CommentLikeFlowIT {

    private static final UUID LIKER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private CommentJpaRepository commentJpaRepository;

    @Autowired
    private CommentLikeJpaRepository commentLikeJpaRepository;

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
        commentLikeJpaRepository.deleteAll();
        commentJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldReturnAcceptedAndPublishValidateCommentLikeCommand() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        var queueName = "test.comment.like.command." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getValidate());
        var post = seedActivePost(UUID.randomUUID());
        var comment = seedActiveComment(post.getId(), UUID.randomUUID());

        mockMvc.perform(post("/api/posts/{postId}/comments/{commentId}/like", post.getId(), comment.getId())
                        .with(jwt().jwt(token -> token.subject(LIKER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(likeRequest("home_feed", 5)))
                .andExpect(status().isAccepted());

        var message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var commandPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(commandPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(commandPayload.get("commentId")).isEqualTo(comment.getId().toString());
        assertThat(commandPayload.get("userId")).isEqualTo(LIKER_ID.toString());
        assertThat(commandPayload.get("source")).isEqualTo("HOME_FEED");
        assertThat(commandPayload.get("feedPosition")).isEqualTo(5);
        waitUntil(() -> outboxEventRepository.findAll().stream()
                .anyMatch(outboxEvent ->
                        ValidateCommentLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType())));
        var commandOutboxEvent = outboxEventRepository.findAll().stream()
                .filter(outboxEvent ->
                        ValidateCommentLikeCommand.class.getSimpleName().equals(outboxEvent.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(commandOutboxEvent.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(commandOutboxEvent.getCorrelationId()).isNotNull();

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldProcessCommandPersistCommentLikeAndPublishCreatedEvent() throws Exception {
        var ownerId = UUID.randomUUID();
        var post = seedActivePost(ownerId);
        var comment = seedActiveComment(post.getId(), ownerId);
        var queueName = "test.comment.like.created." + UUID.randomUUID();
        var rabbitAdmin = new RabbitAdmin(connectionFactory);
        bindQueue(rabbitAdmin, queueName, rabbitMQProperties.getExchange().getPost().getEvents(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getCreated());

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getValidate(),
                command(post.getId(), comment.getId(), LIKER_ID)
        );

        waitUntil(() -> commentLikeJpaRepository.count() == 1);

        assertThat(commentLikeJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventsRepository.count()).isEqualTo(1);

        var eventMessage = receiveMessage(queueName);
        assertThat(eventMessage).isNotNull();
        var eventPayload = objectMapper.readValue(eventMessage.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(post.getId().toString());
        assertThat(eventPayload.get("commentId")).isEqualTo(comment.getId().toString());
        assertThat(eventPayload.get("userId")).isEqualTo(LIKER_ID.toString());
        assertThat(eventPayload.get("source")).isEqualTo("SEARCH");
        assertThat(eventPayload.get("feedPosition")).isEqualTo(7);
        assertThat(eventPayload.get("createdAt")).isNotNull();
        var persistedLike = commentLikeJpaRepository.findAll().getFirst();
        assertThat(persistedLike.getSource()).isEqualTo(CommentLikeSource.SEARCH);
        assertThat(persistedLike.getFeedPosition()).isEqualTo(7);

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldDiscardCommentLikeCommandWhenUsersAreBlocked() {
        var ownerId = UUID.randomUUID();
        var post = seedActivePost(ownerId);
        var comment = seedActiveComment(post.getId(), ownerId);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(LIKER_ID, ownerId),
                Instant.now()
        ));

        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getExchange().getPost().getCommands(),
                rabbitMQProperties.getRk().getPost().getComment().getLike().getValidate(),
                command(post.getId(), comment.getId(), LIKER_ID)
        );
        waitUntil(() -> processedEventsRepository.count() == 1);

        assertThat(commentLikeJpaRepository.count()).isEqualTo(0);
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

    private CommentEntity seedActiveComment(UUID postId, UUID ownerId) {
        return commentJpaRepository.save(CommentEntity.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .userId(ownerId)
                .content("hello")
                .status(CommentStatus.ACTIVE)
                .build());
    }

    private ValidateCommentLikeCommand command(UUID postId, UUID commentId, UUID userId) {
        return new ValidateCommentLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                postId,
                commentId,
                userId,
                CommentLikeSource.SEARCH,
                7
        );
    }

    private String likeRequest(String source, int feedPosition) {
        return """
                {
                  "context": {
                    "source": "%s",
                    "feedPosition": %d
                  }
                }
                """.formatted(source, feedPosition);
    }

    private void bindQueue(RabbitAdmin rabbitAdmin, String queueName, String exchangeName, String routingKey) {
        var queue = new Queue(queueName, true, false, true);
        var exchange = new TopicExchange(exchangeName);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
    }

    private Message receiveMessage(String queueName) {
        return rabbitTemplate.receive(queueName, 10_000);
    }

    private void waitUntil(Check check) {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (check.matches()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for async processing", exception);
            }
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }
}
