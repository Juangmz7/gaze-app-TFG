package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rabbitmq")
public class RabbitMQProperties {

    private Queues queue = new Queues();
    private Exchanges exchange = new Exchanges();
    private RoutingKeys rk = new RoutingKeys();

    @Data
    public static class Queues {

    }

    @Data
    public static class Exchanges {

    }

    @Data
    public static class RoutingKeys {

    }
}
