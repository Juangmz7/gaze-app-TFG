package com.app.socialservice.shared.infrastructure.rabbitmq.config;

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
        private AuthQueues auth = new AuthQueues();
        private PostQueues post = new PostQueues();

        @Data
        public static class UserQueues {
            private String register;
            private String deleted;
            private FollowQueues follow = new FollowQueues();
            private BlockQueues block = new BlockQueues();

            @Data
            public static class FollowQueues {
                private String created;
                private String deleted;
            }

            @Data
            public static class BlockQueues {
                private String created;
            }
        }

        @Data
        public static class AuthQueues {
            private String register;
            private String update;
            private String delete;
        }

        @Data
        public static class PostQueues {
            private String created;
            private String deleted;
        }
    }

    @Data
    public static class Exchanges {
        private AuthExchange auth = new AuthExchange();
        private UserExchange user = new UserExchange();
        private PostExchange post = new PostExchange();

        @Data
        public static class AuthExchange {
            private String events;
        }

        @Data
        public static class UserExchange {
            private String events;
        }

        @Data
        public static class PostExchange {
            private String events;
        }
    }

    @Data
    public static class RoutingKeys {
        private AuthRk auth = new AuthRk();
        private UserRk user = new UserRk();
        private PostRk post = new PostRk();

        @Data
        public static class AuthRk {
            private AuthUserRk user = new AuthUserRk();

            @Data
            public static class AuthUserRk {
                private String register;
                private String update;
                private String delete;
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

        @Data
        public static class PostRk {
            private String created;
            private String deleted;
        }
    }
}
