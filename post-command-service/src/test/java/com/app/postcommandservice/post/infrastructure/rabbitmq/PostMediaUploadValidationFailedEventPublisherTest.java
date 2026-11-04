package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestination;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostMediaUploadValidationFailedEventPublisherTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RabbitMQProperties rabbitMQProperties;

    @InjectMocks
    private PostMediaUploadValidationFailedEventPublisher publisher;

    @Test
    void shouldSupportOnlyItsOwnEventType() {
        assertThat(publisher.supports(PostMediaUploadValidationFailedEvent.class.getSimpleName())).isTrue();
        assertThat(publisher.supports("PostCreatedEvent")).isFalse();
    }

    @Test
    void shouldResolvePostMediaUploadValidationFailedEventDestinationFromConfiguredExchangeAndRoutingKey() {
        when(rabbitMQProperties.getExchange().getPost().getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk().getPost().getMedia().getValidation().getFailed()).thenReturn("rk.post.media.validation.failed");
        var outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .payload("{}")
                .eventType(PostMediaUploadValidationFailedEvent.class.getSimpleName())
                .status(EventStatus.PENDING)
                .build();

        assertThat(publisher.destination(outboxEvent))
                .isEqualTo(new OutboxDestination("x.post.events", "rk.post.media.validation.failed"));
    }
}
