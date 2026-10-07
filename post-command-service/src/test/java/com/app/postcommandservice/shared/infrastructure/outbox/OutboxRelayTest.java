package com.app.postcommandservice.shared.infrastructure.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OutboxRelay outboxRelay;

    @Test
    void shouldPublishPersistentJsonMessageAndMarkProcessedOnlyAfterBrokerAck() {
        var event = claimedEvent(1);
        when(outboxRepository.claimNext(any())).thenReturn(Optional.of(event), Optional.empty());
        brokerReplies(true, null, false);

        outboxRelay.relay();

        var messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("x.post.events"), eq("rk.post.created"), messageCaptor.capture(), any(CorrelationData.class));
        var props = messageCaptor.getValue().getMessageProperties();
        assertThat(new String(messageCaptor.getValue().getBody())).isEqualTo("{\"id\":\"1\"}");
        assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(props.getMessageId()).isEqualTo(event.getId().toString());
        assertThat(props.getCorrelationId()).isEqualTo(event.getCorrelationId().toString());
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        verify(outboxRepository).markProcessed(event.getId());
    }

    @Test
    void shouldReleaseEventAsPendingAndStopTheBatchWhenBrokerNacks() {
        var event = claimedEvent(1);
        when(outboxRepository.claimNext(any())).thenReturn(Optional.of(event));
        brokerReplies(false, "nack-reason", false);

        outboxRelay.relay();

        verify(outboxRepository, times(1)).claimNext(any());
        verify(outboxRepository).markFailedAttempt(eq(event.getId()), eq(EventStatus.PENDING.name()), anyString());
        verify(outboxRepository, never()).markProcessed(any());
    }

    @Test
    void shouldMoveEventToFailedOnceAttemptsAreExhausted() {
        var event = claimedEvent(OutboxRelay.MAX_ATTEMPTS);
        when(outboxRepository.claimNext(any())).thenReturn(Optional.of(event));
        brokerReplies(false, "nack-reason", false);

        outboxRelay.relay();

        verify(outboxRepository).markFailedAttempt(event.getId(), EventStatus.FAILED.name(), "Broker nack: nack-reason");
    }

    @Test
    void shouldMarkUnroutableEventAsProcessedBecauseTheBrokerAcceptedIt() {
        var event = claimedEvent(1);
        when(outboxRepository.claimNext(any())).thenReturn(Optional.of(event), Optional.empty());
        brokerReplies(true, null, true);

        outboxRelay.relay();

        verify(outboxRepository).markProcessed(event.getId());
        verify(outboxRepository, never()).markFailedAttempt(any(), anyString(), anyString());
    }

    @Test
    void shouldReleaseEventWhenSendThrows() {
        var event = claimedEvent(1);
        when(outboxRepository.claimNext(any())).thenReturn(Optional.of(event));
        doAnswer(invocation -> {
            throw new IllegalStateException("connection refused");
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        outboxRelay.relay();

        verify(outboxRepository).markFailedAttempt(event.getId(), EventStatus.PENDING.name(), "connection refused");
    }

    @Test
    void shouldPublishImmediatelyOnlyWhenTheRowCanStillBeClaimed() {
        var event = claimedEvent(1);
        when(outboxRepository.claimIfPending(event.getId())).thenReturn(Optional.of(event));
        brokerReplies(true, null, false);

        outboxRelay.relayImmediately(event.getId());

        verify(outboxRepository).markProcessed(event.getId());
    }

    @Test
    void shouldSkipImmediatePublishWhenRowWasAlreadyClaimed() {
        var id = UUID.randomUUID();
        when(outboxRepository.claimIfPending(id)).thenReturn(Optional.empty());

        outboxRelay.relayImmediately(id);

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void brokerReplies(boolean ack, String reason, boolean returned) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
                        invocation.getArgument(0), invocation.getArgument(1)));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static OutboxEvent claimedEvent(int attempts) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{\"id\":\"1\"}")
                .eventType("PostCreatedEvent")
                .exchange("x.post.events")
                .routingKey("rk.post.created")
                .status(EventStatus.PROCESSING)
                .attempts(attempts)
                .createdAt(Instant.now())
                .lockedAt(Instant.now())
                .build();
    }
}
