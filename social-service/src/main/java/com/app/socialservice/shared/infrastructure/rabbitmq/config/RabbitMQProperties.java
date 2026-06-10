package com.app.socialservice.config.rabbitmq;

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
        private UserQueues user = new UserQueues();

        @Data
        public static class UserQueues {
            private String register;
        }
    }

    @Data
    public static class Exchanges {
        private AuthExchange auth = new AuthExchange();
        private UserExchange user = new UserExchange();

        @Data
        public static class AuthExchange {
            private String events;
        }

        @Data
        public static class UserExchange {
            private String events;
        }
    }

    @Data
    public static class RoutingKeys {
        private AuthRk auth = new AuthRk();
        private UserRk user = new UserRk();

        @Data
        public static class AuthRk {
            private AuthUserRk user = new AuthUserRk();

            @Data
            public static class AuthUserRk {
                private String register;
            }
        }

        @Data
        public static class UserRk {
            private String updated;
            private String deleted;
            private RegisterRk register = new RegisterRk();
            private FollowRk follow = new FollowRk();
            private BlockRk block = new BlockRk();

            @Data
            public static class RegisterRk {
                private String created;
            }

            @Data
            public static class FollowRk {
                private String created;
                private String deleted;
            }

            @Data
            public static class BlockRk {
                private String created;
                private String deleted;
            }
        }
    }
}