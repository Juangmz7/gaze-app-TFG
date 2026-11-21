package com.app.postcommandservice.report.infrastructure.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.comment.domain.model.valueobj.CommentStatus;
import com.app.postcommandservice.comment.infrastructure.entity.CommentEntity;
import com.app.postcommandservice.comment.infrastructure.repository.CommentJpaRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.entity.PostMediaEntity;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.report.domain.model.valueobj.ReportReason;
import com.app.postcommandservice.report.domain.model.valueobj.ReportStatus;
import com.app.postcommandservice.report.infrastructure.entity.PostReportEntity;
import com.app.postcommandservice.report.infrastructure.entity.PostReportSnapshotPayload;
import com.app.postcommandservice.report.infrastructure.repository.CommentReportJpaRepository;
import com.app.postcommandservice.report.infrastructure.repository.PostReportJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReportControllerIT {

    private static final UUID AUTHOR_ID = UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111");
    private static final UUID REPORTER_ID = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222");
    private static final UUID COMMENTER_ID = UUID.fromString("cccccccc-3333-4333-8333-333333333333");
    private static final UUID ADMIN_ID = UUID.fromString("dddddddd-4444-4444-8444-444444444444");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private CommentJpaRepository commentJpaRepository;

    @Autowired
    private PostReportJpaRepository postReportJpaRepository;

    @Autowired
    private CommentReportJpaRepository commentReportJpaRepository;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        postReportJpaRepository.deleteAll();
        commentReportJpaRepository.deleteAll();
        commentJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
    }

    @Test
    void shouldCreatePostReportWithSnapshotAndReturnItForTheDuplicate() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        var body = """
                { "reason": "SPAM", "details": "  buy followers here  " }
                """;

        var first = mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").exists())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        var second = mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "VIOLENCE", "details": null }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(readMap(second)).isEqualTo(readMap(first));
        var rows = postReportJpaRepository.findAll();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(ReportStatus.OPEN);
            assertThat(row.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(row.getDetails()).isEqualTo("buy followers here");
            assertThat(row.getReporterUserId()).isEqualTo(REPORTER_ID);
            assertThat(row.getSnapshot().description()).isEqualTo("original caption");
            assertThat(row.getSnapshot().mediaUrls()).containsExactly("https://acc.blob/media/one");
            assertThat(row.getSnapshot().authorUserId()).isEqualTo(AUTHOR_ID);
        });
    }

    @Test
    void shouldAllowANewReportOnceThePreviousOneIsNoLongerOpen() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        reportPost(post.getId(), REPORTER_ID, "SPAM").andExpect(status().isCreated());
        var resolved = postReportJpaRepository.findAll().getFirst();
        resolved.setStatus(ReportStatus.RESOLVED);
        postReportJpaRepository.save(resolved);

        reportPost(post.getId(), REPORTER_ID, "SPAM").andExpect(status().isCreated());

        assertThat(postReportJpaRepository.findAll()).hasSize(2);
    }

    @Test
    void shouldRejectOtherWithoutDetails() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);

        mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "OTHER", "details": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        assertThat(postReportJpaRepository.count()).isZero();
    }

    @Test
    void shouldRejectDetailsOverOneThousandCharacters() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        var payload = objectMapper.writeValueAsString(Map.of("reason", "OTHER", "details", "x".repeat(1001)));

        mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAnswerBadRequestForMalformedJsonEnumOrUuid() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);

        mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"BORING\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/posts/{postId}/reports", post.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"details\": \"no reason\" }"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/posts/{postId}/reports", "not-a-uuid")
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"SPAM\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAnswerPostNotFoundForMissingOrDeletedPost() throws Exception {
        var deleted = savePost(PostStatus.DELETED);

        reportPost(UUID.randomUUID(), REPORTER_ID, "SPAM")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("post_not_found"));
        reportPost(deleted.getId(), REPORTER_ID, "SPAM")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("post_not_found"));
    }

    @Test
    void shouldRejectReportingOwnPost() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);

        reportPost(post.getId(), AUTHOR_ID, "SPAM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("cannot_report_own_content"));
    }

    @Test
    void shouldCreateCommentReportWithSnapshot() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        var comment = saveComment(post.getId(), CommentStatus.ACTIVE);

        mockMvc.perform(post("/api/posts/{postId}/comments/{commentId}/reports", post.getId(), comment.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"HARASSMENT\", \"details\": null }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertThat(commentReportJpaRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getCommentId()).isEqualTo(comment.getId());
            assertThat(row.getPostId()).isEqualTo(post.getId());
            assertThat(row.getDetails()).isNull();
            assertThat(row.getSnapshot().content()).isEqualTo("you are awful");
            assertThat(row.getSnapshot().authorUserId()).isEqualTo(COMMENTER_ID);
            assertThat(row.getSnapshot().postId()).isEqualTo(post.getId());
        });
    }

    @Test
    void shouldAnswerCommentNotFoundAndRejectOwnComment() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        var deletedComment = saveComment(post.getId(), CommentStatus.DELETED);
        var activeComment = saveComment(post.getId(), CommentStatus.ACTIVE);

        mockMvc.perform(post("/api/posts/{postId}/comments/{commentId}/reports", post.getId(), deletedComment.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"SPAM\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("comment_not_found"));
        mockMvc.perform(post("/api/posts/{postId}/comments/{commentId}/reports", post.getId(), activeComment.getId())
                        .with(as(COMMENTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"SPAM\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("cannot_report_own_content"));
    }

    @Test
    void shouldForbidAdminListsForUserToken() throws Exception {
        mockMvc.perform(get("/api/posts/reports").with(as(REPORTER_ID, "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mockMvc.perform(get("/api/posts/comments/reports").with(as(REPORTER_ID, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRequireAuthenticationForAdminLists() throws Exception {
        mockMvc.perform(get("/api/posts/reports"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldListPostReportsNewestFirstWithKeysetPaginationForAdmin() throws Exception {
        var postId = UUID.randomUUID();
        var base = Instant.parse("2026-11-10T12:00:00Z");
        var oldest = savePostReport(postId, base, ReportStatus.OPEN);
        var middle = savePostReport(postId, base.plusSeconds(60), ReportStatus.OPEN);
        var newest = savePostReport(postId, base.plusSeconds(120), ReportStatus.OPEN);
        savePostReport(postId, base.plusSeconds(180), ReportStatus.RESOLVED);

        var firstPage = mockMvc.perform(get("/api/posts/reports").param("limit", "2").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].reportId").value(newest.getId().toString()))
                .andExpect(jsonPath("$.items[1].reportId").value(middle.getId().toString()))
                .andExpect(jsonPath("$.items[0].target.postId").value(postId.toString()))
                .andExpect(jsonPath("$.items[0].target.commentId").doesNotExist())
                .andExpect(jsonPath("$.items[0].reporterUserId").exists())
                .andExpect(jsonPath("$.items[0].reason").value("NUDITY"))
                .andExpect(jsonPath("$.items[0].details").value((Object) null))
                .andExpect(jsonPath("$.items[0].snapshot.description").value("snap"))
                .andExpect(jsonPath("$.items[0].snapshot.mediaUrls[0]").value("https://acc.blob/media/x"))
                .andExpect(jsonPath("$.items[0].snapshot.authorUserId").value(AUTHOR_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-10T12:02:00Z"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        var cursor = (String) readMap(firstPage).get("nextCursor");

        mockMvc.perform(get("/api/posts/reports")
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reportId").value(oldest.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));

        mockMvc.perform(get("/api/posts/reports").param("status", "RESOLVED").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("RESOLVED"));
    }

    @Test
    void shouldBreakCreatedAtTiesById() throws Exception {
        var postId = UUID.randomUUID();
        var sameInstant = Instant.parse("2026-11-10T12:00:00Z");
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < 3; index++) {
            ids.add(savePostReport(postId, sameInstant, ReportStatus.OPEN).getId());
        }

        var seen = new HashSet<String>();
        String cursor = null;
        do {
            var request = get("/api/posts/reports").param("limit", "1").with(as(ADMIN_ID, "ADMIN"));
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            var page = readMap(mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) page.get("items");
            items.forEach(item -> seen.add((String) item.get("reportId")));
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
    }

    @Test
    void shouldListCommentReportsForAdminWithCommentTarget() throws Exception {
        var post = savePost(PostStatus.ACCEPTED);
        var comment = saveComment(post.getId(), CommentStatus.ACTIVE);
        mockMvc.perform(post("/api/posts/{postId}/comments/{commentId}/reports", post.getId(), comment.getId())
                        .with(as(REPORTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"OTHER\", \"details\": \"threat\" }"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/posts/comments/reports").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].target.postId").value(post.getId().toString()))
                .andExpect(jsonPath("$.items[0].target.commentId").value(comment.getId().toString()))
                .andExpect(jsonPath("$.items[0].reporterUserId").value(REPORTER_ID.toString()))
                .andExpect(jsonPath("$.items[0].details").value("threat"))
                .andExpect(jsonPath("$.items[0].snapshot.content").value("you are awful"))
                .andExpect(jsonPath("$.items[0].snapshot.authorUserId").value(COMMENTER_ID.toString()))
                .andExpect(jsonPath("$.items[0].snapshot.postId").value(post.getId().toString()))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void shouldRejectMalformedCursorLimitAndStatus() throws Exception {
        mockMvc.perform(get("/api/posts/reports").param("cursor", "%%%").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_cursor"));
        mockMvc.perform(get("/api/posts/reports").param("limit", "51").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_limit"));
        mockMvc.perform(get("/api/posts/comments/reports").param("limit", "abc").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_limit"));
        mockMvc.perform(get("/api/posts/reports").param("status", "CLOSED").with(as(ADMIN_ID, "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions reportPost(UUID postId, UUID userId, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/posts/{postId}/reports", postId)
                .with(as(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"" + reason + "\" }"));
    }

    /** Builds the authentication with the application's own JWT converter (realm_access.roles). */
    private RequestPostProcessor as(UUID userId, String... realmRoles) {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("realm_access", Map.of("roles", List.of(realmRoles.length == 0 ? new String[] {"USER"} : realmRoles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return authentication(jwtAuthenticationConverter.convert(jwt));
    }

    private PostEntity savePost(PostStatus status) {
        var post = PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(AUTHOR_ID)
                .postType(PostType.BASIC)
                .description("original caption")
                .tags(new ArrayList<>(List.of("default")))
                .status(status)
                .build();
        if (status != PostStatus.DELETED) {
            post.addMedia(PostMediaEntity.builder()
                    .id(UUID.randomUUID())
                    .url("https://acc.blob/media/one")
                    .mediaType(com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE)
                    .mediaOrder(1)
                    .build());
        }
        return postJpaRepository.save(post);
    }

    private CommentEntity saveComment(UUID postId, CommentStatus status) {
        return commentJpaRepository.save(CommentEntity.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .userId(COMMENTER_ID)
                .content("you are awful")
                .status(status)
                .build());
    }

    private PostReportEntity savePostReport(UUID postId, Instant createdAt, ReportStatus status) {
        return postReportJpaRepository.save(PostReportEntity.builder()
                .id(UUID.randomUUID())
                .postId(postId)
                .reporterUserId(UUID.randomUUID())
                .reason(ReportReason.NUDITY)
                .snapshot(new PostReportSnapshotPayload("snap", List.of("https://acc.blob/media/x"), AUTHOR_ID))
                .status(status)
                .createdAt(createdAt.truncatedTo(ChronoUnit.MICROS))
                .build());
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }
}
