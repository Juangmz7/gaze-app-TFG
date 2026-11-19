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

    private void applySchemaScript() throws IOException, SQLException {
        var script = new ClassPathResource(SCHEMA_SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute(script);
        }
    }

    private void insertUser(String username) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement("""
                INSERT INTO users (id, username, email, account_status, created_at)
                VALUES (?, ?, ?, 'ACCEPTED', NOW())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, username);
            statement.setString(3, username + "@example.com");
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
