package com.app.socialservice.shared.infrastructure.outbox;

import com.app.socialservice.shared.domain.events.DomainEvent;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.exceptions.EventPublisherNotFound;
import com.app.socialservice.shared.infrastructure.exceptions.OutboxEventNotFoundException;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ImmediateOutboxSender {

    private final OutboxEventRepository outboxRepository;
    private final List<EventPublisher> publishers;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendMessage(DomainEvent event) {
        var outboxEvent = outboxRepository
                .findById(event.id())
                .orElseThrow(() -> new OutboxEventNotFoundException("Outbox event not found"));

        var publisher = getEventPublisher(outboxEvent);

        try {
            publisher.publish(outboxEvent);
            outboxEvent.setStatus(EventStatus.PROCESSED);
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.warn("Message send failed for event {}, worker will retry", outboxEvent.getCorrelationId());
        }
    }

    private EventPublisher getEventPublisher(OutboxEvent event) {
        return publishers.stream()
                .filter(p -> p.supports(event.getEventType()))
                .findFirst()
                .orElseThrow(() -> new EventPublisherNotFound("No publisher for: " + event.getEventType()));
    }
}