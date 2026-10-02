package com.app.postcommandservice.shared.infrastructure.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostCommandProductionSchemaValidationIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForLegacyPostLikesTable() {
        bootstrapSchema("create");
        execute("ALTER TABLE post_likes DROP COLUMN source");
        execute("ALTER TABLE post_likes DROP COLUMN feed_position");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("post_likes");

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
        assertForeignKeyExists("posts", "fk_posts_collab");
    }

    @Test
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForCommentLikesTable() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS comment_likes");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("comment_likes");

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
    }

    @Test
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForPostSharesTable() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS post_shares");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("post_shares");
        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
    }

    @Test
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForCommentRequestIdempotencyTable() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS comment_request_idempotency");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("comment_request_idempotency");
        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
    }

    @Test
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForCollabTablesAndPostColumns() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS collab_request_idempotency");
        execute("DROP TABLE IF EXISTS collab_members");
        execute("ALTER TABLE posts DROP CONSTRAINT IF EXISTS fk_posts_collab");
        execute("DROP TABLE IF EXISTS collabs");
        execute("ALTER TABLE posts DROP COLUMN collab_id");
        execute("ALTER TABLE posts DROP COLUMN post_type");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("collab_members");

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
    }

    @Test
    void shouldRequireTrackedSchemaPatchForPostMediaTaggedUsersTable() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS post_media_tagged_users");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("post_media_tagged_users");

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
    }

    @Test
    void shouldMigrateTaggedUsersFromPostsToOrderOneMediaAndCopyExistingData() {
        bootstrapSchema("create");

        UUID postId = UUID.randomUUID();
        UUID postMediaId = UUID.randomUUID();
        Instant now = Instant.now();

        execute("""
                CREATE TABLE post_tagged_users (
                    post_id UUID NOT NULL,
                    username VARCHAR(255) NOT NULL,
                    CONSTRAINT pk_post_tagged_users PRIMARY KEY (post_id, username)
                )
                """);

        execute("""
                INSERT INTO posts (id, user_id, post_type, description, status, created_at, updated_at, version)
                VALUES ('%s', '%s', 'BASIC', 'legacy post', 'ACCEPTED', '%s', '%s', 0)
                """.formatted(postId, UUID.randomUUID(), now, now));

        execute("""
                INSERT INTO post_media (id, post_id, url, thumbnail_url, media_type, duration, media_order)
                VALUES ('%s', '%s', 'https://example.com/media.png', 'https://example.com/media.png', 'IMAGE', NULL, 1)
                """.formatted(postMediaId, postId));

        execute("INSERT INTO post_tagged_users (post_id, username) VALUES ('%s', 'legacyuser')".formatted(postId));

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT username FROM post_media_tagged_users WHERE post_media_id = '" + postMediaId + "'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("username")).isEqualTo("legacyuser");
            assertThat(resultSet.next()).isFalse();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read post_media_tagged_users", exception);
        }

        assertThatThrownBy(() -> execute("SELECT 1 FROM post_tagged_users"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldScopePostRequestIdempotencyByUserAndBackfillUserIdFromPosts() {
        bootstrapSchema("create");

        UUID postId = UUID.randomUUID();
        UUID postOwnerId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant now = Instant.now();

        execute("""
                INSERT INTO posts (id, user_id, post_type, description, status, created_at, updated_at, version)
                VALUES ('%s', '%s', 'BASIC', 'legacy post', 'ACCEPTED', '%s', '%s', 0)
                """.formatted(postId, postOwnerId, now, now));

        // Simulate the pre-fix shape: single correlation_id PK, no user_id/request_hash columns.
        execute("DROP TABLE IF EXISTS post_request_idempotency");
        execute("""
                CREATE TABLE post_request_idempotency (
                    correlation_id UUID NOT NULL,
                    post_id UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT post_request_idempotency_pkey PRIMARY KEY (correlation_id),
                    CONSTRAINT uk_post_request_idempotency_post_id UNIQUE (post_id)
                )
                """);
        execute("""
                INSERT INTO post_request_idempotency (correlation_id, post_id, created_at)
                VALUES ('%s', '%s', '%s')
                """.formatted(correlationId, postId, now));

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class);

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT user_id, request_hash FROM post_request_idempotency WHERE correlation_id = '"
                             + correlationId + "'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(UUID.fromString(resultSet.getString("user_id"))).isEqualTo(postOwnerId);
            assertThat(resultSet.getString("request_hash")).isNull();
            assertThat(resultSet.next()).isFalse();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read post_request_idempotency", exception);
        }
    }

    private void applySchemaPatch(String resourcePath) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resourcePath));
        populator.execute(dataSource());
    }

    private void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to execute SQL: " + sql, exception);
        }
    }

    private void assertForeignKeyExists(String tableName, String constraintName) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             ResultSet resultSet = connection.getMetaData().getImportedKeys(connection.getCatalog(), "public", tableName)) {
            boolean found = false;
            while (resultSet.next()) {
                if (constraintName.equalsIgnoreCase(resultSet.getString("FK_NAME"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AssertionError("Missing foreign key " + constraintName + " on table " + tableName);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to inspect foreign key " + constraintName + " on table " + tableName,
                    exception
            );
        }
    }

    private void bootstrapSchema(String ddlAuto) {
        LocalContainerEntityManagerFactoryBean entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource());
        entityManagerFactory.setPackagesToScan("com.app.postcommandservice");
        entityManagerFactory.setJpaVendorAdapter(jpaVendorAdapter());
        entityManagerFactory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", ddlAuto,
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
        ));

        try {
            entityManagerFactory.afterPropertiesSet();
            var emf = entityManagerFactory.getObject();
            if (emf != null) {
                emf.close();
            }
        } finally {
            entityManagerFactory.destroy();
        }
    }

    private DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(org.postgresql.Driver.class.getName());
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setAutoCommit(false);
        return new HikariDataSource(config);
    }

    private JpaVendorAdapter jpaVendorAdapter() {
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setShowSql(false);
        adapter.setGenerateDdl(false);
        adapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
        return adapter;
    }
}
