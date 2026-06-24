package com.app.socialservice.shared.infrastructure.outbox;

import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.rabbitmq.publisher.EventPublisher;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRetryWorker {

    private final OutboxEventRepository outboxRepository;
    private final List<EventPublisher> publishers;

    @Transactional
    @Scheduled(fixedDelayString = "${outbox.retry.delay-ms:300000}")
    public void retry() {
        log.debug("Worker woke up for retry PENDING events...");
        
        var pendingEvents = outboxRepository.findPendingForProcessing(100);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Fetched a batch of {} PENDING events. Starting recovery...", pendingEvents.size());

        pendingEvents.forEach(outboxEvent -> {
            publishers.stream()
                    .filter(p -> p.supports(outboxEvent.getEventType()))
                    .findFirst()
                    .ifPresent(publisher -> {
                        try {
                            publisher.publish(outboxEvent);
                            outboxRepository.markAsProcessedIfPending(outboxEvent.getId());
                        } catch (Exception e) {
                            log.warn("Retry failed for event {}", outboxEvent.getCorrelationId());
                        }
                    });
        });
    }
}