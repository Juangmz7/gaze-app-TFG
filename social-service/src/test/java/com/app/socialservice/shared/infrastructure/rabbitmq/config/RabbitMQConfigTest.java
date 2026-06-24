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
    void shouldDeclareFollowAndBlockQueuesAndDlqs() {
        var properties = new RabbitMQProperties();
        properties.getQueue().getAuth().setRegister("q.social-service.auth.register");
        properties.getQueue().getAuth().setUpdate("q.social-service.auth.update");
        properties.getQueue().getAuth().setDelete("q.social-service.auth.delete");
        properties.getQueue().getUser().setRegister("q.social-service.user.register");
        properties.getQueue().getUser().setDeleted("q.social-service.user.deleted");
        properties.getQueue().getUser().getFollow().setCreated("q.social-service.user.follow.created");
        properties.getQueue().getUser().getFollow().setDeleted("q.social-service.user.follow.deleted");
        properties.getExchange().getAuth().setEvents("x.auth.events");
        properties.getExchange().getUser().setEvents("x.user.events");
        properties.getRk().getAuth().getUser().setRegister("auth.register");
        properties.getRk().getAuth().getUser().setUpdate("auth.update");
        properties.getRk().getAuth().getUser().setDelete("auth.delete");
        properties.getRk().getUser().getRegister().setCreated("rk.user.registered");
        properties.getRk().getUser().setDeleted("rk.user.deleted");
        properties.getRk().getUser().getFollow().setCreated("rk.user.follow.created");
        properties.getRk().getUser().getFollow().setDeleted("rk.user.follow.deleted");
        properties.getQueue().getUser().getBlock().setCreated("q.social-service.user.block.created");
        properties.getRk().getUser().getBlock().setCreated("rk.user.block.created");

        var config = new RabbitMQConfig(properties);

        Declarables declarables = config.socialServiceSchema();

        var queuesByName = declarables.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, queue -> queue));
        var bindings = declarables.getDeclarablesByType(Binding.class);

        assertThat(queuesByName).containsKeys(
                "q.social-service.user.follow.created",
                "q.social-service.user.follow.created.dlq",
                "q.social-service.user.follow.deleted",
                "q.social-service.user.follow.deleted.dlq",
                "q.social-service.user.block.created",
                "q.social-service.user.block.created.dlq"
        );
        assertThat(queuesByName.get("q.social-service.user.follow.created").getArguments())
                .containsAllEntriesOf(Map.of(
                        "x-dead-letter-exchange", "x.user.events.dlx",
                        "x-dead-letter-routing-key", "rk.user.follow.created.fall-back"
                ));
        assertThat(queuesByName.get("q.social-service.user.follow.deleted").getArguments())
                .containsAllEntriesOf(Map.of(
                        "x-dead-letter-exchange", "x.user.events.dlx",
                        "x-dead-letter-routing-key", "rk.user.follow.deleted.fall-back"
                ));
        assertThat(queuesByName.get("q.social-service.user.block.created").getArguments())
                .containsAllEntriesOf(Map.of(
                        "x-dead-letter-exchange", "x.user.events.dlx",
                        "x-dead-letter-routing-key", "rk.user.block.created.fall-back"
                ));
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.follow.created");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.follow.created");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.follow.created.dlq");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.follow.created.fall-back");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.follow.deleted");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.follow.deleted");
        });
        assertThat(bindings).anySatisfy(binding -> {
            assertThat(binding.getDestination()).isEqualTo("q.social-service.user.follow.deleted.dlq");
            assertThat(binding.getRoutingKey()).isEqualTo("rk.user.follow.deleted.fall-back");
        });
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
