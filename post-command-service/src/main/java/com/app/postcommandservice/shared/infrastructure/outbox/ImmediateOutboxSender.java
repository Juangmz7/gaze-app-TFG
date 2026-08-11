package com.app.postcommandservice.shared.infrastructure.outbox;


import com.app.postcommandservice.shared.domain.events.DomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.exceptions.EventPublisherNotFound;
import com.app.postcommandservice.shared.infrastructure.exceptions.OutboxEventNotFoundException;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ImmediateOutboxSender {

    private final OutboxEventRepository outboxRepository;
    private final List<EventPublisher> publishers;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendMessage(DomainEvent event) {
        int claimed = outboxRepository.markAsProcessedIfPending(event.id());
        if (claimed == 0) {
            log.debug("Outbox event {} already processed or not pending", event.id());
            return;
        }

        var outboxEvent = outboxRepository
                .findById(event.id())
                .orElseThrow(() -> new OutboxEventNotFoundException("Outbox event not found"));

        var publisher = getEventPublisher(outboxEvent);

        try {
            publisher.publish(outboxEvent);
        } catch (Exception e) {
            log.warn("Message send failed for event {}, worker will retry", outboxEvent.getCorrelationId());
            throw e; // throw so the transaction rolls back and the event stays PENDING
        }
    }

    private EventPublisher getEventPublisher(OutboxEvent event) {
        return publishers.stream()
                .filter(p -> p.supports(event.getEventType()))
                .findFirst()
                .orElseThrow(() -> new EventPublisherNotFound("No publisher for: " + event.getEventType()));
    }
}