package com.app.socialservice.shared.infrastructure.rabbitmq.config;

import lombok.RequiredArgsConstructor;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
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
import org.springframework.core.retry.RetryPolicy;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RabbitMQProperties.class)
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties props;

    @Bean
    public Declarables socialServiceSchema() {
        var authEventsExchangeName = props.getExchange().getAuth().getEvents();
        var userEventsExchangeName = props.getExchange().getUser().getEvents();
        var postEventsExchangeName = props.getExchange().getPost().getEvents();

        var followCreatedQueueName = props.getQueue().getUser().getFollow().getCreated();
        var followDeletedQueueName = props.getQueue().getUser().getFollow().getDeleted();

        var followCreatedRoutingKey = props.getRk().getUser().getFollow().getCreated();
        var followDeletedRoutingKey = props.getRk().getUser().getFollow().getDeleted();

        var blockCreatedQueueName = props.getQueue().getUser().getBlock().getCreated();
        var blockCreatedRoutingKey = props.getRk().getUser().getBlock().getCreated();

        var postCreatedQueueName = props.getQueue().getPost().getCreated();
        var postDeletedQueueName = props.getQueue().getPost().getDeleted();

        var postCreatedRoutingKey = props.getRk().getPost().getCreated();
        var postDeletedRoutingKey = props.getRk().getPost().getDeleted();

        Queue userRegisterFromAuthQueue = buildQueue(
                props.getQueue().getAuth().getRegister(),
                authEventsExchangeName,
                props.getRk().getAuth().getUser().getRegister()
        );

        Queue userUpdateFromAuthQueue = buildQueue(
                props.getQueue().getAuth().getUpdate(),
                authEventsExchangeName,
                props.getRk().getAuth().getUser().getUpdate()
        );

        Queue userDeleteFromAuthQueue = buildQueue(
                props.getQueue().getAuth().getDelete(),
                authEventsExchangeName,
                props.getRk().getAuth().getUser().getDelete()
        );

        Queue userRegisteredQueue = buildQueue(
                props.getQueue().getUser().getRegister(),
                userEventsExchangeName,
                props.getRk().getUser().getRegister().getCreated()
        );

        Queue userDeletedQueue = buildQueue(
                props.getQueue().getUser().getDeleted(),
                userEventsExchangeName,
                props.getRk().getUser().getDeleted()
        );

        Queue userFollowedQueue = buildQueue(
                followCreatedQueueName,
                userEventsExchangeName,
                followCreatedRoutingKey
        );

        Queue userUnfollowedQueue = buildQueue(
                followDeletedQueueName,
                userEventsExchangeName,
                followDeletedRoutingKey
        );

        Queue userBlockedQueue = buildQueue(
                blockCreatedQueueName,
                userEventsExchangeName,
                blockCreatedRoutingKey
        );

        Queue postCreatedQueue = buildQueue(
                postCreatedQueueName,
                postEventsExchangeName,
                postCreatedRoutingKey
        );

        Queue postDeletedQueue = buildQueue(
                postDeletedQueueName,
                postEventsExchangeName,
                postDeletedRoutingKey
        );

        Queue userRegisterFromAuthDlq = buildDlq(props.getQueue().getAuth().getRegister());
        Queue userUpdateFromAuthDlq = buildDlq(props.getQueue().getAuth().getUpdate());
        Queue userDeleteFromAuthDlq = buildDlq(props.getQueue().getAuth().getDelete());

        Queue userRegisteredDlq = buildDlq(props.getQueue().getUser().getRegister());
        Queue userDeletedDlq = buildDlq(props.getQueue().getUser().getDeleted());
        Queue userFollowedDlq = buildDlq(followCreatedQueueName);
        Queue userUnfollowedDlq = buildDlq(followDeletedQueueName);
        Queue userBlockedDlq = buildDlq(blockCreatedQueueName);

        Queue postCreatedDlq = buildDlq(postCreatedQueueName);
        Queue postDeletedDlq = buildDlq(postDeletedQueueName);

        var authEventsExchange = new TopicExchange(authEventsExchangeName);
        var authEventsDlx = new DirectExchange(deadLetterExchangeName(authEventsExchangeName));

        var userEventsExchange = new TopicExchange(userEventsExchangeName);
        var userEventsDlx = new DirectExchange(deadLetterExchangeName(userEventsExchangeName));

        var postEventsExchange = new TopicExchange(postEventsExchangeName);
        var postEventsDlx = new DirectExchange(deadLetterExchangeName(postEventsExchangeName));

        return new Declarables(
                authEventsExchange,
                authEventsDlx,
                userEventsExchange,
                userEventsDlx,
                postEventsExchange,
                postEventsDlx,

                userRegisterFromAuthQueue,
                userUpdateFromAuthQueue,
                userDeleteFromAuthQueue,
                userRegisteredQueue,
                userDeletedQueue,
                userFollowedQueue,
                userUnfollowedQueue,
                userBlockedQueue,
                postCreatedQueue,
                postDeletedQueue,

                userRegisterFromAuthDlq,
                userUpdateFromAuthDlq,
                userDeleteFromAuthDlq,
                userRegisteredDlq,
                userDeletedDlq,
                userFollowedDlq,
                userUnfollowedDlq,
                userBlockedDlq,
                postCreatedDlq,
                postDeletedDlq,

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
                        .bind(userFollowedQueue)
                        .to(userEventsExchange)
                        .with(followCreatedRoutingKey),

                BindingBuilder
                        .bind(userUnfollowedQueue)
                        .to(userEventsExchange)
                        .with(followDeletedRoutingKey),

                BindingBuilder
                        .bind(userBlockedQueue)
                        .to(userEventsExchange)
                        .with(blockCreatedRoutingKey),

                BindingBuilder
                        .bind(postCreatedQueue)
                        .to(postEventsExchange)
                        .with(postCreatedRoutingKey),

                BindingBuilder
                        .bind(postDeletedQueue)
                        .to(postEventsExchange)
                        .with(postDeletedRoutingKey),

                BindingBuilder
                        .bind(userRegisterFromAuthDlq)
                        .to(authEventsDlx)
                        .with(deadLetterRoutingKey(props.getRk().getAuth().getUser().getRegister())),

                BindingBuilder
                        .bind(userUpdateFromAuthDlq)
                        .to(authEventsDlx)
                        .with(deadLetterRoutingKey(props.getRk().getAuth().getUser().getUpdate())),

                BindingBuilder
                        .bind(userDeleteFromAuthDlq)
                        .to(authEventsDlx)
                        .with(deadLetterRoutingKey(props.getRk().getAuth().getUser().getDelete())),

                BindingBuilder
                        .bind(userRegisteredDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(props.getRk().getUser().getRegister().getCreated())),

                BindingBuilder
                        .bind(userDeletedDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(props.getRk().getUser().getDeleted())),

                BindingBuilder
                        .bind(userFollowedDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(followCreatedRoutingKey)),

                BindingBuilder
                        .bind(userUnfollowedDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(followDeletedRoutingKey)),

                BindingBuilder
                        .bind(userBlockedDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(blockCreatedRoutingKey)),

                BindingBuilder
                        .bind(postCreatedDlq)
                        .to(postEventsDlx)
                        .with(deadLetterRoutingKey(postCreatedRoutingKey)),

                BindingBuilder
                        .bind(postDeletedDlq)
                        .to(postEventsDlx)
                        .with(deadLetterRoutingKey(postDeletedRoutingKey))
        );
    }

    private Queue buildQueue(String queueName, String exchangeName, String routingKey) {
        return QueueBuilder
                .durable(queueName)
                .withArgument("x-dead-letter-exchange", deadLetterExchangeName(exchangeName))
                .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey(routingKey))
                .build();
    }

    private Queue buildDlq(String queueName) {
        return QueueBuilder
                .durable(queueName + ".dlq")
                .build();
    }

    private String deadLetterExchangeName(String exchangeName) {
        return exchangeName + ".dlx";
    }

    private String deadLetterRoutingKey(String routingKey) {
        return routingKey + ".fall-back";
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        configurer.configure(factory, connectionFactory);

        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor());

        return factory;
    }

    @Bean
    public Advice retryInterceptor() {
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .maxRetries(2)
                /*
                 * Backoff:
                 * 2s, then 4s, then up to max 100s.
                 */
                .delay(Duration.ofMillis(2000))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(100000))

                /*
                 * These exceptions are not retried.
                 *
                 * They go directly to the recoverer.
                 * RejectAndDontRequeueRecoverer rejects the message with requeue=false.
                 * If the queue has DLX configured, RabbitMQ sends it to the DLQ.
                 */
                .excludes(
                        AmqpRejectAndDontRequeueException.class,
                        IllegalArgumentException.class
                )
                .build();

        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

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