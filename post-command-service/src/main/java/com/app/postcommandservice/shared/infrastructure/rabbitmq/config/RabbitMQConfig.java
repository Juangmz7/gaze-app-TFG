package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

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
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.app.postcommandservice.commentlike.application.commands.ValidateCommentLikeCommand;
import com.app.postcommandservice.commentlike.application.commands.ValidateCommentUnlikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.like.application.commands.ValidatePostUnlikeCommand;
import com.app.postcommandservice.post.infrastructure.events.UserBlockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserDeletedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserFollowedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserRegisteredEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnblockedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUnfollowedEvent;
import com.app.postcommandservice.post.infrastructure.events.UserUpdatedEvent;
import com.app.postcommandservice.share.application.commands.CreatePostShareCommand;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;

@Configuration
@EnableConfigurationProperties(RabbitMQProperties.class)
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties props;

    @Bean
    public Declarables postCommandServiceSchema() {

        var postCommandsExchangeName = props.getExchange().getPost().getCommands();
        var postEventsExchangeName = props.getExchange().getPost().getEvents();
        var userEventsExchangeName = props.getExchange().getUser().getEvents();

        var postQueueName = props.getQueue().getPost();
        var userFastQueueName = props.getQueue().getUser().getFast();
        var userSlowQueueName = props.getQueue().getUser().getSlow();

        var postShareCreateValidateRk = props.getRk().getPost().getShare().getCreate().getValidate();
        var postShareDeleteValidateRk = props.getRk().getPost().getShare().getDelete().getValidate();
        var postViewProcessRk = props.getRk().getPost().getView().getProcess();
        var postLikeValidateRk = props.getRk().getPost().getLike().getValidate();
        var postUnlikeValidateRk = props.getRk().getPost().getUnlike().getValidate();
        var commentLikeValidateRk = props.getRk().getPost().getComment().getLike().getValidate();
        var commentLikeDeletedRk = props.getRk().getPost().getComment().getLike().getDeleted();

        var userBlockCreatedRk = props.getRk().getUser().getBlock().getCreated();
        var userFollowCreatedRk = props.getRk().getUser().getFollow().getCreated();
        var userRegisteredRk = props.getRk().getUser().getRegistered();
        var userUpdatedRk = props.getRk().getUser().getUpdated();

        var userBlockDeletedRk = props.getRk().getUser().getBlock().getDeleted();
        var userFollowDeletedRk = props.getRk().getUser().getFollow().getDeleted();
        var userDeletedRk = props.getRk().getUser().getDeleted();

        var postCommandsExchange = new TopicExchange(postCommandsExchangeName);
        var postCommandsDlx = new DirectExchange(deadLetterExchangeName(postCommandsExchangeName));

        var userEventsExchange = new TopicExchange(userEventsExchangeName);
        var userEventsDlx = new DirectExchange(deadLetterExchangeName(userEventsExchangeName));

        var postEventsExchange = new TopicExchange(postEventsExchangeName);

        Queue postQueue = buildQueue(postQueueName, postCommandsExchangeName);
        Queue postDlq = buildDlq(postQueueName);

        Queue userFastQueue = buildQueue(userFastQueueName, userEventsExchangeName);
        Queue userFastDlq = buildDlq(userFastQueueName);

        Queue userSlowQueue = buildQueue(userSlowQueueName, userEventsExchangeName);
        Queue userSlowDlq = buildDlq(userSlowQueueName);

        return new Declarables(
                postCommandsExchange,
                postCommandsDlx,
                userEventsExchange,
                userEventsDlx,
                postEventsExchange,

                postQueue,
                postDlq,
                userFastQueue,
                userFastDlq,
                userSlowQueue,
                userSlowDlq,

                // --- post queue: 4 routing keys, same queue ---
                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(postShareCreateValidateRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(postShareDeleteValidateRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(postViewProcessRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(postLikeValidateRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(postUnlikeValidateRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(commentLikeValidateRk),

                BindingBuilder
                        .bind(postQueue)
                        .to(postCommandsExchange)
                        .with(commentLikeDeletedRk),

                // --- user.fast queue: 4 routing keys, same queue ---
                BindingBuilder
                        .bind(userFastQueue)
                        .to(userEventsExchange)
                        .with(userBlockCreatedRk),

                BindingBuilder
                        .bind(userFastQueue)
                        .to(userEventsExchange)
                        .with(userFollowCreatedRk),

                BindingBuilder
                        .bind(userFastQueue)
                        .to(userEventsExchange)
                        .with(userRegisteredRk),

                BindingBuilder
                        .bind(userFastQueue)
                        .to(userEventsExchange)
                        .with(userUpdatedRk),

                // --- user.slow queue: 3 routing keys, same queue ---
                BindingBuilder
                        .bind(userSlowQueue)
                        .to(userEventsExchange)
                        .with(userBlockDeletedRk),

                BindingBuilder
                        .bind(userSlowQueue)
                        .to(userEventsExchange)
                        .with(userFollowDeletedRk),

                BindingBuilder
                        .bind(userSlowQueue)
                        .to(userEventsExchange)
                        .with(userDeletedRk),

                // --- DLQ bindings: one per queue, keyed off the queue name (not any single rk) ---
                BindingBuilder
                        .bind(postDlq)
                        .to(postCommandsDlx)
                        .with(deadLetterRoutingKey(postQueueName)),

                BindingBuilder
                        .bind(userFastDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(userFastQueueName)),

                BindingBuilder
                        .bind(userSlowDlq)
                        .to(userEventsDlx)
                        .with(deadLetterRoutingKey(userSlowQueueName))
        );
    }

    private Queue buildQueue(String queueName, String exchangeName) {
        return QueueBuilder
                .durable(queueName)
                .withArgument("x-dead-letter-exchange", deadLetterExchangeName(exchangeName))
                .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey(queueName))
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

    private String deadLetterRoutingKey(String key) {
        return key + ".fall-back";
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
        var converter = new JacksonJsonMessageConverter();

        /*
         * Class-level @RabbitListener beans pick a @RabbitHandler from the __TypeId__ header.
         * Outbox relays (this service's and social-service's) set it to the event's simple
         * class name, so map those logical ids to this service's own classes.
         */
        var typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages("*");
        typeMapper.setIdClassMapping(typeIds(
                // q.post-command-service.post (own commands)
                ValidatePostLikeCommand.class,
                ValidatePostUnlikeCommand.class,
                ValidateCommentLikeCommand.class,
                ValidateCommentUnlikeCommand.class,
                CreatePostShareCommand.class,
                ProcessPostViewCommand.class,
                // q.post-command-service.user.fast / .slow (social-service events)
                UserRegisteredEvent.class,
                UserUpdatedEvent.class,
                UserDeletedEvent.class,
                UserBlockedEvent.class,
                UserUnblockedEvent.class,
                UserFollowedEvent.class,
                UserUnfollowedEvent.class
        ));
        converter.setJavaTypeMapper(typeMapper);

        return converter;
    }

    private static Map<String, Class<?>> typeIds(Class<?>... types) {
        return Arrays.stream(types).collect(Collectors.toMap(Class::getSimpleName, Function.identity()));
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            MessageConverter messageConverter,
            CachingConnectionFactory cachingConnectionFactory) {

        var template = new RabbitTemplate(cachingConnectionFactory);
        template.setMessageConverter(messageConverter);
        // Unroutable messages are returned to the publisher instead of silently dropped
        // (requires spring.rabbitmq.publisher-returns). Used by the outbox relay.
        template.setMandatory(true);

        return template;
    }
}
