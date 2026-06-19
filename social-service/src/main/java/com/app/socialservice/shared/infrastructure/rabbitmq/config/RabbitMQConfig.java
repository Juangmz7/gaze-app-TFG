package com.app.socialservice.shared.infrastructure.rabbitmq.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RabbitMQProperties.class)
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties props;

    @Bean
    public Declarables socialServiceSchema() {
        // Queue
        Queue userRegisterFromAuthQueue = QueueBuilder
                .durable(props.getQueue().getAuth().getRegister())
                .withArgument(
                        "x-dead-letter-exchange",
                        props.getExchange().getAuth().getEvents() + ".dlx")
                .withArgument(
                        "x-dead-letter-routing-key",
                        props.getRk().getAuth().getUser().getRegister() + ".fall-back")
                .build();

        Queue userUpdateFromAuthQueue = QueueBuilder
                .durable(props.getQueue().getAuth().getUpdate())
                .withArgument(
                        "x-dead-letter-exchange",
                        props.getExchange().getAuth().getEvents() + ".dlx")
                .withArgument(
                        "x-dead-letter-routing-key",
                        props.getRk().getAuth().getUser().getUpdate() + ".fall-back")
                .build();

        Queue userDeleteFromAuthQueue = QueueBuilder
                .durable(props.getQueue().getAuth().getDelete())
                .withArgument(
                        "x-dead-letter-exchange",
                        props.getExchange().getAuth().getEvents() + ".dlx")
                .withArgument(
                        "x-dead-letter-routing-key",
                        props.getRk().getAuth().getUser().getDelete() + ".fall-back")
                .build();

        Queue userRegisteredQueue = QueueBuilder
                .durable(props.getQueue().getUser().getRegister())
                .withArgument(
                        "x-dead-letter-exchange",
                        props.getExchange().getUser().getEvents() + ".dlx")
                .withArgument(
                        "x-dead-letter-routing-key",
                        props.getRk().getUser().getRegister().getCreated() + ".fall-back")
                .build();

        Queue userDeletedQueue = QueueBuilder
                .durable(props.getQueue().getUser().getDeleted())
                .withArgument(
                        "x-dead-letter-exchange",
                        props.getExchange().getUser().getEvents() + ".dlx")
                .withArgument(
                        "x-dead-letter-routing-key",
                        props.getRk().getUser().getDeleted() + ".fall-back")
                .build();

        // DLQ
        Queue userRegisterFromAuthDlq = QueueBuilder
                .durable(props.getQueue().getAuth().getRegister() + ".dlq")
                .build();

        Queue userUpdateFromAuthDlq = QueueBuilder
                .durable(props.getQueue().getAuth().getUpdate() + ".dlq")
                .build();

        Queue userDeleteFromAuthDlq = QueueBuilder
                .durable(props.getQueue().getAuth().getDelete() + ".dlq")
                .build();

        Queue userRegisteredDlq = QueueBuilder
                .durable(props.getQueue().getUser().getRegister() + ".dlq")
                .build();

        Queue userDeletedDlq = QueueBuilder
                .durable(props.getQueue().getUser().getDeleted() + ".dlq")
                .build();

        // Exchanges
        var authEventsExchange = new TopicExchange(
                props.getExchange().getAuth().getEvents());

        var authEventsDlx = new DirectExchange(
                props.getExchange().getAuth().getEvents() + ".dlx");

        var userEventsExchange = new TopicExchange(
                props.getExchange().getUser().getEvents());
        
        var userEventsDlx = new DirectExchange(
                props.getExchange().getUser().getEvents() + ".dlx");

        return new Declarables(
                // Exchanges
                authEventsExchange,
                authEventsDlx,
                userEventsExchange,
                userEventsDlx,

                // Queues
                userRegisterFromAuthQueue,
                userUpdateFromAuthQueue,
                userDeleteFromAuthQueue,
                userRegisteredQueue,
                userDeletedQueue,
                userRegisterFromAuthDlq,
                userUpdateFromAuthDlq,
                userDeleteFromAuthDlq,
                userRegisteredDlq,
                userDeletedDlq,

                // Bindings
                BindingBuilder
                        .bind(userRegisterFromAuthQueue)
                        .to(authEventsExchange)
                        .with(props.getRk().getAuth().getUser().getRegister()),
                BindingBuilder
                        .bind(userUpdateFromAuthQueue)
                        .to(authEventsExchange)
                        .with(props.getRk().getAuth().getUser().getUpdate()),
                BindingBuilder
                        .bind(userDeleteFromAuthQueue)
                        .to(authEventsExchange)
                        .with(props.getRk().getAuth().getUser().getDelete()),
                BindingBuilder
                        .bind(userRegisteredQueue)
                        .to(userEventsExchange)
                        .with(props.getRk().getUser().getRegister().getCreated()),
                BindingBuilder
                        .bind(userDeletedQueue)
                        .to(userEventsExchange)
                        .with(props.getRk().getUser().getDeleted()),

                BindingBuilder
                        .bind(userRegisterFromAuthDlq)
                        .to(authEventsDlx)
                        .with(props.getRk().getAuth().getUser().getRegister() + ".fall-back"),
                BindingBuilder
                        .bind(userUpdateFromAuthDlq)
                        .to(authEventsDlx)
                        .with(props.getRk().getAuth().getUser().getUpdate() + ".fall-back"),
                BindingBuilder
                        .bind(userDeleteFromAuthDlq)
                        .to(authEventsDlx)
                        .with(props.getRk().getAuth().getUser().getDelete() + ".fall-back"),
                BindingBuilder
                        .bind(userRegisteredDlq)
                        .to(userEventsDlx)
                        .with(props.getRk().getUser().getRegister().getCreated() + ".fall-back"),
                BindingBuilder
                        .bind(userDeletedDlq)
                        .to(userEventsDlx)
                        .with(props.getRk().getUser().getDeleted() + ".fall-back")
        );
    }

    // Listener Factory

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(retryInterceptor());
        return factory;
    }

    @Bean
    public org.aopalliance.aop.Advice retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(3)
                .backOffOptions(2000, 2.0, 100000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    // Infrastructure

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            MessageConverter messageConverter,
            CachingConnectionFactory cachingConnectionFactory) {

        var template = new RabbitTemplate(cachingConnectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}