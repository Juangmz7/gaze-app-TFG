package com.app.postcommandservice.comment.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;
import com.app.postcommandservice.comment.infrastructure.repository.CommentJpaRepository;
import com.app.postcommandservice.comment.infrastructure.repository.CommentRequestIdempotencyJpaRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelEntity;
import com.app.postcommandservice.post.infrastructure.entity.BlockReadModelId;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CommentControllerTest {

    private static final UUID COMMENTER_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID POST_OWNER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private CommentJpaRepository commentJpaRepository;

    @Autowired
    private CommentRequestIdempotencyJpaRepository commentRequestIdempotencyJpaRepository;

    @Autowired
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        commentRequestIdempotencyJpaRepository.deleteAll();
        commentJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
    }

    @Test
    void shouldCreateCommentAndReturnExpectedBodyWhenPostIsActive() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var correlationId = UUID.randomUUID();

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                postEntity.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        correlationId,
                                                        "content",
                                                        "hello comment"
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentId").exists())
                .andExpect(
                        jsonPath("$.postId")
                                .value(postEntity.getId().toString())
                )
                .andExpect(
                        jsonPath("$.userId")
                                .value(COMMENTER_ID.toString())
                )
                .andExpect(
                        jsonPath("$.content")
                                .value("hello comment")
                )
                .andExpect(
                        jsonPath("$.replyTo")
                                .value(Matchers.nullValue())
                )
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.commentStatus").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        var persistedComment =
                commentJpaRepository.findAll().getFirst();

        assertThat(persistedComment.getStatus().name())
                .isEqualTo("ACTIVE");

        assertThat(persistedComment.getReplyTo())
                .isNull();

        assertThat(commentRequestIdempotencyJpaRepository.findById(correlationId))
                .isPresent()
                .get()
                .extracting(idempotency -> idempotency.getCommentId())
                .isEqualTo(persistedComment.getId());
    }

    @Test
    void shouldReturnBadRequestWhenCreateCommentOmitsCorrelationId() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        mockMvc.perform(
                        post("/api/posts/{postId}/comments", postEntity.getId())
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "content",
                                                        "hello comment"
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        assertThat(commentJpaRepository.count()).isEqualTo(0);
        assertThat(commentRequestIdempotencyJpaRepository.count()).isEqualTo(0);
    }

    @Test
    void shouldReturnSameCommentBodyWhenCalledTwiceWithTheSameCorrelationId() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "content", "hello comment"
        ));

        var firstResponse = mockMvc.perform(
                        post("/api/posts/{postId}/comments", postEntity.getId())
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                )
                .andExpect(status().isOk())
                .andReturn();

        var secondResponse = mockMvc.perform(
                        post("/api/posts/{postId}/comments", postEntity.getId())
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                )
                .andExpect(status().isOk())
                .andReturn();

        assertCommentBodiesEqual(readResponse(firstResponse), readResponse(secondResponse));
        assertThat(commentJpaRepository.count()).isEqualTo(1);
        assertThat(commentRequestIdempotencyJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnSameCommentBodyWhenConcurrentRequestsReuseTheSameCorrelationId() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var correlationId = UUID.randomUUID();
        var payload = objectMapper.writeValueAsString(Map.of(
                "correlationId", correlationId,
                "content", "concurrent comment"
        ));
        var readyLatch = new CountDownLatch(2);
        var startLatch = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executorService.submit(
                        concurrentCreateCommentRequest(postEntity.getId(), payload, readyLatch, startLatch)
                ));
            }

            readyLatch.await();
            startLatch.countDown();

            var firstBody = objectMapper.readTree(futures.get(0).get());
            var secondBody = objectMapper.readTree(futures.get(1).get());

            assertCommentBodiesEqual(firstBody, secondBody);
        }

        assertThat(commentJpaRepository.count()).isEqualTo(1);
        assertThat(commentRequestIdempotencyJpaRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldCreateReplyCommentWhenParentCommentBelongsToSamePost()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var parentComment =
                seedComment(
                        postEntity.getId(),
                        UUID.randomUUID(),
                        "parent",
                        null
                );

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                postEntity.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "reply comment",
                                                        "replyTo",
                                                        parentComment.getId()
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.replyTo")
                                .value(parentComment.getId().toString())
                )
                .andExpect(
                        jsonPath("$.content")
                                .value("reply comment")
                );

        assertThat(commentJpaRepository.count())
                .isEqualTo(2);
    }

    @Test
    void shouldReturnNotFoundWhenTargetPostDoesNotExist()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                UUID.randomUUID()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "hello comment"
                                                )
                                        )
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("NOT_FOUND")
                );
    }

    @Test
    void shouldReturnBadRequestWhenTargetPostIsNotActive()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.DELETED);

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                postEntity.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "hello comment"
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BAD_REQUEST")
                );
    }

    @Test
    void shouldReturnNotFoundWhenReplyTargetDoesNotBelongToSamePost()
            throws Exception {

        var targetPost =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var otherPost =
                seedPost(UUID.randomUUID(), PostStatus.ACTIVE);

        var foreignComment =
                seedComment(
                        otherPost.getId(),
                        UUID.randomUUID(),
                        "foreign",
                        null
                );

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                targetPost.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "reply comment",
                                                        "replyTo",
                                                        foreignComment.getId()
                                                )
                                        )
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("NOT_FOUND")
                );
    }

    @Test
    void shouldReturnBadRequestWhenSenderIsBlockedByPostOwnerOrViceVersa()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        blockReadModelJpaRepository.save(
                new BlockReadModelEntity(
                        new BlockReadModelId(
                                COMMENTER_ID,
                                POST_OWNER_ID
                        ),
                        Instant.now()
                )
        );

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                postEntity.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "hello comment"
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BLOCKED")
                );
    }

    @Test
    void shouldReturnBadRequestWhenSenderIsBlockedByParentCommentAuthorOrViceVersa()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var parentAuthorId =
                UUID.randomUUID();

        var parentComment =
                seedComment(
                        postEntity.getId(),
                        parentAuthorId,
                        "parent",
                        null
                );

        blockReadModelJpaRepository.save(
                new BlockReadModelEntity(
                        new BlockReadModelId(
                                parentAuthorId,
                                COMMENTER_ID
                        ),
                        Instant.now()
                )
        );

        mockMvc.perform(
                        post(
                                "/api/posts/{postId}/comments",
                                postEntity.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "correlationId",
                                                        UUID.randomUUID(),
                                                        "content",
                                                        "reply comment",
                                                        "replyTo",
                                                        parentComment.getId()
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BLOCKED")
                );
    }

    // =========================================================
    // DELETE COMMENT
    // =========================================================

    @Test
    void shouldSoftDeleteCommentAndReturnNoContentWhenOwnerDeletesAnActiveComment()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "hello comment",
                        null
                );

        mockMvc.perform(
                        delete(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                )
                .andExpect(status().isNoContent());

        var deletedComment =
                commentJpaRepository
                        .findById(comment.getId())
                        .orElseThrow();

        assertThat(deletedComment.getStatus())
                .isEqualTo(CommentStatus.DELETED);

        assertThat(deletedComment.getDeletedAt())
                .isNotNull();
    }

    @Test
    void shouldReturnForbiddenWhenDeletingCommentOwnedByAnotherUser()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        UUID.randomUUID(),
                        "hello comment",
                        null
                );

        mockMvc.perform(
                        delete(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("FORBIDDEN")
                );
    }

    @Test
    void shouldReturnNotFoundWhenDeletingCommentThatDoesNotExist()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        mockMvc.perform(
                        delete(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                UUID.randomUUID()
                        )
                                .with(jwtFor(COMMENTER_ID))
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("NOT_FOUND")
                );
    }

    @Test
    void shouldReturnBadRequestWhenDeletingCommentThatIsNotActive()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                commentJpaRepository.save(
                        CommentEntity.builder()
                                .id(UUID.randomUUID())
                                .postId(postEntity.getId())
                                .userId(COMMENTER_ID)
                                .content("hello comment")
                                .replyTo(null)
                                .status(CommentStatus.DELETED)
                                .deletedAt(Instant.now())
                                .build()
                );

        mockMvc.perform(
                        delete(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BAD_REQUEST")
                );
    }

    @Test
    void shouldReturnBadRequestWhenDeletingCommentThatIsBanned()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                commentJpaRepository.save(
                        CommentEntity.builder()
                                .id(UUID.randomUUID())
                                .postId(postEntity.getId())
                                .userId(COMMENTER_ID)
                                .content("hello comment")
                                .replyTo(null)
                                .status(CommentStatus.BANNED)
                                .deletedAt(Instant.now())
                                .build()
                );

        mockMvc.perform(
                        delete(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BAD_REQUEST")
                );
    }

    // =========================================================
    // UPDATE COMMENT
    // =========================================================

    @Test
    void shouldUpdateCommentAndReturnExpectedBodyWhenOwnerUsesPut()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "before",
                        null
                );

        var originalUpdatedAt =
                refreshedComment(comment.getId()).getUpdatedAt();

        Thread.sleep(5L);

        var result =
                mockMvc.perform(
                                put(
                                        "/api/posts/{postId}/comments/{commentId}",
                                        postEntity.getId(),
                                        comment.getId()
                                )
                                        .with(jwtFor(COMMENTER_ID))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "content",
                                                                "after"
                                                        )
                                                )
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.commentId")
                                        .value(comment.getId().toString())
                        )
                        .andExpect(
                                jsonPath("$.postId")
                                        .value(postEntity.getId().toString())
                        )
                        .andExpect(
                                jsonPath("$.userId")
                                        .value(COMMENTER_ID.toString())
                        )
                        .andExpect(
                                jsonPath("$.content")
                                        .value("after")
                        )
                        .andExpect(
                                jsonPath("$.replyTo")
                                        .value(Matchers.nullValue())
                        )
                        .andExpect(jsonPath("$.updatedAt").exists())
                        .andExpect(jsonPath("$.commentStatus").doesNotExist())
                        .andExpect(jsonPath("$.deletedAt").doesNotExist())
                        .andReturn();

        var persistedComment =
                refreshedComment(comment.getId());

        var responseBody =
                readResponse(result);

        assertThat(persistedComment.getContent())
                .isEqualTo("after");

        assertThat(persistedComment.getUpdatedAt())
                .isAfter(originalUpdatedAt);

        assertThat(responseBody.get("updatedAt").asText())
                .isEqualTo(
                        persistedComment
                                .getUpdatedAt()
                                .toString()
                );
    }

    @Test
    void shouldUpdateCommentWhenOwnerUsesPatch()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "before",
                        null
                );

        var originalUpdatedAt =
                refreshedComment(comment.getId()).getUpdatedAt();

        Thread.sleep(5L);

        var result =
                mockMvc.perform(
                                patch(
                                        "/api/posts/{postId}/comments/{commentId}",
                                        postEntity.getId(),
                                        comment.getId()
                                )
                                        .with(jwtFor(COMMENTER_ID))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "content",
                                                                "patched"
                                                        )
                                                )
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.content")
                                        .value("patched")
                        )
                        .andExpect(
                                jsonPath("$.updatedAt")
                                        .exists()
                        )
                        .andReturn();

        var persistedComment =
                refreshedComment(comment.getId());

        var responseBody =
                readResponse(result);

        assertThat(persistedComment.getContent())
                .isEqualTo("patched");

        assertThat(persistedComment.getUpdatedAt())
                .isAfter(originalUpdatedAt);

        assertThat(responseBody.get("updatedAt").asText())
                .isEqualTo(
                        persistedComment
                                .getUpdatedAt()
                                .toString()
                );
    }

    @Test
    void shouldReturnOkWithoutUpdatingTimestampsWhenContentIsUnchanged()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "same",
                        null
                );

        var originalPersistedComment =
                refreshedComment(comment.getId());

        var result =
                mockMvc.perform(
                                put(
                                        "/api/posts/{postId}/comments/{commentId}",
                                        postEntity.getId(),
                                        comment.getId()
                                )
                                        .with(jwtFor(COMMENTER_ID))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "content",
                                                                "same"
                                                        )
                                                )
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.content")
                                        .value("same")
                        )
                        .andExpect(
                                jsonPath("$.updatedAt")
                                        .value(
                                                originalPersistedComment
                                                        .getUpdatedAt()
                                                        .toString()
                                        )
                        )
                        .andReturn();

        var persistedComment =
                refreshedComment(comment.getId());

        var responseBody =
                readResponse(result);

        assertThat(persistedComment.getContent())
                .isEqualTo("same");

        assertThat(
                persistedComment
                        .getUpdatedAt()
                        .truncatedTo(ChronoUnit.MICROS)
        ).isEqualTo(
                originalPersistedComment
                        .getUpdatedAt()
                        .truncatedTo(ChronoUnit.MICROS)
        );

        assertThat(responseBody.get("updatedAt").asText())
                .isEqualTo(
                        persistedComment
                                .getUpdatedAt()
                                .toString()
                );
    }

    @Test
    void shouldReturnNotFoundWhenCommentDoesNotBelongToPathPost()
            throws Exception {

        var targetPost =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var otherPost =
                seedPost(UUID.randomUUID(), PostStatus.ACTIVE);

        var foreignComment =
                seedComment(
                        otherPost.getId(),
                        COMMENTER_ID,
                        "before",
                        null
                );

        mockMvc.perform(
                        put(
                                "/api/posts/{postId}/comments/{commentId}",
                                targetPost.getId(),
                                foreignComment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "content",
                                                        "after"
                                                )
                                        )
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("NOT_FOUND")
                );
    }

    @Test
    void shouldReturnForbiddenWhenRequesterIsNotOriginalAuthor()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        UUID.randomUUID(),
                        "before",
                        null
                );

        mockMvc.perform(
                        put(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "content",
                                                        "after"
                                                )
                                        )
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("FORBIDDEN")
                );
    }

    @Test
    void shouldReturnBadRequestWhenCommentIsDeleted()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "before",
                        null
                );

        comment.setStatus(CommentStatus.DELETED);
        comment.setDeletedAt(Instant.now());

        commentJpaRepository.save(comment);

        mockMvc.perform(
                        put(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "content",
                                                        "after"
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BAD_REQUEST")
                );
    }

    @Test
    void shouldReturnBadRequestWhenCommentIsBanned()
            throws Exception {

        var postEntity =
                seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        var comment =
                seedComment(
                        postEntity.getId(),
                        COMMENTER_ID,
                        "before",
                        null
                );

        comment.setStatus(CommentStatus.BANNED);
        comment.setDeletedAt(Instant.now());

        commentJpaRepository.save(comment);

        mockMvc.perform(
                        put(
                                "/api/posts/{postId}/comments/{commentId}",
                                postEntity.getId(),
                                comment.getId()
                        )
                                .with(jwtFor(COMMENTER_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "content",
                                                        "after"
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("BAD_REQUEST")
                );
    }

    private PostEntity seedPost(
            UUID ownerId,
            PostStatus status
    ) {
        return postJpaRepository.save(
                PostEntity.builder()
                        .id(UUID.randomUUID())
                        .userId(ownerId)
                        .description("post")
                        .taggedUsers(new java.util.ArrayList<>())
                        .tags(new java.util.ArrayList<>())
                        .status(status)
                        .build()
        );
    }

    private CommentEntity seedComment(
            UUID postId,
            UUID userId,
            String content,
            UUID replyTo
    ) {
        return commentJpaRepository.save(
                CommentEntity.builder()
                        .id(UUID.randomUUID())
                        .postId(postId)
                        .userId(userId)
                        .content(content)
                        .replyTo(replyTo)
                        .status(CommentStatus.ACTIVE)
                        .build()
        );
    }

    private CommentEntity refreshedComment(UUID commentId) {
        return commentJpaRepository
                .findById(commentId)
                .orElseThrow();
    }

    private Callable<String> concurrentCreateCommentRequest(
            UUID postId,
            String payload,
            CountDownLatch readyLatch,
            CountDownLatch startLatch
    ) {
        return () -> {
            readyLatch.countDown();
            startLatch.await();

            return mockMvc.perform(
                            post("/api/posts/{postId}/comments", postId)
                                    .with(jwtFor(COMMENTER_ID))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                    )
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        };
    }

    private JsonNode readResponse(
            org.springframework.test.web.servlet.MvcResult result
    ) throws Exception {

        return objectMapper.readTree(
                result.getResponse().getContentAsString()
        );
    }

    private void assertCommentBodiesEqual(JsonNode firstBody, JsonNode secondBody) {
        assertThat(firstBody.get("commentId").asText()).isEqualTo(secondBody.get("commentId").asText());
        assertThat(firstBody.get("postId").asText()).isEqualTo(secondBody.get("postId").asText());
        assertThat(firstBody.get("userId").asText()).isEqualTo(secondBody.get("userId").asText());
        assertThat(firstBody.get("content").asText()).isEqualTo(secondBody.get("content").asText());
        assertThat(firstBody.get("replyTo").isNull()).isEqualTo(secondBody.get("replyTo").isNull());
        assertThat(firstBody.get("createdAt").asText()).isEqualTo(secondBody.get("createdAt").asText());
        assertThat(firstBody.get("updatedAt").asText()).isEqualTo(secondBody.get("updatedAt").asText());
        assertThat(firstBody.get("commentStatus")).isNull();
        assertThat(firstBody.get("deletedAt")).isNull();
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(
            UUID userId
    ) {
        return jwt().jwt(
                jwt -> jwt.subject(userId.toString())
        );
    }
}
