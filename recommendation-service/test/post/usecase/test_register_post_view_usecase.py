from datetime import datetime, timezone
from unittest.mock import Mock, PropertyMock, patch
from uuid import uuid4

import pytest

from pipeline.config.constants import FAST_SKIP_WEIGHT, POST_VIEW_WEIGHT
from pipeline.exceptions.exceptions import (
    PostFeaturesNotFoundException,
    PostInteractionFeaturesNotFoundException,
)
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.model.user.user_features import UserFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from post.command.post_commands import RegisterPostViewCommand
from post.usecase.register_post_view_usecase import RegisterPostViewUsecase
from rabbitmq.event.post.post_events import InteractionSource, PostViewExitReason

pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _make_command(
    post_id=None,
    user_id=None,
    duration_ms=10000,
    time_watched_ms=5000,
    completion_percent=50,
    exit_reason=PostViewExitReason.SCROLL_NEXT,
    source=InteractionSource.HOME_FEED,
    feed_position=1,
):
    return RegisterPostViewCommand(
        event_id=uuid4(),
        correlation_id=uuid4(),
        occurred_at=NOW,
        view_id=uuid4(),
        post_id=post_id or uuid4(),
        user_id=user_id or uuid4(),
        source=source,
        feed_position=feed_position,
        duration_ms=duration_ms,
        time_watched_ms=time_watched_ms,
        completion_percent=completion_percent,
        exit_reason=exit_reason,
        server_timestamp=NOW,
        replay_count=0,
    )


def _make_raw_stats(impressions=0, views=0, watch_time=0.0, watch_time_average_percent=0.0, fast_skips=0):
    stats = RawInteractionStats.empty()
    stats.impressions = impressions
    stats.views = views
    stats.watch_time = watch_time
    stats.watch_time_average_percent = watch_time_average_percent
    stats.fast_skips = fast_skips
    return stats


def _make_decayed_stats(impressions=0.0, views_engagement=0.0):
    stats = DecayedInteractionStats.empty()
    stats.impressions = impressions
    stats.views_engagement = views_engagement
    return stats


def _make_user_creator_features(user_id, creator_id, raw_stats=None, decayed_stats=None):
    return UserCreatorFeatures(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats or _make_raw_stats(),
        decayed_interaction_stats=decayed_stats or _make_decayed_stats(),
        last_updated_at=NOW,
    )


def _make_post_tag_features(user_id, tag_name, raw_stats=None, decayed_stats=None):
    return PostTagFeatures(
        user_id=user_id,
        tag_name=tag_name,
        raw_interaction_stats=raw_stats or _make_raw_stats(),
        decayed_interaction_stats=decayed_stats or _make_decayed_stats(),
        last_updated_at=NOW,
    )


def _make_post_interaction_features(post_id, raw_stats=None, decayed_stats=None):
    return PostInteractionFeatures(
        post_id=post_id,
        raw_interaction_stats=raw_stats or _make_raw_stats(),
        decayed_interaction_stats=decayed_stats or _make_decayed_stats(),
        last_updated_at=NOW,
    )


def _make_post_features(post_id, creator_id, tags=None):
    return PostFeatures(
        post_id=post_id,
        creator_id=creator_id,
        collab_id=None,
        collab_title=None,
        description="test",
        tags=tags or ["tag1"],
        tagged_users_ids=[],
        semantic_embedding=[0.1] * 64,
        created_at=NOW,
    )


def _make_user_features(user_id):
    return UserFeatures(
        user_id=user_id,
        semantic_embedding=[0.0] * 64,
        last_updated_at=NOW,
    )


