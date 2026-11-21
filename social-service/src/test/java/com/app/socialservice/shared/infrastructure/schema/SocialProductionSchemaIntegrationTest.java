package com.app.socialservice.shared.infrastructure.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.app.socialservice.follow.infrastructure.repository.JpaFollowRepository;
import com.app.socialservice.report.infrastructure.repository.JpaUserReportRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production schema script (prod runs with ddl-auto=validate, so Hibernate never creates
 * these indexes) on its own PostgreSQL: the other integration tests use create-drop.
 */
@Testcontainers
class SocialProductionSchemaIntegrationTest {

    private static final String SCHEMA_SCRIPT = "db/schema/social-service-prod.sql";
    private static final String USERNAME_INDEX = "uk_users_username_lower";
    private static final String FOLLOWERS_PAGE_INDEX = "idx_follows_followers_page";
    private static final String FOLLOWING_PAGE_INDEX = "idx_follows_following_page";
    private static final String USER_REPORTS_PAGE_INDEX = "idx_user_reports_status_created_at_id";
    private static final String USER_REPORTS_OPEN_INDEX = "uk_user_reports_open_reporter_reported";

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

    @BeforeEach
    void resetSchema() throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void shouldApplyTheScriptTwiceWithoutErrors() throws Exception {
        applySchemaScript();

        applySchemaScript();

        assertThat(indexDefinition(USERNAME_INDEX)).contains("UNIQUE", "lower((username)::text)");
        assertThat(indexDefinition(FOLLOWERS_PAGE_INDEX))
                .contains("(followed_id, created_at DESC, follower_id DESC)");
        assertThat(indexDefinition(FOLLOWING_PAGE_INDEX))
                .contains("(follower_id, created_at DESC, followed_id DESC)");
        assertThat(indexDefinition(USER_REPORTS_PAGE_INDEX)).contains("(status, created_at DESC, id DESC)");
        assertThat(indexDefinition(USER_REPORTS_OPEN_INDEX))
                .contains("UNIQUE", "(reporter_user_id, reported_user_id)", "WHERE", "'OPEN'");
    }

    @Test
    void shouldInsertOnlyOneOpenReportPerReporterAndReportedUser() throws Exception {
        applySchemaScript();
        var reporterId = UUID.randomUUID();
        var reportedId = UUID.randomUUID();

        var first = insertUserReport(reporterId, reportedId, "OPEN");
        var duplicate = insertUserReport(reporterId, reportedId, "OPEN");
        markReportsResolved(reporterId, reportedId);
        var afterResolution = insertUserReport(reporterId, reportedId, "OPEN");
        var otherReporter = insertUserReport(UUID.randomUUID(), reportedId, "OPEN");

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(afterResolution).isEqualTo(1);
        assertThat(otherReporter).isEqualTo(1);
    }

