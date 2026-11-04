package com.app.socialservice.shared.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.exceptions.EventPublisherNotFound;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // Keep the scheduler out of the way: each test drives the relay explicitly
        "outbox.relay.fixed-delay-ms=3600000"
})
class OutboxRelayIT {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldStoreDestinationAtInsertTimeAndMarkProcessedAfterBrokerConfirm() {
        var outboxId = savePendingUserFollowedEvent();

        var saved = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(saved.getExchange()).isEqualTo("x.user.events");
        assertThat(saved.getRoutingKey()).isEqualTo("rk.user.follow.created");

        outboxRelay.relayImmediately(outboxId);

        var processed = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(processed.getAttempts()).isEqualTo(1);
        assertThat(processed.getProcessedAt()).isNotNull();
    }

    @Test
    void shouldRejectOutboxRowsWithoutPublisherInsteadOfLeavingThemPending() {
        assertThatThrownBy(() -> outboxEventRepository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{}")
                .eventType("UnknownEvent")
                .status(EventStatus.PENDING)
                .createdAt(Instant.now())
                .build()))
                .isInstanceOf(EventPublisherNotFound.class)
                .hasMessage("No publisher for: UnknownEvent");
    }

    @Test
    void shouldKeepEventPendingWhenBrokerRejectsItAndMoveItToFailedAfterMaxAttempts() {
        var outboxId = savePendingUserFollowedEvent();
        jdbcTemplate.update("UPDATE outbox_event SET exchange = 'x.does-not-exist' WHERE id = ?", outboxId);

        outboxRelay.relay();

        var retried = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(retried.getLastError()).isNotBlank();

        jdbcTemplate.update("UPDATE outbox_event SET attempts = ? WHERE id = ?", OutboxRelay.MAX_ATTEMPTS - 1, outboxId);

        outboxRelay.relay();

        assertThat(outboxEventRepository.findById(outboxId).orElseThrow().getStatus()).isEqualTo(EventStatus.FAILED);
    }

    @Test
    void shouldReclaimEventWhoseRelayDiedMidPublish() {
        var outboxId = savePendingUserFollowedEvent();
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PROCESSING', attempts = 1, locked_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(OutboxRelay.LOCK_TIMEOUT).minusSeconds(1)),
                outboxId
        );

        outboxRelay.relay();

        var processed = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(processed.getAttempts()).isEqualTo(2);
    }

    @Test
    void shouldIgnoreAFailureReportedByARelayWhoseClaimExpired() {
        var outboxId = savePendingUserFollowedEvent();
        // Relay A claimed the row (attempt 1) and stalled; relay B reclaimed it (attempt 2)
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PROCESSING', attempts = 2, locked_at = now() WHERE id = ?",
                outboxId);

        // A's late failure must not release the claim B still holds
        assertThat(outboxEventRepository.markFailedAttempt(outboxId, 1, EventStatus.PENDING.name(), "late failure"))
                .isZero();
        var stillClaimed = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(stillClaimed.getStatus()).isEqualTo(EventStatus.PROCESSING);
        assertThat(stillClaimed.getLockedAt()).isNotNull();

        // Nor undo B's confirmed publish, whatever attempt it reports
        outboxEventRepository.markProcessed(outboxId);
        assertThat(outboxEventRepository.markFailedAttempt(outboxId, 1, EventStatus.PENDING.name(), "late failure"))
                .isZero();
        assertThat(outboxEventRepository.markFailedAttempt(outboxId, 2, EventStatus.FAILED.name(), "late failure"))
                .isZero();
        assertThat(outboxEventRepository.findById(outboxId).orElseThrow().getStatus()).isEqualTo(EventStatus.PROCESSED);
    }

    private UUID savePendingUserFollowedEvent() {
        var event = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(UUID.randomUUID())
                .followedUserId(UUID.randomUUID())
                .build();
        var outboxId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(event.correlationId())
                .payload(jsonMapper.toJson(event))
                .eventType(UserFollowedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .createdAt(event.occurredAt())
                .build());
        return outboxId;
    }
}
