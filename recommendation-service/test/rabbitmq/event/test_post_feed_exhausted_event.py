import json
from uuid import UUID

import pytest
from pydantic import ValidationError

from rabbitmq.event.post.post_events import PostFeedExhaustedEvent, PostFeedExhaustedReason

pytestmark = pytest.mark.unit

LITERAL_EVENT = """
{
  "id": "019f0000-0000-7000-8000-000000000001",
  "correlationId": "019f0000-0000-7000-8000-000000000002",
  "occurredAt": "2026-11-04T10:00:00.123456Z",
  "userId": "019f0000-0000-7000-8000-000000000003",
  "reason": "low_watermark",
  "currentFeedSize": 7,
  "excludedPostIds": ["019f0000-0000-7000-8000-000000000004", "019f0000-0000-7000-8000-000000000005"]
}
"""


def test_should_parse_user_id_reason_feed_size_and_excluded_ids_from_literal_camel_case():
    event = PostFeedExhaustedEvent.model_validate_json(LITERAL_EVENT)

    assert event.id == UUID("019f0000-0000-7000-8000-000000000001")
    assert event.correlationId == UUID("019f0000-0000-7000-8000-000000000002")
    assert event.userId == UUID("019f0000-0000-7000-8000-000000000003")
    assert event.reason is PostFeedExhaustedReason.LOW_WATERMARK
    assert event.currentFeedSize == 7
    assert event.excludedPostIds == [
        UUID("019f0000-0000-7000-8000-000000000004"),
        UUID("019f0000-0000-7000-8000-000000000005"),
    ]


@pytest.mark.parametrize("reason", ["disconnect", "stale_heartbeat", "user_registered", "low_watermark"])
def test_should_accept_every_reason_of_the_contract(reason):
    event = PostFeedExhaustedEvent.model_validate_json(LITERAL_EVENT.replace("low_watermark", reason))

    assert event.reason.value == reason


@pytest.mark.parametrize("reason", ["DISCONNECT", "FEED_EMPTY", "feed_empty", ""])
def test_should_reject_a_reason_outside_the_contract(reason):
    with pytest.raises(ValidationError):
        PostFeedExhaustedEvent.model_validate_json(LITERAL_EVENT.replace("low_watermark", reason))


def test_should_read_null_excluded_ids_as_no_exclusions():
    event = PostFeedExhaustedEvent.model_validate_json(
        LITERAL_EVENT.replace(
            '["019f0000-0000-7000-8000-000000000004", "019f0000-0000-7000-8000-000000000005"]', "null"
        )
    )

    assert event.excludedPostIds == []


@pytest.mark.parametrize("missing", ["userId", "reason", "currentFeedSize", "correlationId"])
def test_should_reject_a_missing_required_field(missing):

    payload = json.loads(LITERAL_EVENT)
    del payload[missing]

    with pytest.raises(ValidationError):
        PostFeedExhaustedEvent.model_validate(payload)


def test_should_reject_a_negative_feed_size():
    with pytest.raises(ValidationError):
        PostFeedExhaustedEvent.model_validate_json(LITERAL_EVENT.replace('"currentFeedSize": 7', '"currentFeedSize": -1'))