    @Test
    void shouldRejectAnUnknownReportStatus() throws Exception {
        applySchemaScript();

        assertThatThrownBy(() -> insertUserReport(UUID.randomUUID(), UUID.randomUUID(), "CLOSED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_user_reports_status");
    }

    @Test
    void shouldServeEveryUserReportsPageFromAnIndexSeekInTheListOrder() throws Exception {
        applySchemaScript();
        var reportedId = insertUser("reported");
        for (int i = 0; i < 30; i++) {
            insertUserReport(UUID.randomUUID(), reportedId, i % 3 == 0 ? "RESOLVED" : "OPEN");
        }

        var firstPage = explainUserReports(JpaUserReportRepository.PAGE_SELECT + JpaUserReportRepository.PAGE_ORDER);
        var nextPage = explainUserReports(JpaUserReportRepository.PAGE_SELECT
                + JpaUserReportRepository.PAGE_AFTER_CURSOR
                + JpaUserReportRepository.PAGE_ORDER);

        assertUserReportsIndexSeekWithoutSort(firstPage);
        assertUserReportsIndexSeekWithoutSort(nextPage);
        assertThat(indexCondition(nextPage, USER_REPORTS_PAGE_INDEX))
                .as("plan:%n%s", nextPage)
                .contains("ROW(created_at, id) < ROW(");
    }

    @Test
    void shouldFindTheOpenReportOfAReporterThroughThePartialIndex() throws Exception {
        applySchemaScript();
        for (int i = 0; i < 30; i++) {
            insertUserReport(UUID.randomUUID(), UUID.randomUUID(), "OPEN");
        }

        var plan = explainQuery(JpaUserReportRepository.FIND_OPEN_RECEIPT
                .replace(":reporterUserId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":reportedUserId", "'" + UUID.randomUUID() + "'::uuid"));

        assertThat(plan).as("plan:%n%s", plan).contains(USER_REPORTS_OPEN_INDEX);
    }

    @Test
    void shouldRejectAUsernameThatOnlyDiffersInLetterCase() throws Exception {
        applySchemaScript();
        insertUser("jane.doe");

        assertThatThrownBy(() -> insertUser("Jane.Doe"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(USERNAME_INDEX);
    }

    @Test
    void shouldServeTheCaseInsensitiveUsernameLookupFromTheIndex() throws Exception {
        applySchemaScript();
        insertUser("jane.doe");

        try (var connection = connect(); var statement = connection.createStatement()) {
            // The table is tiny, so the planner would scan it; this proves the index matches the query
            statement.execute("SET enable_seqscan = off");
            var plan = new StringBuilder();
            try (var resultSet = statement.executeQuery(
                    "EXPLAIN SELECT id FROM users WHERE lower(username) = 'jane.doe'")) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }

            assertThat(plan).contains(USERNAME_INDEX);
        }
    }

    @Test
    void shouldServeEveryFollowersPageFromAnIndexSeekInTheListOrder() throws Exception {
        applySchemaScript();
        var ownerId = insertUser("owner");
        var viewerId = insertUser("viewer");
        for (int i = 0; i < 30; i++) {
            insertActiveFollow(insertUser("follower" + i), ownerId);
        }

        var firstPage = explain(JpaFollowRepository.FOLLOWERS_PAGE_SELECT + JpaFollowRepository.FOLLOWERS_PAGE_ORDER,
                ownerId, viewerId);
        var nextPage = explain(JpaFollowRepository.FOLLOWERS_PAGE_SELECT
                + JpaFollowRepository.FOLLOWERS_PAGE_AFTER_CURSOR
                + JpaFollowRepository.FOLLOWERS_PAGE_ORDER, ownerId, viewerId);

        assertIndexSeekWithoutSort(firstPage, FOLLOWERS_PAGE_INDEX);
        assertIndexSeekWithoutSort(nextPage, FOLLOWERS_PAGE_INDEX);
        assertThat(indexCondition(nextPage, FOLLOWERS_PAGE_INDEX))
                .as("plan:%n%s", nextPage)
                .contains("ROW(created_at, follower_id) < ROW(");
    }

    @Test
    void shouldServeEveryFollowingPageFromAnIndexSeekInTheListOrder() throws Exception {
        applySchemaScript();
        var ownerId = insertUser("owner");
        var viewerId = insertUser("viewer");
        for (int i = 0; i < 30; i++) {
            insertActiveFollow(ownerId, insertUser("followed" + i));
        }

        var firstPage = explain(JpaFollowRepository.FOLLOWING_PAGE_SELECT + JpaFollowRepository.FOLLOWING_PAGE_ORDER,
                ownerId, viewerId);
        var nextPage = explain(JpaFollowRepository.FOLLOWING_PAGE_SELECT
                + JpaFollowRepository.FOLLOWING_PAGE_AFTER_CURSOR
                + JpaFollowRepository.FOLLOWING_PAGE_ORDER, ownerId, viewerId);

        assertIndexSeekWithoutSort(firstPage, FOLLOWING_PAGE_INDEX);
        assertIndexSeekWithoutSort(nextPage, FOLLOWING_PAGE_INDEX);
        assertThat(indexCondition(nextPage, FOLLOWING_PAGE_INDEX))
                .as("plan:%n%s", nextPage)
                .contains("ROW(created_at, followed_id) < ROW(");
    }

    /**
     * EXPLAIN of the repository's own query text, with literals for its parameters. The tables
     * are tiny, so sequential scans are switched off: this proves an index matches the query.
     */
    private String explain(String query, UUID ownerId, UUID viewerId) throws SQLException {
        var sql = query
                .replace(":listOwnerId", "'" + ownerId + "'::uuid")
                .replace(":viewerId", "'" + viewerId + "'::uuid")
                .replace(":afterCreatedAt", "'2026-11-19T10:00:00Z'::timestamptz")
                .replace(":afterUserId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":fetchSize", "21");
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("ANALYZE");
            statement.execute("SET enable_seqscan = off");
            var plan = new StringBuilder();
            try (var resultSet = statement.executeQuery("EXPLAIN " + sql)) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }

    private String explainUserReports(String query) throws SQLException {
        return explainQuery(query
                .replace(":status", "'OPEN'")
                .replace(":afterCreatedAt", "'2026-11-19T10:00:00Z'::timestamptz")
                .replace(":afterReportId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":fetchSize", "21"));
    }

    private String explainQuery(String sql) throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("ANALYZE");
            statement.execute("SET enable_seqscan = off");
            var plan = new StringBuilder();
            try (var resultSet = statement.executeQuery("EXPLAIN " + sql)) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
            return plan.toString();
        }
    }

    private void assertUserReportsIndexSeekWithoutSort(String plan) {
        assertThat(plan).as("plan:%n%s", plan)
                .contains("Index Scan using " + USER_REPORTS_PAGE_INDEX + " on user_reports");
        assertThat(plan).as("plan:%n%s", plan).doesNotContainPattern("(?m)^\\s*(->\\s*)?(Incremental )?Sort\\b");
        assertThat(indexCondition(plan, USER_REPORTS_PAGE_INDEX)).as("plan:%n%s", plan)
                .contains("status)::text = 'OPEN'");
    }

    /**
     * The repository's own insert, with literals for its parameters: the number of rows inserted.
     */
    private int insertUserReport(UUID reporterId, UUID reportedId, String status) throws SQLException {
        var sql = JpaUserReportRepository.INSERT_IF_NO_OPEN_REPORT
                .replace(":id", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":reportedUserId", "'" + reportedId + "'::uuid")
                .replace(":reporterUserId", "'" + reporterId + "'::uuid")
                .replace(":reason", "'SPAM'")
                .replace(":details", "NULL")
                .replace(":snapshot", "'{\"username\":\"reported\"}'")
                .replace(":status", "'" + status + "'")
                .replace(":createdAt", "clock_timestamp()");
        try (var connection = connect(); var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private void markReportsResolved(UUID reporterId, UUID reportedId) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(
                "UPDATE user_reports SET status = 'RESOLVED' WHERE reporter_user_id = ? AND reported_user_id = ?")) {
            statement.setObject(1, reporterId);
            statement.setObject(2, reportedId);
            statement.executeUpdate();
        }
    }

    private void assertIndexSeekWithoutSort(String plan, String indexName) {
        assertThat(plan).as("plan:%n%s", plan).contains("Index Scan using " + indexName + " on follows");
        assertThat(plan).as("plan:%n%s", plan).doesNotContainPattern("(?m)^\\s*(->\\s*)?(Incremental )?Sort\\b");
        assertThat(indexCondition(plan, indexName)).as("plan:%n%s", plan).contains("_id = '");
    }

    private String indexCondition(String plan, String indexName) {
        var lines = plan.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Index Scan using " + indexName)) {
                return lines.subList(i + 1, lines.size()).stream()
                        .filter(line -> line.contains("Index Cond:"))
                        .findFirst()
                        .orElse("");
            }
        }
        return "";
    }

    private void applySchemaScript() throws IOException, SQLException {
        var script = new ClassPathResource(SCHEMA_SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute(script);
        }
    }

    private UUID insertUser(String username) throws SQLException {
        var userId = UUID.randomUUID();
        try (var connection = connect(); var statement = connection.prepareStatement("""
                INSERT INTO users (id, username, email, account_status, created_at)
                VALUES (?, ?, ?, 'ACCEPTED', NOW())
                """)) {
            statement.setObject(1, userId);
            statement.setString(2, username);
            statement.setString(3, username + "@example.com");
            statement.executeUpdate();
        }
        return userId;
    }

    private void insertActiveFollow(UUID followerId, UUID followedId) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement("""
                INSERT INTO follows (follower_id, followed_id, status, created_at)
                VALUES (?, ?, 'ACTIVE', clock_timestamp())
                """)) {
            statement.setObject(1, followerId);
            statement.setObject(2, followedId);
            statement.executeUpdate();
        }
    }

    private String indexDefinition(String indexName) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("SELECT indexdef FROM pg_indexes WHERE indexname = ?")) {
            statement.setString(1, indexName);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("index %s exists", indexName).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
