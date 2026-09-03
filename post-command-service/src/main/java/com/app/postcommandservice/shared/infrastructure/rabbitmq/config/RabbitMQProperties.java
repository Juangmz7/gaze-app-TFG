package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rabbitmq")
public class RabbitMQProperties {

    private Queues queue = new Queues();
    private Exchanges exchange = new Exchanges();
    private RoutingKeys rk = new RoutingKeys();

    // ---------------------------------------------------------------
    // Queues (incoming)
    // ---------------------------------------------------------------
    @Data
    public static class Queues {
        private String post;              // q.post-command-service.post
        private UserQueues user = new UserQueues();

        @Data
        public static class UserQueues {
            private String slow;          // q.post-command-service.user.slow
            private String fast;          // q.post-command-service.user.fast
        }
    }

    // ---------------------------------------------------------------
    // Exchanges (incoming source + outgoing target)
    // ---------------------------------------------------------------
    @Data
    public static class Exchanges {
        private PostExchange post = new PostExchange();
        private UserExchange user = new UserExchange();

        @Data
        public static class PostExchange {
            private String commands;      // x.post.commands   (incoming source)
            private String events;        // x.post.events     (outgoing target)
        }

        @Data
        public static class UserExchange {
            private String events;        // x.user.events     (incoming source)
        }
    }

    // ---------------------------------------------------------------
    // Routing Keys (incoming validation rks + outgoing event rks)
    // ---------------------------------------------------------------
    @Data
    public static class RoutingKeys {
        private PostRk post = new PostRk();
        private UserRk user = new UserRk();

        @Data
        public static class PostRk {
            // outgoing (post lifecycle events)
            private String created;
            private String updated;
            private String deleted;
            private String banned;
            private String viewed;

            private ViewRk view = new ViewRk();
            private LikeRk like = new LikeRk();
            private UnlikeRk unlike = new UnlikeRk();
            private ShareRk share = new ShareRk();
            private CommentRk comment = new CommentRk();
            private CollabRk collab = new CollabRk();

            @Data
            public static class ViewRk {
                private String process;               // incoming: rk.post.view.process
            }

            @Data
            public static class LikeRk {
                private String validate;               // incoming: rk.post.like.validate
                private String created;                // outgoing: rk.post.like.created
                private String deleted;                // outgoing: rk.post.like.deleted
            }

            @Data
            public static class UnlikeRk {
                private String validate;               // incoming: rk.post.unlike.validate
            }

            @Data
            public static class ShareRk {
                private CreateRk create = new CreateRk();
                private DeleteRk delete = new DeleteRk();
                private String created;                 // outgoing: rk.post.share.created
                private String deleted;                 // outgoing: rk.post.share.deleted

                @Data
                public static class CreateRk {
                    private String validate;            // incoming: rk.post.share.create.validate
                }

                @Data
                public static class DeleteRk {
                    private String validate;            // incoming: rk.post.share.delete.validate
                }
            }

            @Data
            public static class CommentRk {
                private String created;
                private String updated;
                private String deleted;
                private String banned;
                private LikeRk like = new LikeRk();

                @Data
                public static class LikeRk {
                    private String validate;
                    private String created;
                    private String deleted;
                }
            }

            @Data
            public static class CollabRk {
                private String opened;
                private String closed;
                private String linked;
                private RequestRk request = new RequestRk();
                private MemberRk member = new MemberRk();

                @Data
                public static class RequestRk {
                    private String created;
                    private String accepted;
                    private String rejected;
                    private String deleted;
                }

                @Data
                public static class MemberRk {
                    private String left;
                    private String banned;
                }
            }
        }

        @Data
        public static class UserRk {
            // incoming (consumed from x.user.events)
            private String deleted;
            private String registered;
            private String updated;
            private BlockRk block = new BlockRk();

            @Data
            public static class BlockRk {
                private String created;
                private String deleted;
            }
        }
    }
}