class _Fixture:
    """Helper to set up the usecase with mocked repositories."""

    def __init__(
        self,
        post_id=None,
        user_id=None,
        creator_id=None,
        tags=None,
        raw_stats_kwargs=None,
    ):
        self.post_id = post_id or uuid4()
        self.user_id = user_id or uuid4()
        self.creator_id = creator_id or uuid4()
        self.tags = tags or ["tag1"]

        raw_kw = raw_stats_kwargs or {}

        self.user_creator_features = _make_user_creator_features(
            self.user_id, self.creator_id,
            raw_stats=_make_raw_stats(**raw_kw),
            decayed_stats=_make_decayed_stats(),
        )
        self.post_tag_features_list = [
            _make_post_tag_features(
                self.user_id, tag,
                raw_stats=_make_raw_stats(**raw_kw),
                decayed_stats=_make_decayed_stats(),
            )
            for tag in self.tags
        ]
        self.post_interaction_features = _make_post_interaction_features(
            self.post_id,
            raw_stats=_make_raw_stats(**raw_kw),
            decayed_stats=_make_decayed_stats(),
        )
        self.post_features = _make_post_features(self.post_id, self.creator_id, self.tags)
        self.user_features = _make_user_features(self.user_id)

        self.user_creator_repo = Mock(spec=UserCreatorFeaturesRepository)
        self.post_tag_repo = Mock(spec=PostTagFeaturesRepository)
        self.post_features_repo = Mock(spec=PostFeaturesRepository)
        self.user_features_repo = Mock(spec=UserFeaturesRepository)
        self.post_interaction_repo = Mock(spec=PostInteractionFeaturesRepository)

        self.post_features_repo.get_post_features.return_value = self.post_features
        self.post_interaction_repo.get_for_update.return_value = self.post_interaction_features
        self.user_creator_repo.get_user_creator_features.return_value = self.user_creator_features
        self.user_creator_repo.get_user_creator_features_for_update.return_value = self.user_creator_features
        self.post_tag_repo.get_post_tag_features.return_value = self.post_tag_features_list
        self.post_tag_repo.get_post_tag_features_for_update.return_value = self.post_tag_features_list
        self.user_features_repo.get_user_features_for_update.return_value = self.user_features

        self.usecase = RegisterPostViewUsecase(
            user_creator_features_repository=self.user_creator_repo,
            post_tag_features_repository=self.post_tag_repo,
            post_features_repository=self.post_features_repo,
            user_features_repository=self.user_features_repo,
            post_interaction_features_repository=self.post_interaction_repo,
        )


# ---------------------------------------------------------------------------
# watch_time_average_percent tests
# ---------------------------------------------------------------------------

def test_first_view_sets_average_to_watch_percent():
    """First view (old_views=0): avg should equal watch_percent."""
    fixture = _Fixture()
    # 10s duration, 6s watched → watch_percent = 0.6
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=6000, completion_percent=60,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time_average_percent == pytest.approx(0.6)
    assert raw.views == 1


def test_multiple_views_with_different_percentages():
    """Two views: 60% then 30% → avg = (0.6*1 + 0.3) / 2 = 0.45."""
    fixture = _Fixture(raw_stats_kwargs={"views": 1, "watch_time_average_percent": 0.6, "impressions": 1})

    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=3000, completion_percent=30,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time_average_percent == pytest.approx(0.45)
    assert raw.views == 2


def test_view_watching_100_percent():
    """Watching the full post → watch_percent = 1.0."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=10000, completion_percent=100,
        exit_reason=PostViewExitReason.VIDEO_COMPLETED,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time_average_percent == pytest.approx(1.0)


def test_view_watching_0_percent():
    """Watching 0s but not a fast skip (e.g. navigated away) → watch_percent = 0.0."""
    fixture = _Fixture(raw_stats_kwargs={"views": 2, "watch_time_average_percent": 0.5, "impressions": 2})
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=0, completion_percent=0,
        exit_reason=PostViewExitReason.NAVIGATED_AWAY,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    # (0.5 * 2 + 0.0) / 3 = 1.0/3 ≈ 0.333
    assert raw.watch_time_average_percent == pytest.approx(1.0 / 3.0)
    assert raw.views == 3


def test_zero_post_duration_no_division_by_zero():
    """duration_ms=0 → watch_percent=0.0, no crash."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=0, time_watched_ms=5000, completion_percent=50,
        exit_reason=PostViewExitReason.NAVIGATED_AWAY,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time_average_percent == pytest.approx(0.0)
    assert raw.views == 1


def test_watch_time_is_sum_of_watched_seconds():
    """watch_time accumulates total watched seconds."""
    fixture = _Fixture(raw_stats_kwargs={"watch_time": 5.0, "impressions": 1})
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=3000, completion_percent=30,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time == pytest.approx(8.0)


def test_watch_time_average_is_correct_running_average():
    """After 3 views with 60%, 30%, 90%: avg = (0.6 + 0.3 + 0.9) / 3 = 0.6."""
    fixture = _Fixture(raw_stats_kwargs={
        "views": 2,
        "watch_time_average_percent": 0.45,  # (0.6 + 0.3) / 2
        "impressions": 2,
    })
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=9000, completion_percent=90,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    # (0.45 * 2 + 0.9) / 3 = 1.8 / 3 = 0.6
    assert raw.watch_time_average_percent == pytest.approx(0.6)


# ---------------------------------------------------------------------------
# Branching logic tests
# ---------------------------------------------------------------------------

def test_fast_skip_increments_fast_skips_not_views():
    """completion < 5% + SCROLL_NEXT → fast skip, no view increment."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=200, completion_percent=2,
        exit_reason=PostViewExitReason.SCROLL_NEXT,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.fast_skips == 1
    assert raw.views == 0
    assert raw.watch_time_average_percent == pytest.approx(0.0)
    assert raw.impressions == 1


def test_low_completion_non_scroll_next_counts_as_valid_view():
    """completion < 5% but exit_reason != SCROLL_NEXT → valid view."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=300, completion_percent=3,
        exit_reason=PostViewExitReason.NAVIGATED_AWAY,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.views == 1
    assert raw.fast_skips == 0
    assert raw.impressions == 1


