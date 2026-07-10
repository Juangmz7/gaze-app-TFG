package com.app.socialservice.shared.infrastructure.repository;

import java.sql.Connection;
import java.util.UUID;

import javax.sql.DataSource;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.shared.infrastructure.entity.TargetDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProcessedEventsRepositoryIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbcTemplate;

    @jakarta.annotation.Resource
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldInsertTwoProcessedEventsRowsWithTheSameEventIdAndDifferentTargetDatabaseValues() {
        var eventId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.POSTGRES.name(),
                UUID.randomUUID(),
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.NEO4J.name(),
                UUID.randomUUID(),
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(1);
        assertThat(processedEventsRepository.findByIdAndTargetDatabase(eventId, TargetDatabase.POSTGRES)).isPresent();
        assertThat(processedEventsRepository.findByIdAndTargetDatabase(eventId, TargetDatabase.NEO4J)).isPresent();
    }

    @Test
    void shouldInsertTwoProcessedEventsRowsWithTheSameCorrelationIdAndDifferentTargetDatabaseValues() {
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                UUID.randomUUID(),
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                UUID.randomUUID(),
                TargetDatabase.NEO4J.name(),
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(1);
        assertThat(processedEventsRepository.findByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.POSTGRES))
                .isPresent();
        assertThat(processedEventsRepository.findByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.NEO4J))
                .isPresent();
    }

    @Test
    void shouldRejectOrIgnoreDuplicateProcessedEventsForTheSameIdAndTargetDatabase() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isZero();
        assertThat(countRowsForIdAndTarget(eventId, TargetDatabase.POSTGRES)).isEqualTo(1);
    }

    @Test
    void shouldIgnoreDuplicateProcessedEventsForTheSameCorrelationIdAndTargetDatabase() {
        var firstEventId = UUID.randomUUID();
        var secondEventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                firstEventId,
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                secondEventId,
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isZero();
        assertThat(processedEventsRepository.findByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.POSTGRES))
                .get()
                .extracting(com.app.socialservice.shared.infrastructure.entity.ProcessedEvent::getId)
                .isEqualTo(firstEventId);
        assertThat(countRowsForCorrelationAndTarget(correlationId, TargetDatabase.POSTGRES)).isEqualTo(1);
    }

    @Test
    void shouldAllowTheSameEventToBeProcessedOnceByPostgresAndOnceByNeo4j() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.POSTGRES.name(),
                correlationId,
                "UserFollowedEvent"
        );

        assertThat(processedEventsRepository.existsByIdAndTargetDatabase(eventId, TargetDatabase.POSTGRES)).isTrue();
        assertThat(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.POSTGRES))
                .isTrue();
        assertThat(processedEventsRepository.existsByIdAndTargetDatabase(eventId, TargetDatabase.NEO4J)).isFalse();
        assertThat(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.NEO4J))
                .isFalse();

        processedEventsRepository.insertIfAbsent(
                eventId,
                TargetDatabase.NEO4J.name(),
                correlationId,
                "UserFollowedEvent"
        );

        assertThat(processedEventsRepository.existsByIdAndTargetDatabase(eventId, TargetDatabase.NEO4J)).isTrue();
        assertThat(processedEventsRepository.existsByCorrelationIdAndTargetDatabase(correlationId, TargetDatabase.NEO4J))
                .isTrue();
    }

    @Test
    void shouldMigrateExistingProcessedEventsRowsWithFlywayBaselineOnMigrate() throws Exception {
        var schemaName = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            try {
                connection.createStatement().execute("CREATE SCHEMA " + schemaName);
                connection.setSchema(schemaName);
                connection.createStatement().execute("""
                        CREATE TABLE processed_events (
                            id UUID PRIMARY KEY,
                            correlation_id UUID NOT NULL,
                            event_type VARCHAR(255) NOT NULL,
                            processed_at TIMESTAMPTZ NOT NULL
                        )
                        """);
                connection.createStatement().execute(String.format("""
                        INSERT INTO processed_events (id, correlation_id, event_type, processed_at)
                        VALUES ('%s', '%s', 'UserRegisteredEvent', NOW())
                        """, eventId, correlationId));

                Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schemaName)
                        .defaultSchema(schemaName)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate();

                var targetDatabase = queryForString(connection,
                        "SELECT target_database FROM processed_events WHERE id = '" + eventId + "'");
                assertThat(targetDatabase).isEqualTo(TargetDatabase.POSTGRES.name());
                assertThat(queryForInteger(connection,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '0' AND success")).isEqualTo(1);
                assertThat(queryForInteger(connection,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success")).isEqualTo(1);
            } finally {
                connection.setSchema("public");
            }
        }

        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
    }

    @Test
    void shouldCreateTheCompositeUniquenessConstraintOnIdAndTargetDatabase() {
        var constraintCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'processed_events_pkey'
                  AND conrelid = 'processed_events'::regclass
                """, Integer.class);

        assertThat(constraintCount).isEqualTo(1);
    }

    @Test
    void shouldCreateOrUpdateTheCorrelationIdIndexToIncludeTargetDatabase() {
        var indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'idx_processed_events_correlation_id_target_database'
                """, String.class);

        assertThat(indexDefinition).contains("(correlation_id, target_database)");
    }

    private int countRowsForIdAndTarget(UUID eventId, TargetDatabase targetDatabase) {
        var rowCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE id = ? AND target_database = ?
                """, Integer.class, eventId, targetDatabase.name());
        return rowCount == null ? 0 : rowCount;
    }

    private int countRowsForCorrelationAndTarget(UUID correlationId, TargetDatabase targetDatabase) {
        var rowCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE correlation_id = ? AND target_database = ?
                """, Integer.class, correlationId, targetDatabase.name());
        return rowCount == null ? 0 : rowCount;
    }

    private String queryForString(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }

    private int queryForInteger(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getInt(1);
        }
    }
}
