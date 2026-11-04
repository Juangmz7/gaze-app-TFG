package com.app.postcommandservice.post.infrastructure.rabbitmq;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostMediaUploadedEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMQProperties rabbitMQProperties;

    @Mock
    private RabbitMQProperties.Exchanges exchanges;

    @Mock
    private RabbitMQProperties.Exchanges.PostExchange postExchange;

    @Mock
    private RabbitMQProperties.RoutingKeys routingKeys;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk postRk;

    @Mock
    private RabbitMQProperties.RoutingKeys.PostRk.MediaRk mediaRk;

    @InjectMocks
    private PostMediaUploadedEventPublisher publisher;

    @Test
    void shouldPublishEventDirectlyToTheConfiguredExchangeAndRoutingKey() {
        var event = PostMediaUploadedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(UUID.randomUUID())
                .media(List.of(PostMediaUploadedMediaPayload.builder()
                        .id(UUID.randomUUID())
                        .url("https://cdn/image.jpg")
                        .thumbnailUrl("https://cdn/image.jpg")
                        .mediaType(MediaType.IMAGE)
                        .order(1)
                        .build()))
                .build();

        when(rabbitMQProperties.getExchange()).thenReturn(exchanges);
        when(exchanges.getPost()).thenReturn(postExchange);
        when(postExchange.getEvents()).thenReturn("x.post.events");
        when(rabbitMQProperties.getRk()).thenReturn(routingKeys);
        when(routingKeys.getPost()).thenReturn(postRk);
        when(postRk.getMedia()).thenReturn(mediaRk);
        when(mediaRk.getUploaded()).thenReturn("rk.post.media.uploaded");

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend("x.post.events", "rk.post.media.uploaded", event);
    }
}
