from uuid import UUID, uuid4

from rabbitmq.config.constants import FEED_EXCHANGE, FEED_TRENDING_SENT_RK
from rabbitmq.event.feed.feed_events import TrendingPostSentEvent
from shared.config.database import SQLAlchemyTransactionManager
from shared.outbox.outbox_publisher import OutboxPublisher


class TrendingFeedUseCase:
    def __init__(
        self,
        outbox_publisher: OutboxPublisher,
        transaction_manager: SQLAlchemyTransactionManager,
    ):
        self.outbox_publisher = outbox_publisher
        self.transaction_manager = transaction_manager

    def publish_trending_post_sent(self, posts: list[UUID], correlation_id: UUID | None = None) -> None:
        """Saves a TrendingPostSent event to the outbox. TODO: no caller yet."""
        event = TrendingPostSentEvent(correlationId=correlation_id or uuid4(), posts=posts)
        with self.transaction_manager.transaction() as session:
            self.outbox_publisher.add(
                session,
                event,
                event_type="TrendingPostSent",
                exchange=FEED_EXCHANGE,
                routing_key=FEED_TRENDING_SENT_RK,
            )

    def generate_trending_feed(self) -> None:
        # TODO: implement trending logic and call publish_trending_post_sent()
        pass
