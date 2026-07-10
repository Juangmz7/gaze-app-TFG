package com.app.socialservice.shared.infrastructure.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import javax.sql.DataSource;

import com.app.socialservice.SocialServiceApplication;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProcessedEventsFlywayStartupIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
            new PostgreSQLContainer<>("postgres:latest");

    @Test
    void shouldBaselineAndMigrateLegacyProcessedEventsSchemaWhenSpringBootStarts() throws Exception {
        var schemaName = "flyway_startup_" + UUID.randomUUID().toString().replace("-", "");
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        createLegacyProcessedEventsSchema(schemaName, eventId, correlationId);

        try (var context = startApplication(schemaName)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.flyway.baseline-version")).isEqualTo("0");

            var jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT target_database FROM processed_events WHERE id = ?",
                    String.class,
                    eventId
            )).isEqualTo(TargetDatabase.POSTGRES.name());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT correlation_id FROM processed_events WHERE id = ?",
                    UUID.class,
                    eventId
            )).isEqualTo(correlationId);
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '0' AND success
                    """,
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '1' AND success
                    """,
                    Integer.class
            )).isEqualTo(1);
        } finally {
            dropSchema(schemaName);
        }
    }

    private void createLegacyProcessedEventsSchema(String schemaName, UUID eventId, UUID correlationId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword()
        )) {
            connection.createStatement().execute("CREATE SCHEMA " + schemaName);
            connection.createStatement().execute("SET search_path TO " + schemaName);
            connection.createStatement().execute("""
                    CREATE TABLE processed_events (
                        id UUID PRIMARY KEY,
                        correlation_id UUID NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        processed_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE outbox_event (
                        id UUID PRIMARY KEY,
                        correlation_id UUID NOT NULL,
                        payload TEXT NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        status VARCHAR(255) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            connection.createStatement().execute(String.format(
                    """
                    INSERT INTO processed_events (id, correlation_id, event_type, processed_at)
                    VALUES ('%s', '%s', 'UserRegisteredEvent', NOW())
                    """,
                    eventId,
                    correlationId
            ));
        }
    }

    private ConfigurableApplicationContext startApplication(String schemaName) {
        return SpringApplication.from(SocialServiceApplication::main)
                .with(FlywayStartupTestConfiguration.class)
                .run(
                        "--spring.profiles.active=test",
                        "--server.port=0",
                        "--spring.datasource.url=" + schemaJdbcUrl(schemaName),
                        "--spring.datasource.username=" + POSTGRESQL_CONTAINER.getUsername(),
                        "--spring.datasource.password=" + POSTGRESQL_CONTAINER.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.jpa.properties.hibernate.default_schema=" + schemaName,
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.default-schema=" + schemaName,
                        "--spring.flyway.schemas=" + schemaName,
                        "--spring.rabbitmq.host=localhost",
                        "--spring.rabbitmq.port=5672",
                        "--spring.rabbitmq.username=guest",
                        "--spring.rabbitmq.password=guest",
                        "--spring.rabbitmq.listener.simple.auto-startup=false",
                        "--spring.data.redis.host=localhost",
                        "--spring.data.redis.port=6379",
                        "--spring.neo4j.uri=bolt://localhost:7687",
                        "--spring.neo4j.authentication.username=neo4j",
                        "--spring.neo4j.authentication.password=test"
                )
                .getApplicationContext();
    }

    private String schemaJdbcUrl(String schemaName) {
        return POSTGRESQL_CONTAINER.getJdbcUrl() + "&currentSchema=" + schemaName;
    }

    private void dropSchema(String schemaName) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword()
        )) {
            connection.createStatement().execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlywayStartupTestConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                var now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject("test-user")
                        .issuedAt(now)
                        .expiresAt(now.plus(Duration.ofHours(1)))
                        .build();
            };
        }

        @Bean
        Flyway flyway(Environment environment, DataSource dataSource) {
            var configuredSchemas = environment.getProperty("spring.flyway.schemas", "");
            var schemas = Arrays.stream(configuredSchemas.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toArray(String[]::new);

            return Flyway.configure()
                    .dataSource(dataSource)
                    .baselineOnMigrate(Boolean.TRUE.equals(
                            environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)
                    ))
                    .baselineVersion(environment.getProperty("spring.flyway.baseline-version", "1"))
                    .defaultSchema(environment.getProperty("spring.flyway.default-schema"))
                    .schemas(schemas)
                    .locations("classpath:db/migration")
                    .load();
        }

        @Bean
        InitializingBean flywayMigrator(Flyway flyway) {
            return flyway::migrate;
        }
    }
}
