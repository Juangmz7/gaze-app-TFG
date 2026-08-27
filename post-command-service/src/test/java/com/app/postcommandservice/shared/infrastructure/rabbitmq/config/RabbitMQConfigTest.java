package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    @Test
    void shouldDeclarePropertyDrivenQueuesDlqsExchangesAndBindings() {
        var properties = new RabbitMQProperties();
        properties.getQueue().setPost("q.post-command-service.post");
        properties.getQueue().getUser().setFast("q.post-command-service.user.fast");
        properties.getQueue().getUser().setSlow("q.post-command-service.user.slow");
        properties.getExchange().getPost().setCommands("x.post.commands");
        properties.getExchange().getPost().setEvents("x.post.events");
        properties.getExchange().getUser().setEvents("x.user.events");
        properties.getRk().getPost().getShare().getCreate().setValidate("rk.post.share.create.validate");
        properties.getRk().getPost().getShare().getDelete().setValidate("rk.post.share.delete.validate");
        properties.getRk().getPost().getView().setProcess("rk.post.view.process");
        properties.getRk().getPost().getLike().setValidate("rk.post.like.validate");
        properties.getRk().getPost().getUnlike().setValidate("rk.post.unlike.validate");
        properties.getRk().getPost().getComment().getLike().setValidate("rk.post.comment.like.validate");
        properties.getRk().getUser().getBlock().setCreated("rk.user.block.created");
        properties.getRk().getUser().getBlock().setDeleted("rk.user.block.deleted");
        properties.getRk().getUser().setRegistered("rk.user.registered");
        properties.getRk().getUser().setUpdated("rk.user.updated");
        properties.getRk().getUser().setDeleted("rk.user.deleted");

        var config = new RabbitMQConfig(properties);

        var schema = config.postCommandServiceSchema();
        var bindings = schema.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .collect(Collectors.toList());
        var exchanges = schema.getDeclarables().stream()
                .filter(Exchange.class::isInstance)
                .map(Exchange.class::cast)
                .collect(Collectors.toMap(Exchange::getName, exchange -> exchange));
        var queues = schema.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .collect(Collectors.toMap(Queue::getName, queue -> queue));

        assertThat(exchanges).containsKeys("x.post.commands", "x.post.commands.dlx", "x.post.events", "x.user.events",
                "x.user.events.dlx");
        assertThat(queues).containsKeys("q.post-command-service.post", "q.post-command-service.post.dlq");
        assertThat(bindings).anyMatch(binding -> "q.post-command-service.post".equals(binding.getDestination())
                && "rk.post.unlike.validate".equals(binding.getRoutingKey()));
        assertThat(bindings).anyMatch(binding -> "q.post-command-service.post".equals(binding.getDestination())
                && "rk.post.comment.like.validate".equals(binding.getRoutingKey()));
        assertThat(bindings).anyMatch(binding -> "q.post-command-service.post.dlq".equals(binding.getDestination())
                && "q.post-command-service.post.fall-back".equals(binding.getRoutingKey()));
    }
}
