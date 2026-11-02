package com.app.postcommandservice.post.infrastructure;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConfirmMediaUploadFlowIT {

    private static final UUID AUTHOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQProperties rabbitMQProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void purgeMediaQueue() {
        new RabbitAdmin(connectionFactory).purgeQueue(rabbitMQProperties.getQueue().getPostMedia(), true);
    }

    @AfterEach
    void tearDown() {
        postJpaRepository.deleteAll();
        new RabbitAdmin(connectionFactory).purgeQueue(rabbitMQProperties.getQueue().getPostMedia(), true);
    }

    @Test
    void shouldReturnAcceptedAndPublishPostMediaUploadedEventToTheDeclaredQueue() throws Exception {
        var mockMvc = mockMvc();
        var mediaId = UUID.randomUUID();
        var post = seedPost(PostStatus.PENDING, mediaId);

        mockMvc.perform(post("/api/posts/{postId}/media/confirm", post.getId())
                        .with(jwtFor(AUTHOR_ID)))
                .andExpect(status().isAccepted());

        var message = receiveMessage(rabbitMQProperties.getQueue().getPostMedia());
        assertThat(message).isNotNull();
        var eventPayload = objectMapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() { });
        assertThat(eventPayload.get("postId")).isEqualTo(post.getId().toString());
        var media = (java.util.List<Map<String, Object>>) eventPayload.get("media");
        assertThat(media).hasSize(1);
        assertThat(media.getFirst().get("id")).isEqualTo(mediaId.toString());
        assertThat(media.getFirst().get("url")).isEqualTo("https://cdn/blob.jpg");
        assertThat(media.getFirst().get("thumbnailUrl")).isEqualTo("https://cdn/blob.jpg");
        assertThat(media.getFirst().get("mediaType")).isEqualTo("IMAGE");
        assertThat(media.getFirst().get("order")).isEqualTo(1);
    }

    @Test
    void shouldReturnConflictAndPublishNothingWhenPostIsNotPending() throws Exception {
        var mockMvc = mockMvc();
        var post = seedPost(PostStatus.ACCEPTED, UUID.randomUUID());

        mockMvc.perform(post("/api/posts/{postId}/media/confirm", post.getId())
                        .with(jwtFor(AUTHOR_ID)))
                .andExpect(status().isConflict());

        assertThat(receiveMessage(rabbitMQProperties.getQueue().getPostMedia())).isNull();
    }

    private PostEntity seedPost(PostStatus status, UUID mediaId) {
        var postEntity = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("pending post")
                .status(status)
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

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
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

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }
}
