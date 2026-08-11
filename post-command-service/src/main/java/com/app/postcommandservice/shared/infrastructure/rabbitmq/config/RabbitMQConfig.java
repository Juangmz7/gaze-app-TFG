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
        return new Declarables();
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