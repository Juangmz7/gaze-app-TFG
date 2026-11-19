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
