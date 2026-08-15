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
import com.app.postcommandservice.like.domain.model.PostLikeSource;
import com.app.postcommandservice.like.infrastructure.rabbitmq.ValidatePostLikeCommandPublisher;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CommandDispatchOutboxRetryIT {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ImmediateOutboxSender immediateOutboxSender;

    @Autowired
    private OutboxRetryWorker outboxRetryWorker;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ValidatePostLikeCommandPublisher validatePostLikeCommandPublisher;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldKeepCommandOutboxPendingWhenImmediateSendFailsAndAllowRetryWorkerToRepublish() {
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

        when(validatePostLikeCommandPublisher.supports(ValidatePostLikeCommand.class.getSimpleName())).thenReturn(true);
        doThrow(new RuntimeException("RabbitMQ unavailable"))
                .doNothing()
                .when(validatePostLikeCommandPublisher)
                .publish(any(OutboxEvent.class));

        assertThatThrownBy(() -> immediateOutboxSender.sendMessage(new OutboxEventCreatedDomainEvent(outboxId)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("RabbitMQ unavailable");

        assertThat(outboxEventRepository.findById(outboxId))
                .get()
                .extracting(OutboxEvent::getStatus)
                .isEqualTo(EventStatus.PENDING);

        jdbcTemplate.update(
                "UPDATE outbox_event SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                outboxId
        );

        outboxRetryWorker.retry();

        assertThat(outboxEventRepository.findById(outboxId))
                .get()
                .extracting(OutboxEvent::getStatus)
                .isEqualTo(EventStatus.PROCESSED);
        verify(validatePostLikeCommandPublisher, times(2)).publish(any(OutboxEvent.class));
    }
}
