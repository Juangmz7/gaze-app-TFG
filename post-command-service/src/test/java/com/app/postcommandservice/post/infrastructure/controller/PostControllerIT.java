package com.app.postcommandservice.post.infrastructure.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.UserReadModelEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.UserReadModelJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostControllerIT {

    private static final UUID CREATOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostRequestIdempotencyJpaRepository postRequestIdempotencyJpaRepository;

    @Autowired
    private UserReadModelJpaRepository userReadModelJpaRepository;

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

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        userReadModelJpaRepository.deleteAll();
        postRequestIdempotencyJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldCreatePostAndPublishEventWhenRequestIsValidWithNoTaggedUsers() throws Exception {
        String queueName = "test.post.created." + UUID.randomUUID();
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        Queue queue = new Queue(queueName, false, true, true);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(rabbitMQProperties.getExchange().getPost().getEvents()))
                .with(rabbitMQProperties.getRk().getPost().getCreated()));

        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));

        var mvcResult = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").exists())
                .andExpect(jsonPath("$.userId").value(CREATOR_ID.toString()))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.taggedUsers").isArray())
                .andExpect(jsonPath("$.postTags[0]").value("java"))
                .andReturn();

        assertThat(postJpaRepository.findAll()).hasSize(1);
        assertThat(postJpaRepository.findAll().getFirst().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(postRequestIdempotencyJpaRepository.findById(correlationId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        Message message = receiveMessage(queueName);
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("userId")).isEqualTo(CREATOR_ID.toString());
        assertThat(eventPayload.get("description")).isEqualTo("");

        rabbitAdmin.deleteQueue(queueName);
    }

    @Test
    void shouldCreatePostSuccessfullyWhenTaggedUsersExistAndAreNotBlocked() throws Exception {
        seedUser(UUID.randomUUID(), "alice");
        seedUser(UUID.randomUUID(), "bob");

        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "hello",
                "taggedUsers", new LinkedHashSet<>(Set.of("alice", "bob")),
                "postTags", Set.of("spring")
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("hello"))
                .andExpect(jsonPath("$.taggedUsers").isArray());

        assertThat(postJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldReturnSamePostBodyWhenCalledTwiceWithTheSameCorrelationId() throws Exception {
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "idempotent",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));

        var firstResponse = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var secondResponse = mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var firstBody = objectMapper.readValue(firstResponse, new TypeReference<Map<String, Object>>() { });
        var secondBody = objectMapper.readValue(secondResponse, new TypeReference<Map<String, Object>>() { });

        assertThat(secondBody).isEqualTo(firstBody);
        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnSamePostBodyWhenConcurrentRequestsReuseTheSameCorrelationId() throws Exception {
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "description", "concurrent",
                "taggedUsers", Set.of(),
                "postTags", Set.of("java")
        ));
        var readyLatch = new CountDownLatch(2);
        var startLatch = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executorService.submit(concurrentCreatePostRequest(payload, readyLatch, startLatch)));
            }

            readyLatch.await();
            startLatch.countDown();

            var firstBody = objectMapper.readValue(futures.get(0).get(), new TypeReference<Map<String, Object>>() { });
            var secondBody = objectMapper.readValue(futures.get(1).get(), new TypeReference<Map<String, Object>>() { });

            assertThat(firstBody).isEqualTo(secondBody);
        }

        assertThat(postJpaRepository.count()).isEqualTo(1);
        assertThat(postRequestIdempotencyJpaRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundWhenTaggedUsernameDoesNotExist() throws Exception {
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", UUID.randomUUID(),
                "description", "hello",
                "taggedUsers", Set.of("missing"),
                "postTags", Set.of()
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnForbiddenWhenTaggedUserIsBlocked() throws Exception {
        UUID aliceId = UUID.randomUUID();
        seedUser(aliceId, "alice");
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(CREATOR_ID, aliceId),
                Instant.now()
        ));

        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", UUID.randomUUID(),
                "description", "hello",
                "taggedUsers", Set.of("alice"),
                "postTags", Set.of()
        ));

        mockMvc.perform(post("/api/posts")
                        .with(jwtFor(CREATOR_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    private void seedUser(UUID userId, String username) {
        var now = Instant.now();
        userReadModelJpaRepository.save(new UserReadModelEntity(userId, username, now, now));
    }

    private Callable<String> concurrentCreatePostRequest(
            String payload,
            CountDownLatch readyLatch,
            CountDownLatch startLatch) {
        return () -> {
            readyLatch.countDown();
            startLatch.await();

            return mockMvc.perform(post("/api/posts")
                            .with(jwtFor(CREATOR_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        };
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
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
}
