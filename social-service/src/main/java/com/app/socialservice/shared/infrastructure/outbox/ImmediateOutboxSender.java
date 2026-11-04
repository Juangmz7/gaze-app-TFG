package com.app.socialservice.shared.infrastructure.outbox;


import com.app.socialservice.shared.domain.events.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Low-latency path: once the business transaction (state + outbox row) has
 * committed, try to publish that row immediately. It goes through the same
 * claim/confirm logic as the scheduled relay, which picks it up if this fails.
 */
@Component
@RequiredArgsConstructor
public class ImmediateOutboxSender {

    private final OutboxRelay outboxRelay;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendMessage(DomainEvent event) {
        outboxRelay.relayImmediately(event.id());
    }
}
