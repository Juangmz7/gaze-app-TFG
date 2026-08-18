package com.app.postcommandservice.comment.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CommentControllerTest {

    private static final UUID COMMENTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID POST_OWNER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private CommentJpaRepository commentJpaRepository;

    @Autowired
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        blockReadModelJpaRepository.deleteAll();
        commentJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
    }

    @Test
    void shouldCreateCommentAndReturnExpectedBodyWhenPostIsActive() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hello comment"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentId").exists())
                .andExpect(jsonPath("$.postId").value(postEntity.getId().toString()))
                .andExpect(jsonPath("$.userId").value(COMMENTER_ID.toString()))
                .andExpect(jsonPath("$.content").value("hello comment"))
                .andExpect(jsonPath("$.replyTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.commentStatus").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        var persistedComment = commentJpaRepository.findAll().getFirst();
        assertThat(persistedComment.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(persistedComment.getReplyTo()).isNull();
    }

    @Test
    void shouldCreateReplyCommentWhenParentCommentBelongsToSamePost() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var parentComment = seedComment(postEntity.getId(), UUID.randomUUID(), "parent", null);

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "reply comment",
                                "replyTo", parentComment.getId()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replyTo").value(parentComment.getId().toString()))
                .andExpect(jsonPath("$.content").value("reply comment"));

        assertThat(commentJpaRepository.count()).isEqualTo(2);
    }

    @Test
    void shouldReturnNotFoundWhenTargetPostDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/comments", UUID.randomUUID())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hello comment"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnBadRequestWhenTargetPostIsNotActive() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.DELETED);

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hello comment"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void shouldReturnNotFoundWhenReplyTargetDoesNotBelongToSamePost() throws Exception {
        var targetPost = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var otherPost = seedPost(UUID.randomUUID(), PostStatus.ACTIVE);
        var foreignComment = seedComment(otherPost.getId(), UUID.randomUUID(), "foreign", null);

        mockMvc.perform(post("/api/posts/{postId}/comments", targetPost.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "reply comment",
                                "replyTo", foreignComment.getId()
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnBadRequestWhenSenderIsBlockedByPostOwnerOrViceVersa() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(COMMENTER_ID, POST_OWNER_ID),
                Instant.now()
        ));

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hello comment"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    @Test
    void shouldReturnBadRequestWhenSenderIsBlockedByParentCommentAuthorOrViceVersa() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var parentAuthorId = UUID.randomUUID();
        var parentComment = seedComment(postEntity.getId(), parentAuthorId, "parent", null);
        blockReadModelJpaRepository.save(new BlockReadModelEntity(
                new BlockReadModelId(parentAuthorId, COMMENTER_ID),
                Instant.now()
        ));

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.getId())
                        .with(jwtFor(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "reply comment",
                                "replyTo", parentComment.getId()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BLOCKED"));
    }

    @Test
    void shouldSoftDeleteCommentAndReturnNoContentWhenOwnerDeletesAnActiveComment() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var comment = seedComment(postEntity.getId(), COMMENTER_ID, "hello comment", null);

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postEntity.getId(), comment.getId())
                        .with(jwtFor(COMMENTER_ID)))
                .andExpect(status().isNoContent());

        var deletedComment = commentJpaRepository.findById(comment.getId()).orElseThrow();
        assertThat(deletedComment.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(deletedComment.getDeletedAt()).isNotNull();
    }

    @Test
    void shouldReturnForbiddenWhenDeletingCommentOwnedByAnotherUser() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var comment = seedComment(postEntity.getId(), UUID.randomUUID(), "hello comment", null);

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postEntity.getId(), comment.getId())
                        .with(jwtFor(COMMENTER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void shouldReturnNotFoundWhenDeletingCommentThatDoesNotExist() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postEntity.getId(), UUID.randomUUID())
                        .with(jwtFor(COMMENTER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void shouldReturnBadRequestWhenDeletingCommentThatIsNotActive() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var comment = commentJpaRepository.save(CommentEntity.builder()
                .id(UUID.randomUUID())
                .postId(postEntity.getId())
                .userId(COMMENTER_ID)
                .content("hello comment")
                .replyTo(null)
                .status(CommentStatus.DELETED)
                .deletedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postEntity.getId(), comment.getId())
                        .with(jwtFor(COMMENTER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void shouldReturnBadRequestWhenDeletingCommentThatIsBanned() throws Exception {
        var postEntity = seedPost(POST_OWNER_ID, PostStatus.ACTIVE);
        var comment = commentJpaRepository.save(CommentEntity.builder()
                .id(UUID.randomUUID())
                .postId(postEntity.getId())
                .userId(COMMENTER_ID)
                .content("hello comment")
                .replyTo(null)
                .status(CommentStatus.BANNED)
                .deletedAt(Instant.now())
                .build());

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postEntity.getId(), comment.getId())
                        .with(jwtFor(COMMENTER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    private PostEntity seedPost(UUID ownerId, PostStatus status) {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .description("post")
                .taggedUsers(new java.util.ArrayList<>())
                .tags(new java.util.ArrayList<>())
                .status(status)
                .build());
    }

    private CommentEntity seedComment(UUID postId, UUID userId, String content, UUID replyTo) {
        return commentJpaRepository.save(CommentEntity.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .userId(userId)
                .content(content)
                .replyTo(replyTo)
                .status(CommentStatus.ACTIVE)
                .build());
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }
}
