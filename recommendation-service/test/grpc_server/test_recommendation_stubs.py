from pathlib import Path

import pytest
from grpc_tools import protoc

from recommendation.v1 import recommendation_pb2

pytestmark = pytest.mark.unit

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
COMMITTED_STUBS = REPOSITORY_ROOT / "recommendation-service" / "src" / "recommendation" / "v1"


def test_committed_stubs_match_the_proto(tmp_path):
    # Act
    exit_code = protoc.main([
        "grpc_tools.protoc",
        f"-I{REPOSITORY_ROOT / 'proto'}",
        f"--python_out={tmp_path}",
        f"--pyi_out={tmp_path}",
        f"--grpc_python_out={tmp_path}",
        str(REPOSITORY_ROOT / "proto" / "recommendation" / "v1" / "recommendation.proto"),
    ])

    # Assert
    assert exit_code == 0
    for name in ("recommendation_pb2.py", "recommendation_pb2.pyi", "recommendation_pb2_grpc.py"):
        generated = (tmp_path / "recommendation" / "v1" / name).read_text().replace("\r\n", "\n")
        committed = (COMMITTED_STUBS / name).read_text().replace("\r\n", "\n")
        assert committed == generated, f"{name} is stale: regenerate it (see docs/recommendation-service.md)"


def test_contract_names():
    assert recommendation_pb2.FeedRequestReason.Name(0) == "FEED_REQUEST_REASON_UNSPECIFIED"
    assert set(recommendation_pb2.FeedRequestReason.keys()) >= {
        "DISCONNECT", "STALE_HEARTBEAT", "USER_REGISTERED", "LOW_WATERMARK", "FEED_EMPTY",
    }
    assert set(recommendation_pb2.GetRecommendedFeedRequest.DESCRIPTOR.fields_by_name) == {
        "user_id", "excluded_post_ids", "reason",
    }
    assert set(recommendation_pb2.GetRecommendedFeedResponse.DESCRIPTOR.fields_by_name) == {"post_ids"}
