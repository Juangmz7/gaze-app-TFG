package com.app.socialservice.shared.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;

/**
 * Generic outbox relay: claims rows one at a time, publishes them with
 * publisher confirms and only then marks them PROCESSED (at-least-once).
 * Consumers must deduplicate by message id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    static final int BATCH_SIZE = 100;
    static final int MAX_ATTEMPTS = 10;
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    static final Duration PROCESSED_RETENTION = Duration.ofDays(7);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:5000}")
    public void relay() {
        if (!brokerAvailable()) {
            return;
        }

        for (int i = 0; i < BATCH_SIZE; i++) {
            var claimed = outboxRepository.claimNext(Instant.now().minus(LOCK_TIMEOUT));
            if (claimed.isEmpty()) {
                return;
            }
            if (!publish(claimed.get())) {
                return; // stop the batch to preserve ordering
            }
        }
    }

    /** Publishes a just-committed row right away; the scheduled relay covers any failure. */
    public void relayImmediately(UUID outboxEventId) {
        outboxRepository.claimIfPending(outboxEventId).ifPresent(this::publish);
    }

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
    public void deleteProcessed() {
        var deleted = outboxRepository.deleteProcessedBefore(Instant.now().minus(PROCESSED_RETENTION));
        if (deleted > 0) {
            log.info("Deleted {} processed outbox events older than {}", deleted, PROCESSED_RETENTION);
        }
    }

    /*
     * Claiming consumes an attempt, so nothing is claimed while the broker is unreachable:
     * an outage longer than MAX_ATTEMPTS cycles must not move events to FAILED.
     */
    private boolean brokerAvailable() {
        try {
            rabbitTemplate.execute(channel -> null);
            return true;
        } catch (AmqpException ex) {
            log.warn("Outbox relay skipped, broker unavailable: {}", ex.getMessage());
            return false;
        }
    }

    private boolean publish(OutboxEvent event) {
        try {
            publishAndConfirm(event);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            markFailedAttempt(event, ex);
            return false;
        }

        outboxRepository.markProcessed(event.getId());
        return true;
    }

    private void markFailedAttempt(OutboxEvent event, Exception ex) {
        var exhausted = event.getAttempts() >= MAX_ATTEMPTS;
        var nextStatus = exhausted ? EventStatus.FAILED : EventStatus.PENDING;

        if (exhausted) {
            log.error("Outbox event moved to FAILED after {} attempts: id={} type={}",
                    event.getAttempts(), event.getId(), event.getEventType(), ex);
        } else {
            log.warn("Outbox publish failed: id={} type={} attempt={}",
                    event.getId(), event.getEventType(), event.getAttempts(), ex);
        }

        outboxRepository.markFailedAttempt(event.getId(), nextStatus.name(), truncate(String.valueOf(ex.getMessage())));
    }

    private void publishAndConfirm(OutboxEvent event) throws Exception {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(event.getId().toString());
        props.setCorrelationId(event.getCorrelationId().toString());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // Logical type id (simple class name): lets class-level @RabbitHandler listeners
        // pick the handler through the converter's id -> class mapping.
        props.setType(event.getEventType());
        props.setHeader(DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, event.getEventType());
        var message = new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);

        var correlation = new CorrelationData(event.getId().toString());
        rabbitTemplate.send(event.getExchange(), event.getRoutingKey(), message, correlation);

        var confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("Broker nack: " + confirm.reason());
        }

        // The broker accepted the message but no queue is bound to the routing key yet:
        // nothing can consume it, so retrying would only stall the outbox.
        var returned = correlation.getReturned();
        if (returned != null) {
            log.warn("Outbox event was unroutable and is marked as processed: id={} type={} exchange={} routingKey={} reply={}",
                    event.getId(), event.getEventType(), event.getExchange(), event.getRoutingKey(),
                    returned.getReplyText());
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