def test_normal_view_increments_views_impressions_and_watch_time():
    """A normal view (completion >= 5%) increments all expected metrics."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.views == 1
    assert raw.impressions == 1
    assert raw.watch_time == pytest.approx(5.0)
    assert raw.watch_time_average_percent == pytest.approx(0.5)


# ---------------------------------------------------------------------------
# Always-applied tests
# ---------------------------------------------------------------------------

def test_impressions_always_increment_on_fast_skip():
    """Impressions increment even for fast skips."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=100, completion_percent=1,
        exit_reason=PostViewExitReason.SCROLL_NEXT,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.impressions == 1


def test_watch_time_always_accumulates_on_fast_skip():
    """watch_time accumulates even for fast skips."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=400, completion_percent=4,
        exit_reason=PostViewExitReason.SCROLL_NEXT,
    )
    fixture.usecase.execute(command)

    raw = fixture.user_creator_features.raw_interaction_stats
    assert raw.watch_time == pytest.approx(0.4)


# ---------------------------------------------------------------------------
# views_engagement tests
# ---------------------------------------------------------------------------

def test_views_engagement_set_correctly_on_valid_view():
    """views_engagement = raw_views / raw_impressions after a valid view."""
    fixture = _Fixture(raw_stats_kwargs={"views": 2, "impressions": 5})
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
    )
    fixture.usecase.execute(command)

    # After: views=3, impressions=6 → views_engagement = 3/6 = 0.5
    for entity in [fixture.user_creator_features, fixture.post_interaction_features] + fixture.post_tag_features_list:
        assert entity.decayed_interaction_stats.views_engagement == pytest.approx(0.5)


def test_views_engagement_not_set_on_fast_skip():
    """On fast skip, views_engagement should remain unchanged (0.0 from empty)."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=100, completion_percent=1,
        exit_reason=PostViewExitReason.SCROLL_NEXT,
    )
    fixture.usecase.execute(command)

    # views=0, impressions=1 → but views_engagement not set on fast skip
    for entity in [fixture.user_creator_features, fixture.post_interaction_features] + fixture.post_tag_features_list:
        assert entity.decayed_interaction_stats.views_engagement == pytest.approx(0.0)


# ---------------------------------------------------------------------------
# Integration-level tests
# ---------------------------------------------------------------------------

def test_updates_all_three_entity_types():
    """Verify all entities are saved."""
    fixture = _Fixture(tags=["music", "dance"])
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
    )
    fixture.usecase.execute(command)

    fixture.user_creator_repo.save.assert_called_once_with(fixture.user_creator_features)
    fixture.post_tag_repo.save_all.assert_called_once_with(fixture.post_tag_features_list)
    fixture.post_interaction_repo.save.assert_called_once_with(fixture.post_interaction_features)


def test_user_semantic_embedding_updated():
    """User features should be fetched and saved for embedding update."""
    fixture = _Fixture()
    command = _make_command(
        post_id=fixture.post_id, user_id=fixture.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
    )
    fixture.usecase.execute(command)

    fixture.user_features_repo.get_user_features_for_update.assert_called_once_with(fixture.user_id)
    fixture.user_features_repo.save.assert_called_once()
    saved_features = fixture.user_features_repo.save.call_args.args[0]
    assert saved_features.has_semantic_signal is True


def test_source_weight_applied_to_embedding():
    """Different sources produce different embedding updates."""
    fixture_home = _Fixture()
    # Use non-uniform embeddings so L2 normalization produces different results for different weights
    fixture_home.post_features.semantic_embedding = [float(i) for i in range(64)]
    fixture_home.user_features.semantic_embedding = [1.0 / (i + 1) for i in range(64)]
    command_home = _make_command(
        post_id=fixture_home.post_id, user_id=fixture_home.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
        source=InteractionSource.HOME_FEED,
    )
    fixture_home.usecase.execute(command_home)

    fixture_search = _Fixture()
    fixture_search.post_features.semantic_embedding = [float(i) for i in range(64)]
    fixture_search.user_features.semantic_embedding = [1.0 / (i + 1) for i in range(64)]
    command_search = _make_command(
        post_id=fixture_search.post_id, user_id=fixture_search.user_id,
        duration_ms=10000, time_watched_ms=5000, completion_percent=50,
        source=InteractionSource.SEARCH,
    )
    fixture_search.usecase.execute(command_search)

    # Both should save, but with different source weights applied
    embedding_home = fixture_home.user_features_repo.save.call_args.args[0].semantic_embedding
    embedding_search = fixture_search.user_features_repo.save.call_args.args[0].semantic_embedding
    # SEARCH has higher multiplier (1.25) vs HOME_FEED (1.0), so embeddings differ
    assert embedding_home != embedding_search
