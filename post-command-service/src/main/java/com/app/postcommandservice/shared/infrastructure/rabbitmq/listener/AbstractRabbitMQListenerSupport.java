package com.app.postcommandservice.shared.infrastructure.rabbitmq.listener;

import com.app.postcommandservice.shared.infrastructure.rabbitmq.config.RabbitMQProperties;
import com.app.postcommandservice.shared.infrastructure.repository.ProcessedEventsRepository;

abstract class AbstractRabbitMQListenerSupport {

    private final ProcessedEventsRepository processedEventsRepository;
    protected final RabbitMQProperties rabbitMQProperties;

    protected AbstractRabbitMQListenerSupport(
            ProcessedEventsRepository processedEventsRepository,
            RabbitMQProperties rabbitMQProperties) {
        this.processedEventsRepository = processedEventsRepository;
        this.rabbitMQProperties = rabbitMQProperties;
    }


}
