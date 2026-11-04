from pathlib import Path

import pytest
from google.protobuf import descriptor_pb2
from grpc_tools import protoc

from recommendation.v1 import recommendation_pb2, recommendation_pb2_grpc

pytestmark = pytest.mark.unit

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
STALE_STUBS_HINT = "The stubs are stale: regenerate them (see docs/recommendation-service.md)"


def _without_json_names(message: descriptor_pb2.DescriptorProto) -> None:
    for field in message.field:
        field.ClearField("json_name")
    for nested_message in message.nested_type:
        _without_json_names(nested_message)


def _serialized(file_descriptor: descriptor_pb2.FileDescriptorProto) -> bytes:
    """protoc fills json_name in a descriptor set, not in the generated Python code."""
    for message in file_descriptor.message_type:
        _without_json_names(message)
    return file_descriptor.SerializeToString(deterministic=True)


def test_committed_stubs_match_the_proto(tmp_path):
    # Compares what the proto defines instead of the generated text, which also carries
    # the versions of the generator and changes with every grpcio-tools release
    descriptor_set_path = tmp_path / "descriptor_set.pb"

    # Act
    exit_code = protoc.main([
        "grpc_tools.protoc",
        f"-I{REPOSITORY_ROOT / 'proto'}",
        f"--descriptor_set_out={descriptor_set_path}",
        str(REPOSITORY_ROOT / "proto" / "recommendation" / "v1" / "recommendation.proto"),
    ])

    # Assert
    assert exit_code == 0
    [from_proto] = descriptor_pb2.FileDescriptorSet.FromString(descriptor_set_path.read_bytes()).file
    committed = descriptor_pb2.FileDescriptorProto.FromString(recommendation_pb2.DESCRIPTOR.serialized_pb)
    assert _serialized(committed) == _serialized(from_proto), STALE_STUBS_HINT


def test_committed_grpc_stub_has_every_rpc_of_the_contract():
    # The descriptor lives in recommendation_pb2 only, so it says nothing about this file
    service = recommendation_pb2.DESCRIPTOR.services_by_name["RecommendationService"]

    for method in service.methods:
        assert hasattr(recommendation_pb2_grpc.RecommendationServiceServicer, method.name), STALE_STUBS_HINT


def test_contract_names():
    assert recommendation_pb2.FeedRequestReason.Name(0) == "FEED_REQUEST_REASON_UNSPECIFIED"
    assert set(recommendation_pb2.FeedRequestReason.keys()) >= {
        "DISCONNECT", "STALE_HEARTBEAT", "USER_REGISTERED", "LOW_WATERMARK", "FEED_EMPTY",
    }
    assert set(recommendation_pb2.GetRecommendedFeedRequest.DESCRIPTOR.fields_by_name) == {
        "user_id", "excluded_post_ids", "reason",
    }
    assert set(recommendation_pb2.GetRecommendedFeedResponse.DESCRIPTOR.fields_by_name) == {"post_ids"}
