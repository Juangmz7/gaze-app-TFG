package com.app.postcommandservice.shared.infrastructure.schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
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
    void shouldRequireTrackedSchemaPatchBeforeProductionValidationPassesForCommentRequestIdempotencyTable() {
        bootstrapSchema("create");
        execute("DROP TABLE IF EXISTS comment_request_idempotency");

        assertThatThrownBy(() -> bootstrapSchema("validate"))
                .hasRootCauseInstanceOf(Exception.class)
                .hasMessageContaining("comment_request_idempotency");
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
