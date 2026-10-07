package com.app.postcommandservice.shared.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.usecase.ValidatePostLikeUseCase;
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.exceptions.EventPublisherNotFound;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // Keep the scheduler out of the way: each test drives the relay explicitly
        "outbox.relay.fixed-delay-ms=3600000"
})
class CommandDispatchOutboxRetryIT {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ValidatePostLikeUseCase validatePostLikeUseCase;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldStoreDestinationAtInsertTimeAndMarkProcessedAfterBrokerConfirm() {
        var outboxId = savePendingValidatePostLikeCommand();

        var saved = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(saved.getExchange()).isEqualTo("x.post.commands");
        assertThat(saved.getRoutingKey()).isEqualTo("rk.post.like.validate");

        outboxRelay.relayImmediately(outboxId);

        var processed = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(EventStatus.PROCESSED);
        assertThat(processed.getAttempts()).isEqualTo(1);
        assertThat(processed.getProcessedAt()).isNotNull();
        assertThat(processed.getLockedAt()).isNull();

        // Delivered with a logical __TypeId__ that the class-level listener maps to its own handler
        verify(validatePostLikeUseCase, timeout(10_000)).validateAndCreateLike(any(ValidatePostLikeCommand.class));
    }

    @Test
    void shouldRejectOutboxRowsWithoutPublisherInsteadOfLeavingThemPending() {
        assertThatThrownBy(() -> outboxEventRepository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{}")
                .eventType("UnknownEvent")
                .status(EventStatus.PENDING)
                .build()))
                .isInstanceOf(EventPublisherNotFound.class)
                .hasMessage("No publisher for: UnknownEvent");
    }

    @Test
    void shouldKeepEventPendingWhenBrokerRejectsItAndMoveItToFailedAfterMaxAttempts() {
        var outboxId = savePendingValidatePostLikeCommand();
        jdbcTemplate.update("UPDATE outbox_event SET exchange = 'x.does-not-exist' WHERE id = ?", outboxId);

        outboxRelay.relay();

        var retried = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(retried.getLastError()).isNotBlank();

        jdbcTemplate.update("UPDATE outbox_event SET attempts = ? WHERE id = ?", OutboxRelay.MAX_ATTEMPTS - 1, outboxId);

        outboxRelay.relay();

        var failed = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(OutboxRelay.MAX_ATTEMPTS);
    }

    @Test
    void shouldReclaimEventWhoseRelayDiedMidPublish() {
        var outboxId = savePendingValidatePostLikeCommand();
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
    void shouldNotReclaimEventWhileItsLockIsStillValid() {
        var outboxId = savePendingValidatePostLikeCommand();
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'PROCESSING', attempts = 1, locked_at = now() WHERE id = ?",
                outboxId
        );

        outboxRelay.relay();
        outboxRelay.relayImmediately(outboxId);

        assertThat(outboxEventRepository.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PROCESSING);
    }

    @Test
    void shouldDeleteOnlyOldProcessedEvents() {
        var oldProcessed = savePendingValidatePostLikeCommand();
        var recentProcessed = savePendingValidatePostLikeCommand();
        var pending = savePendingValidatePostLikeCommand();
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PROCESSED', processed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(OutboxRelay.PROCESSED_RETENTION).minusSeconds(60)), oldProcessed);
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PROCESSED', processed_at = now() WHERE id = ?",
                recentProcessed);

        outboxRelay.deleteProcessed();

        assertThat(outboxEventRepository.findById(oldProcessed)).isEmpty();
        assertThat(outboxEventRepository.findById(recentProcessed)).isPresent();
        assertThat(outboxEventRepository.findById(pending)).isPresent();
    }

    private UUID savePendingValidatePostLikeCommand() {
        var command = new ValidatePostLikeCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PostLikeSource.HOME_FEED,
                1
        );
        var outboxId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(command.correlationId())
                .payload(jsonMapper.toJson(command))
                .eventType(ValidatePostLikeCommand.class.getSimpleName())
                .status(EventStatus.PENDING)
                .build());
        return outboxId;
    }
}
