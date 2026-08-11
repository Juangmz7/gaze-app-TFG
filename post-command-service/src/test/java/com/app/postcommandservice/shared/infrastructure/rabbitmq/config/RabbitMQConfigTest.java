package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void shouldDeclarePropertyDrivenQueuesDlqsExchangesAndBindings() {
        var properties = new RabbitMQProperties();
    }
}
