package com.app.socialservice;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String AZURITE_ACCOUNT_NAME = "devstoreaccount1";
    private static final int AZURITE_BLOB_PORT = 10000;

    @Bean
    @ServiceConnection
    LgtmStackContainer grafanaLgtmContainer() {
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
    }

    @Bean
    @ServiceConnection
    Neo4jContainer neo4jContainer() {
        return new Neo4jContainer(DockerImageName.parse("neo4j:latest"));
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:latest"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
    }

    /**
     * The media container of the Azure Blob tests (profile pictures). No Testcontainers module
     * exists for Azurite, so it is a generic container on the image post-query-service pins.
     */
    @Bean
    GenericContainer<?> azuriteContainer() {
        return new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.35.0"))
                .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
                .withExposedPorts(AZURITE_BLOB_PORT)
                .waitingFor(Wait.forListeningPort());
    }

    @Bean
    DynamicPropertyRegistrar azuriteProperties(GenericContainer<?> azuriteContainer) {
        return registry -> registry.add("azure.storage.account-url", () -> String.format(
                "http://%s:%d/%s",
                azuriteContainer.getHost(),
                azuriteContainer.getMappedPort(AZURITE_BLOB_PORT),
                AZURITE_ACCOUNT_NAME));
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder() {
        return token -> {
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-user")
                    .issuedAt(now)
                    .expiresAt(now.plus(Duration.ofHours(1)))
                    .build();
        };
    }

}
