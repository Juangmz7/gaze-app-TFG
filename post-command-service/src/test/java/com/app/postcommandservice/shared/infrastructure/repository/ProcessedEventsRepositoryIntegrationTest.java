package com.app.postcommandservice.shared.infrastructure.repository;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.app.postcommandservice.TestcontainersConfiguration;

import java.util.UUID;

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



    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
    }

    @Test
    void shouldInsertTwoProcessedEventsRowsWithTheSameEventId() {
        var eventId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                UUID.randomUUID(),
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                UUID.randomUUID(),
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(0);
        assertThat(processedEventsRepository.findById(eventId)).isPresent();
        assertThat(processedEventsRepository.findById(eventId)).isPresent();
    }

    @Test
    void shouldInsertTwoProcessedEventsRowsWithTheSameCorrelationId() {
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                UUID.randomUUID(),
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                UUID.randomUUID(),
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(1);
        assertThat(processedEventsRepository.existsByCorrelationId(correlationId)).isTrue();
        assertThat(processedEventsRepository.existsByCorrelationId(correlationId)).isTrue();
    }

    @Test
    void shouldRejectOrIgnoreDuplicateProcessedEvents() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                eventId,
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isZero();
        assertThat(countRowsForIdAndTarget(eventId)).isEqualTo(1);
    }

    @Test
    void shouldAllowMultipleEventsWithTheSameCorrelationId() {
        var firstEventId = UUID.randomUUID();
        var secondEventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var firstInsert = processedEventsRepository.insertIfAbsent(
                firstEventId,
                correlationId,
                "UserRegisteredEvent"
        );
        var secondInsert = processedEventsRepository.insertIfAbsent(
                secondEventId,
                correlationId,
                "UserRegisteredEvent"
        );

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(1);
        assertThat(countRowsForCorrelationAndTarget(correlationId)).isEqualTo(2);
    }

    @Test
    void shouldCreateOrUpdateTheCorrelationIdIndexToIncludeTargetDatabase() {
        var indexDefinition = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'idx_processed_events_correlation_id'
                """, String.class);

        assertThat(indexDefinition).contains("(correlation_id)");
    }

    private int countRowsForIdAndTarget(UUID eventId) {
        var rowCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE id = ?
                """, Integer.class, eventId);
        return rowCount == null ? 0 : rowCount;
    }

    private int countRowsForCorrelationAndTarget(UUID correlationId) {
        var rowCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM processed_events
                WHERE correlation_id = ?
                """, Integer.class, correlationId);
        return rowCount == null ? 0 : rowCount;
    }
}
