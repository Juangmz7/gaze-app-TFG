package com.app.socialservice.shared.infrastructure.rabbitmq.config;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void shouldDeclareBlockQueueAndDlq() {
        var properties = new RabbitMQProperties();
        properties.getQueue().getAuth().setRegister("q.social-service.auth.register");
        properties.getQueue().getAuth().setUpdate("q.social-service.auth.update");
        properties.getQueue().getAuth().setDelete("q.social-service.auth.delete");
        properties.getQueue().getUser().setRegister("q.social-service.user.register");
        properties.getQueue().getUser().setDeleted("q.social-service.user.deleted");
        properties.getExchange().getAuth().setEvents("x.auth.events");
        properties.getExchange().getUser().setEvents("x.user.events");
        properties.getRk().getAuth().getUser().setRegister("auth.register");
        properties.getRk().getAuth().getUser().setUpdate("auth.update");
        properties.getRk().getAuth().getUser().setDelete("auth.delete");
        properties.getRk().getUser().getRegister().setCreated("rk.user.registered");
        properties.getRk().getUser().setDeleted("rk.user.deleted");
        properties.getRk().getUser().getBlock().setCreated("rk.user.block.created");

        var config = new RabbitMQConfig(properties);

        Declarables declarables = config.socialServiceSchema();

        var queuesByName = declarables.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, queue -> queue));
        var bindings = declarables.getDeclarablesByType(Binding.class);

        assertThat(queuesByName).containsKeys(
                "q.social-service.user.block.created",
                "q.social-service.user.block.created.dlq"
        );
        assertThat(queuesByName.get("q.social-service.user.block.created").getArguments())
                .containsAllEntriesOf(Map.of(
                        "x-dead-letter-exchange", "x.user.events.dlx",
                        "x-dead-letter-routing-key", "rk.user.block.created.fall-back"
                ));
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.block.created");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.block.created");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.block.created.dlq");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.block.created.fall-back");
        });
    }
}
