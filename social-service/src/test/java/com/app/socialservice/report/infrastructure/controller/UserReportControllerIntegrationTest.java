package com.app.socialservice.report.infrastructure.controller;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.report.domain.enums.ReportStatus;
import com.app.socialservice.report.domain.enums.UserReportReason;
import com.app.socialservice.report.domain.model.valueobj.ReportDetails;
import com.app.socialservice.report.infrastructure.repository.JpaUserReportRepository;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.testutil.SocialIntegrationSeeder;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.testutil.UserMother;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tokens go through the real filter chain and JWT converter (only the signature check is
 * replaced by the mocked JwtDecoder), so the realm_access.roles to ROLE_* mapping is exercised.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserReportControllerIntegrationTest {

    private static final String REPORTS_PATH = "/api/social/reports/users";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";
    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";
    private static final String BAD_REQUEST_ERROR_CODE = "BAD_REQUEST";
    private static final String FORBIDDEN_ERROR_CODE = "FORBIDDEN";
    private static final String USER_NOT_FOUND_ERROR_CODE = "user_not_found";
    private static final String CANNOT_REPORT_SELF_ERROR_CODE = "cannot_report_self";
    private static final String INVALID_CURSOR_ERROR_CODE = "invalid_cursor";
    private static final String INVALID_LIMIT_ERROR_CODE = "invalid_limit";
    private static final String REPORTED_DESCRIPTION = "Original bio";
    private static final Map<String, String> REPORTED_SOCIAL_MEDIA = Map.of("instagram", "@original");
    private static final String REPORTED_PICTURE = "https://cdn.example.com/avatars/original.jpg";
    private static final Instant NEWEST_REPORT = Instant.parse("2026-11-19T10:00:00.123456Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaUserRepository jpaUserRepository;

    @Autowired
    private JpaUserReportRepository jpaUserReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jpaUserReportRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void reportingAUserAnswers201WithTheReceiptAndStoresASnapshotOfTheProfile() throws Exception {
        var reporterId = seedUser("reporter");
        var reportedId = seedReportedUser("reported");

        var response = postReport(reporterId, reportBody(reportedId, UserReportReason.IMPERSONATION, "  fake  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(ReportStatus.OPEN.name()))
                .andReturn().getResponse().getContentAsString();

        var receipt = jsonMapper.readTree(response);
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("reportId", "status", "createdAt");
        var row = jdbcTemplate.queryForMap("""
                SELECT reporter_user_id, reported_user_id, reason, details, status, created_at,
                       CAST(snapshot AS TEXT) AS snapshot
                FROM user_reports WHERE id = ?
                """, UUID.fromString(receipt.get("reportId").asString()));
        assertThat(row.get("reporter_user_id")).isEqualTo(reporterId);
        assertThat(row.get("reported_user_id")).isEqualTo(reportedId);
        assertThat(row.get("reason")).isEqualTo(UserReportReason.IMPERSONATION.name());
        assertThat(row.get("details")).isEqualTo("fake");
        assertThat(row.get("status")).isEqualTo(ReportStatus.OPEN.name());
        assertThat(Instant.parse(receipt.get("createdAt").asString()))
                .isEqualTo(((Timestamp) row.get("created_at")).toInstant());
        var snapshot = jsonMapper.readTree((String) row.get("snapshot"));
        assertThat(snapshot.get("username").asString()).isEqualTo("reported");
        assertThat(snapshot.get("description").asString()).isEqualTo(REPORTED_DESCRIPTION);
        assertThat(snapshot.get("socialMedia").get("instagram").asString()).isEqualTo("@original");
        assertThat(snapshot.get("profilePic").asString()).isEqualTo(REPORTED_PICTURE);
    }

    @Test
    void reportingTheSameUserTwiceKeepsOneOpenReportAndAnswersItWith200() throws Exception {
        var reporterId = seedUser("reporter");
        var reportedId = seedReportedUser("reported");

        var first = readJson(postReport(reporterId, reportBody(reportedId, UserReportReason.SPAM, null))
                .andExpect(status().isCreated()));
        var second = readJson(postReport(reporterId, reportBody(reportedId, UserReportReason.HARASSMENT, null))
                .andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(countReports()).isEqualTo(1);
    }

    @Test
    void anotherReporterOpensItsOwnReportOnTheSameUser() throws Exception {
        var reportedId = seedReportedUser("reported");

        postReport(seedUser("reporter1"), reportBody(reportedId, UserReportReason.SPAM, null))
                .andExpect(status().isCreated());
        postReport(seedUser("reporter2"), reportBody(reportedId, UserReportReason.SPAM, null))
                .andExpect(status().isCreated());

        assertThat(countReports()).isEqualTo(2);
    }

    @Test
    void reasonOtherWithoutDetailsAnswers400() throws Exception {
        var reporterId = seedUser("reporter");
        var reportedId = seedReportedUser("reported");

        postReport(reporterId, reportBody(reportedId, UserReportReason.OTHER, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));
        postReport(reporterId, reportBody(reportedId, UserReportReason.OTHER, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));
        postReport(reporterId, reportBody(reportedId, UserReportReason.SPAM, "x".repeat(ReportDetails.MAX_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));

        assertThat(countReports()).isZero();
    }

    @Test
    void reportingYourselfAnswers400CannotReportSelf() throws Exception {
        var reporterId = seedUser("reporter");

        postReport(reporterId, reportBody(reporterId, UserReportReason.SPAM, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(CANNOT_REPORT_SELF_ERROR_CODE));
    }

    @Test
    void unknownDeletedOrBannedUsersAnswer404UserNotFound() throws Exception {
        var reporterId = seedUser("reporter");
        var deletedId = seedUser("deleted", UserAccountStatus.DELETED);
        var bannedId = seedUser("banned", UserAccountStatus.BANNED);

        for (var reportedId : List.of(UUID.randomUUID(), deletedId, bannedId)) {
            postReport(reporterId, reportBody(reportedId, UserReportReason.SPAM, null))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value(USER_NOT_FOUND_ERROR_CODE));
        }
        assertThat(countReports()).isZero();
    }

    @Test
    void malformedBodiesAnswer400NotA500() throws Exception {
        var reporterId = seedUser("reporter");
        var reportedId = seedReportedUser("reported");

        var malformedBodies = List.of(
                "{\"reportedUserId\":\"not-a-uuid\",\"reason\":\"SPAM\",\"details\":null}",
                "{\"reportedUserId\":\"" + reportedId + "\",\"reason\":\"RUDE\",\"details\":null}",
                "{\"reportedUserId\":",
                "not json"
        );
        for (var body : malformedBodies) {
            postReport(reporterId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));
        }
        postReport(reporterId, "{\"reason\":\"SPAM\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));
        postReport(reporterId, "{\"reportedUserId\":\"" + reportedId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(VALIDATION_ERROR_CODE));
        assertThat(countReports()).isZero();
    }

    @Test
    void theAdminListAnswers403ForAUserToken() throws Exception {
        var userId = seedUser("regular");

        mockMvc.perform(withToken(get(REPORTS_PATH), userId, USER_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(FORBIDDEN_ERROR_CODE));
        mockMvc.perform(withToken(get(REPORTS_PATH), userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(FORBIDDEN_ERROR_CODE));
    }

    @Test
    void theAdminListAnswers401WithoutAToken() throws Exception {
        mockMvc.perform(get(REPORTS_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminPagesTheOpenReportsNewestFirstWithoutDuplicates() throws Exception {
        var adminId = seedUser("admin");
        var reportedId = seedReportedUser("reported");
        var seededOpenIds = new ArrayList<UUID>();
        for (int i = 0; i < 25; i++) {
            // Two reports share each instant, so the report id breaks the ties
            seededOpenIds.add(seedReport(seedUser("reporter" + i), reportedId, ReportStatus.OPEN,
                    NEWEST_REPORT.minusSeconds(i / 2)));
        }
        seedReport(seedUser("resolved-reporter"), reportedId, ReportStatus.RESOLVED, NEWEST_REPORT.plusSeconds(60));
        var sorted = jdbcTemplate.queryForList(
                "SELECT id FROM user_reports WHERE status = 'OPEN' ORDER BY created_at DESC, id DESC", UUID.class);

        var first = getPage(adminId, "?limit=10");
        var second = getPage(adminId, "?limit=10&cursor=" + first.get("nextCursor").asString());
        var third = getPage(adminId, "?limit=10&cursor=" + second.get("nextCursor").asString());

        var listed = new ArrayList<UUID>();
        listed.addAll(reportIdsOf(first));
        listed.addAll(reportIdsOf(second));
        listed.addAll(reportIdsOf(third));
        assertThat(reportIdsOf(first)).hasSize(10);
        assertThat(reportIdsOf(second)).hasSize(10);
        assertThat(reportIdsOf(third)).hasSize(5);
        assertThat(third.has("nextCursor")).isTrue();
        assertThat(third.get("nextCursor").isNull()).isTrue();
        assertThat(listed).containsExactlyElementsOf(sorted);
        assertThat(new HashSet<>(listed)).containsExactlyInAnyOrderElementsOf(seededOpenIds);
        assertThat(KeysetCursor.decode(first.get("nextCursor").asString()).id()).isEqualTo(sorted.get(9));
    }

    @Test
    void theStatusParameterSelectsTheReportsOfThatStatus() throws Exception {
        var adminId = seedUser("admin");
        var reportedId = seedReportedUser("reported");
        var openId = seedReport(seedUser("reporter1"), reportedId, ReportStatus.OPEN, NEWEST_REPORT);
        var resolvedId = seedReport(seedUser("reporter2"), reportedId, ReportStatus.RESOLVED, NEWEST_REPORT);
        var dismissedId = seedReport(seedUser("reporter3"), reportedId, ReportStatus.DISMISSED, NEWEST_REPORT);

        assertThat(reportIdsOf(getPage(adminId, ""))).containsExactly(openId);
        assertThat(reportIdsOf(getPage(adminId, "?status=OPEN"))).containsExactly(openId);
        assertThat(reportIdsOf(getPage(adminId, "?status=RESOLVED"))).containsExactly(resolvedId);
        assertThat(reportIdsOf(getPage(adminId, "?status=DISMISSED"))).containsExactly(dismissedId);
    }

    @Test
    void anItemHasTheContractFieldsTheReportedUsersCurrentDataAndTheSnapshotAtReportTime() throws Exception {
        var adminId = seedUser("admin");
        var reporterId = seedUser("reporter");
        var reportedId = seedReportedUser("reported");
        var receipt = readJson(postReport(reporterId, reportBody(reportedId, UserReportReason.OTHER, "details"))
                .andExpect(status().isCreated()));
        jdbcTemplate.update("""
                UPDATE users SET username = 'renamed', description = 'New bio', picture_url = NULL,
                       social_media = NULL
                WHERE id = ?
                """, reportedId);

        var item = getPage(adminId, "").get("items").get(0);

        assertThat(item.propertyNames()).containsExactlyInAnyOrder(
                "reportId", "reporterUserId", "reportedUser", "reason", "details", "snapshot", "status", "createdAt");
        assertThat(item.get("reportId")).isEqualTo(receipt.get("reportId"));
        assertThat(item.get("createdAt")).isEqualTo(receipt.get("createdAt"));
        assertThat(item.get("reporterUserId").asString()).isEqualTo(reporterId.toString());
        assertThat(item.get("reason").asString()).isEqualTo(UserReportReason.OTHER.name());
        assertThat(item.get("details").asString()).isEqualTo("details");
        assertThat(item.get("status").asString()).isEqualTo(ReportStatus.OPEN.name());
        var reportedUser = item.get("reportedUser");
        assertThat(reportedUser.propertyNames()).containsExactlyInAnyOrder("userId", "username", "profilePic");
        assertThat(reportedUser.get("userId").asString()).isEqualTo(reportedId.toString());
        assertThat(reportedUser.get("username").asString()).isEqualTo("renamed");
        assertThat(reportedUser.get("profilePic").isNull()).isTrue();
        var snapshot = item.get("snapshot");
        assertThat(snapshot.get("username").asString()).isEqualTo("reported");
        assertThat(snapshot.get("description").asString()).isEqualTo(REPORTED_DESCRIPTION);
        assertThat(snapshot.get("socialMedia").get("instagram").asString()).isEqualTo("@original");
        assertThat(snapshot.get("profilePic").asString()).isEqualTo(REPORTED_PICTURE);
    }

    @Test
    void aPresetReasonReportListsNullDetails() throws Exception {
        var adminId = seedUser("admin");
        var reportedId = seedReportedUser("reported");
        postReport(seedUser("reporter"), reportBody(reportedId, UserReportReason.UNDERAGE, null))
                .andExpect(status().isCreated());

        var item = getPage(adminId, "").get("items").get(0);

        assertThat(item.has("details")).isTrue();
        assertThat(item.get("details").isNull()).isTrue();
    }

    @Test
    void badCursorLimitOrStatusAnswer400() throws Exception {
        var adminId = seedUser("admin");

        mockMvc.perform(withToken(get(REPORTS_PATH + "?cursor=not-a-cursor"), adminId, ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(INVALID_CURSOR_ERROR_CODE));
        for (var limit : List.of("0", "51", "abc", "")) {
            mockMvc.perform(withToken(get(REPORTS_PATH + "?limit=" + limit), adminId, ADMIN_ROLE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value(INVALID_LIMIT_ERROR_CODE));
        }
        mockMvc.perform(withToken(get(REPORTS_PATH + "?status=CLOSED"), adminId, ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(BAD_REQUEST_ERROR_CODE));
    }

    private ResultActions postReport(UUID reporterId, String body) throws Exception {
        return mockMvc.perform(withToken(post(REPORTS_PATH), reporterId, USER_ROLE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode getPage(UUID adminId, String query) throws Exception {
        return readJson(mockMvc.perform(withToken(get(REPORTS_PATH + query), adminId, ADMIN_ROLE, USER_ROLE))
                .andExpect(status().isOk()));
    }

    /**
     * A bearer token the mocked JwtDecoder turns into a Jwt carrying these realm roles; the
     * application's own JwtAuthenticationConverter maps them.
     */
    private MockHttpServletRequestBuilder withToken(
            MockHttpServletRequestBuilder request,
            UUID userId,
            String... realmRoles) {
        var tokenValue = "token-" + UUID.randomUUID();
        var builder = Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)));
        if (realmRoles.length > 0) {
            builder.claim("realm_access", Map.of("roles", List.of(realmRoles)));
        }
        when(jwtDecoder.decode(tokenValue)).thenReturn(builder.build());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue);
    }

    private String reportBody(UUID reportedUserId, UserReportReason reason, String details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("reportedUserId", reportedUserId.toString());
        body.put("reason", reason.name());
        body.put("details", details);
        return jsonMapper.writeValueAsString(body);
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private List<UUID> reportIdsOf(JsonNode page) {
        var ids = new ArrayList<UUID>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("reportId").asString())));
        return ids;
    }

    private long countReports() {
        return jpaUserReportRepository.count();
    }

    private UUID seedUser(String username) {
        return seedUser(username, UserAccountStatus.ACCEPTED);
    }

    private UUID seedUser(String username, UserAccountStatus accountStatus) {
        var userId = UUID.randomUUID();
        SocialIntegrationSeeder.seedUser(jpaUserRepository,
                UserMother.entity(userId, username, accountStatus, Instant.now(), null, null, null));
        return userId;
    }

    private UUID seedReportedUser(String username) {
        var userId = UUID.randomUUID();
        SocialIntegrationSeeder.seedUser(jpaUserRepository, UserMother.entity(userId, username,
                UserAccountStatus.ACCEPTED, Instant.now(), REPORTED_DESCRIPTION, REPORTED_SOCIAL_MEDIA,
                REPORTED_PICTURE));
        return userId;
    }

    private UUID seedReport(UUID reporterId, UUID reportedId, ReportStatus status, Instant createdAt) {
        var reportId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO user_reports
                    (id, reported_user_id, reporter_user_id, reason, details, snapshot, status, created_at)
                VALUES (?, ?, ?, ?, NULL, CAST(? AS JSONB), ?, ?)
                """,
                reportId, reportedId, reporterId, UserReportReason.SPAM.name(),
                "{\"username\":\"reported\",\"description\":null,\"socialMedia\":null,\"profilePic\":null}",
                status.name(), Timestamp.from(createdAt));
        return reportId;
    }
}
