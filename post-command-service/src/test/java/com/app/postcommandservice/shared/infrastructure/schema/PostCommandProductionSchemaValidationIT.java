package com.app.postcommandservice.shared.infrastructure.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

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
    void shouldRestorePostMediaConstraintsAndOptimisticVersionColumn() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS post_media");
        execute("ALTER TABLE posts DROP COLUMN version");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class);

        applySchemaPatch("db/schema/post-command-service-prod.sql");

        assertThatNoException().isThrownBy(() -> bootstrapSchema("validate"));
        assertForeignKeyExists("post_media", "fk_post_media_post");
        assertUniqueConstraintExists("post_media", "uk_post_media_post_order");
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

    private void assertUniqueConstraintExists(String tableName, String constraintName) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             ResultSet resultSet = connection.getMetaData().getIndexInfo(connection.getCatalog(), "public", tableName, true, false)) {
            while (resultSet.next()) {
                if (constraintName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return;
                }
            }
            throw new AssertionError("Missing unique constraint " + constraintName + " on " + tableName);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect unique constraint " + constraintName, exception);
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
