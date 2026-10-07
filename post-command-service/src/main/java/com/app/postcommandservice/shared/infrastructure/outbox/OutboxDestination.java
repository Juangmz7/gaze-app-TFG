package com.app.postcommandservice.shared.infrastructure.outbox;

/**
 * Exchange and routing key an outbox row is published to. Resolved once, at
 * insert time, and stored on the row so the relay never needs per-type logic.
 */
public record OutboxDestination(String exchange, String routingKey) {
}
