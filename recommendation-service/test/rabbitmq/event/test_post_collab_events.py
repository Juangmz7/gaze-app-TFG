from uuid import uuid4

import pytest

from rabbitmq.event.post.post_events import (
    PostCollabLinkedEvent,
    PostCollabOpenedWithPostCreatedEvent,
    PostType,
)

pytestmark = pytest.mark.unit

NOW = "2026-11-11T10:00:00Z"


## The payload post-command-service's CollabOpenedEvent sends, with its PostType
## names: a value the enum did not know sent every collab event to the DLQ.
def collab_opened_payload() -> dict:
    return {
        "id": str(uuid4()),
        "correlationId": str(uuid4()),
        "occurredAt": NOW,
        "collabId": str(uuid4()),
        "title": "Summer trip",
        "createdBy": str(uuid4()),
        "collabStatus": "OPEN",
        "collabCreatedAt": NOW,
        "creatorMemberStatus": "ACCEPTED",
        "creatorRole": "ADMIN",
        "creatorMemberCreatedAt": NOW,
        "postId": str(uuid4()),
        "userId": str(uuid4()),
        "postCollabId": str(uuid4()),
        "postType": "COLLAB",
        "description": None,
        "postTags": ["travel"],
        "media": [],
        "postCreatedAt": NOW,
        "postUpdatedAt": NOW,
        "withPostCreated": True,
    }


def test_a_collab_opened_event_from_post_command_service_validates():
    event = PostCollabOpenedWithPostCreatedEvent.model_validate(collab_opened_payload())

    assert event.postType is PostType.COLLAB


def test_a_collab_linked_event_with_a_collab_post_validates():
    payload = {
        "id": str(uuid4()),
        "correlationId": str(uuid4()),
        "occurredAt": NOW,
        "postId": str(uuid4()),
        "userId": str(uuid4()),
        "collabId": str(uuid4()),
        "postType": "COLLAB",
        "createdAt": NOW,
        "updatedAt": NOW,
    }

    assert PostCollabLinkedEvent.model_validate(payload).postType is PostType.COLLAB
