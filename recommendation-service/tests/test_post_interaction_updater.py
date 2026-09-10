from contextlib import contextmanager
from datetime import datetime, timedelta
import os
from pathlib import Path
import unittest
from uuid import uuid4

from pipeline.config.constants import FAST_SKIP_WEIGHT, POST_LIKE_WEIGHT, POST_VIEW_WEIGHT
from pipeline.model.interaction.affinity_score_processor import AffinityScoreProcessor
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.model.user.user_features import UserFeatures
from post.usecase.post_interaction_updater import PostInteractionUpdater
from rabbitmq.event.post.post_events import InteractionSource
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay, normalize_vector_0_1


def assert_float_lists_almost_equal(
        testcase: unittest.TestCase,
        actual: list[float],
        expected: list[float],
) -> None:
    testcase.assertEqual(len(actual), len(expected))
    for actual_value, expected_value in zip(actual, expected, strict=True):
        testcase.assertAlmostEqual(actual_value, expected_value)


def raw_stats(**overrides) -> RawInteractionStats:
    values = {
        "impressions": 10,
        "views": 2,
        "likes": 5,
        "comments": 4,
        "comments_likes": 3,
        "shares": 2,
        "fast_skips": 1,
        "collab_requests": 0,
        "collab_requests_accepted": 0,
        "watch_time_average_percent": 0.0,
        "watch_time": 0.0,
    }
    values.update(overrides)
    return RawInteractionStats(**values)


def decayed_stats(**overrides) -> DecayedInteractionStats:
    values = {
        "impressions": 10.0,
        "views_engagement": 2.0,
        "likes": 5.0,
        "comments": 4.0,
        "comments_likes": 3.0,
        "shares": 2.0,
        "fast_skips": 1.0,
        "collab_requests": 0.0,
        "collab_requests_accepted": 0.0,
        "watch_time_average_percent": 0.0,
        "watch_time": 0.0,
    }
    values.update(overrides)
    return DecayedInteractionStats(**values)


class FakePostFeaturesRepository:
    def __init__(self, post_features: PostFeatures):
        self.post_features = post_features

    def get_post_features(self, post_id):
        return self.post_features if self.post_features.post_id == post_id else None


class FakeUserCreatorFeaturesRepository:
    def __init__(self, features: UserCreatorFeatures):
        self.features = features
        self.locked_with = None
        self.saved = None

    def get_user_creator_features_for_update(self, user_id, creator_id):
        self.locked_with = (user_id, creator_id)
        if self.features.user_id == user_id and self.features.creator_id == creator_id:
            return self.features
        return None

    def save(self, user_creator_features):
        self.saved = user_creator_features


class FakePostTagFeaturesRepository:
    def __init__(self, features: list[PostTagFeatures]):
        self.features = features
        self.locked_with = None
        self.saved = None

    def get_post_tag_features_for_update(self, user_id, tags):
        self.locked_with = (user_id, tags)
        return [
            feature
            for feature in self.features
            if feature.user_id == user_id and feature.tag_name in tags
        ]

    def save_all(self, post_tag_features):
        self.saved = post_tag_features


class FakeUserFeaturesRepository:
    def __init__(self, user_features: UserFeatures):
        self.user_features = user_features
        self.locked_user_id = None
        self.saved = None

    def get_user_features_for_update(self, user_id):
        self.locked_user_id = user_id
        return self.user_features if self.user_features.user_id == user_id else None

    def save(self, user_features):
        self.saved = user_features


class FakeTransactionManager:
    def __init__(self):
        self.entered = 0
        self.exited = 0

    @contextmanager
    def transaction(self):
        self.entered += 1
        try:
            yield
        finally:
            self.exited += 1


def build_updater(t0: datetime):
    post_id = uuid4()
    user_id = uuid4()
    creator_id = uuid4()
    post_features = PostFeatures(
        post_id=post_id,
        creator_id=creator_id,
        collab_id=None,
        collab_title=None,
        description="post",
        tags=["music"],
        tagged_users_ids=[],
        semantic_embedding=[0.1, 0.2, 0.4],
        created_at=t0,
    )
    creator_features = UserCreatorFeatures(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(),
        decayed_interaction_stats=decayed_stats(),
        last_updated_at=t0,
    )
    tag_features = PostTagFeatures(
        user_id=user_id,
        tag_name="music",
        raw_interaction_stats=raw_stats(),
        decayed_interaction_stats=decayed_stats(),
        last_updated_at=t0,
    )
    user_features = UserFeatures(
        user_id=user_id,
        semantic_embedding=[0.3, 0.4, 0.5],
        last_updated_at=t0,
    )
    transaction_manager = FakeTransactionManager()
    creator_repository = FakeUserCreatorFeaturesRepository(creator_features)
    tag_repository = FakePostTagFeaturesRepository([tag_features])
    user_repository = FakeUserFeaturesRepository(user_features)
    updater = PostInteractionUpdater(
        user_creator_features_repository=creator_repository,
        post_tag_features_repository=tag_repository,
        user_features_repository=user_repository,
        post_features_repository=FakePostFeaturesRepository(post_features),
        transaction_manager=transaction_manager,
    )
    return (
        updater,
        post_features,
        creator_features,
        tag_features,
        user_features,
        creator_repository,
        tag_repository,
        user_repository,
        transaction_manager,
    )


class PostInteractionUpdaterTests(unittest.TestCase):
    def test_like_updates_raw_decayed_affinity_and_weighted_semantic_embedding(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        (
            updater,
            post_features,
            creator_features,
            _tag_features,
            user_features,
            creator_repository,
            _tag_repository,
            user_repository,
            transaction_manager,
        ) = build_updater(t0)
        factor = decay(t0, t1)

        updater.apply(
            post_id=post_features.post_id,
            user_id=user_features.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.LIKES,
                    raw_delta=1,
                    decayed_delta=1,
                )
            ],
            embedding_weight=POST_LIKE_WEIGHT,
            occurred_at=t1,
            source=InteractionSource.HOME_FEED,
        )

        self.assertEqual(creator_repository.locked_with, (user_features.user_id, post_features.creator_id))
        self.assertEqual(user_repository.locked_user_id, user_features.user_id)
        self.assertEqual(transaction_manager.entered, 1)
        self.assertEqual(transaction_manager.exited, 1)
        self.assertEqual(creator_features.raw_interaction_stats.likes, 6)
        self.assertAlmostEqual(creator_features.decayed_interaction_stats.likes, 5.0 * factor + 1)
        self.assertAlmostEqual(creator_features.decayed_interaction_stats.comments, 4.0 * factor)
        self.assertAlmostEqual(
            creator_features.affinity_score,
            AffinityScoreProcessor(
                creator_features.decayed_interaction_stats
            ).calculate_affinity_score(),
        )

        expected_embedding = normalize_vector_0_1([
            previous * factor + post * POST_LIKE_WEIGHT
            for previous, post in zip(
                [0.3, 0.4, 0.5],
                post_features.semantic_embedding,
                strict=True,
            )
        ])
        assert_float_lists_almost_equal(self, user_features.semantic_embedding, expected_embedding)
        self.assertEqual(user_features.last_updated_at, t1)

    def test_view_metric_can_use_watch_ratio_as_decayed_delta_without_weighting_counter(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        updater, post_features, creator_features, *_ = build_updater(t0)
        factor = decay(t0, t1)
        watch_ratio = 0.42

        updater.apply(
            post_id=post_features.post_id,
            user_id=creator_features.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.VIEW_ENGAGEMENT,
                    raw_delta=1,
                    decayed_delta=watch_ratio,
                )
            ],
            embedding_weight=POST_VIEW_WEIGHT * watch_ratio,
            occurred_at=t1,
        )

        self.assertEqual(creator_features.raw_interaction_stats.views, 3)
        self.assertAlmostEqual(
            creator_features.decayed_interaction_stats.views_engagement,
            2.0 * factor + watch_ratio,
        )

    def test_fast_skip_updates_stats_and_applies_negative_semantic_weight(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        updater, post_features, creator_features, _tag_features, user_features, *_ = build_updater(t0)
        factor = decay(t0, t1)

        updater.apply(
            post_id=post_features.post_id,
            user_id=user_features.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.FAST_SKIPS,
                    raw_delta=1,
                    decayed_delta=1,
                )
            ],
            embedding_weight=FAST_SKIP_WEIGHT,
            occurred_at=t1,
        )

        self.assertEqual(creator_features.raw_interaction_stats.fast_skips, 2)
        self.assertAlmostEqual(
            creator_features.decayed_interaction_stats.fast_skips,
            1.0 * factor + 1,
        )
        expected_embedding = normalize_vector_0_1([
            previous * factor + post * FAST_SKIP_WEIGHT
            for previous, post in zip(
                [0.3, 0.4, 0.5],
                post_features.semantic_embedding,
                strict=True,
            )
        ])
        assert_float_lists_almost_equal(self, user_features.semantic_embedding, expected_embedding)

    def test_decayed_stats_apply_decay_to_all_metrics_before_incrementing_target_metric(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        features = UserCreatorFeatures(
            user_id=uuid4(),
            creator_id=uuid4(),
            raw_interaction_stats=raw_stats(likes=0),
            decayed_interaction_stats=decayed_stats(likes=5.0, comments=4.0),
            last_updated_at=t0,
        )
        factor = decay(t0, t1)

        features.apply_interaction_updates(
            [
                InteractionMetricUpdate(
                    metric=InteractionMetric.LIKES,
                    raw_delta=1,
                    decayed_delta=1,
                )
            ],
            t1,
        )

        self.assertAlmostEqual(features.decayed_interaction_stats.likes, 5.0 * factor + 1)
        self.assertAlmostEqual(features.decayed_interaction_stats.comments, 4.0 * factor)
        self.assertEqual(features.last_updated_at, t1)

    def test_user_semantic_profile_is_loaded_for_update_and_old_vector_is_decayed(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        updater, post_features, _creator_features, _tag_features, user_features, *_repos = build_updater(t0)
        factor = decay(t0, t1)

        updater.apply(
            post_id=post_features.post_id,
            user_id=user_features.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.LIKES,
                    raw_delta=1,
                    decayed_delta=1,
                )
            ],
            embedding_weight=POST_LIKE_WEIGHT,
            occurred_at=t1,
        )

        expected_embedding = normalize_vector_0_1([
            previous * factor + post * POST_LIKE_WEIGHT
            for previous, post in zip(
                [0.3, 0.4, 0.5],
                post_features.semantic_embedding,
                strict=True,
            )
        ])
        assert_float_lists_almost_equal(self, user_features.semantic_embedding, expected_embedding)
        self.assertEqual(user_features.last_updated_at, t1)

    def test_multiple_metrics_can_be_updated_in_one_interaction_transaction(self):
        t0 = datetime(2026, 1, 1, 12, 0, 0)
        t1 = t0 + timedelta(seconds=1)
        updater, post_features, creator_features, *_ = build_updater(t0)
        factor = decay(t0, t1)

        updater.apply(
            post_id=post_features.post_id,
            user_id=creator_features.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.IMPRESSIONS,
                    raw_delta=1,
                    decayed_delta=1,
                ),
                InteractionMetricUpdate(
                    metric=InteractionMetric.VIEW_ENGAGEMENT,
                    raw_delta=1,
                    decayed_delta=0.5,
                ),
            ],
            embedding_weight=POST_VIEW_WEIGHT * 0.5,
            occurred_at=t1,
        )

        self.assertEqual(creator_features.raw_interaction_stats.impressions, 11)
        self.assertEqual(creator_features.raw_interaction_stats.views, 3)
        self.assertAlmostEqual(
            creator_features.decayed_interaction_stats.impressions,
            10.0 * factor + 1,
        )
        self.assertAlmostEqual(
            creator_features.decayed_interaction_stats.views_engagement,
            2.0 * factor + 0.5,
        )


class CapturingSession:
    def __init__(self):
        self.statement = None

    def scalars(self, statement):
        self.statement = statement
        return self

    def scalar(self, statement):
        self.statement = statement
        return None

    def first(self):
        return None

    def all(self):
        return []


class CapturingSessionProvider:
    def __init__(self):
        self.session_instance = CapturingSession()

    @contextmanager
    def session(self):
        yield self.session_instance


class RepositoryLockingTests(unittest.TestCase):
    def test_repository_implementations_keep_lock_aware_paths(self):
        project_root = Path(__file__).resolve().parents[1]
        repository_paths = [
            project_root / "src/pipeline/repository/impl/sql_alchemy_user_creator_features_repository.py",
            project_root / "src/pipeline/repository/impl/sql_alchemy_post_tag_features_repository.py",
            project_root / "src/pipeline/repository/impl/sql_alchemy_user_features_repository.py",
        ]

        for repository_path in repository_paths:
            source = repository_path.read_text()
            self.assertIn("_for_update", source)
            self.assertIn("with_for_update()", source)

    def test_repository_for_update_paths_build_row_locking_queries(self):
        os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")
        try:
            from pipeline.repository.impl.sql_alchemy_post_tag_features_repository import (
                SqlAlchemyPostTagFeaturesRepository,
            )
            from pipeline.repository.impl.sql_alchemy_user_creator_features_repository import (
                SqlAlchemyUserCreatorFeaturesRepository,
            )
            from pipeline.repository.impl.sql_alchemy_user_features_repository import (
                SqlAlchemyUserFeaturesRepository,
            )
        except ModuleNotFoundError as exc:
            if exc.name == "sqlalchemy":
                self.skipTest("SQLAlchemy is not installed")
            raise

        user_id = uuid4()
        creator_id = uuid4()

        creator_provider = CapturingSessionProvider()
        SqlAlchemyUserCreatorFeaturesRepository(
            creator_provider
        ).get_user_creator_features_for_update(user_id, creator_id)
        self.assertIsNotNone(creator_provider.session_instance.statement._for_update_arg)

        tag_provider = CapturingSessionProvider()
        SqlAlchemyPostTagFeaturesRepository(
            tag_provider
        ).get_post_tag_features_for_update(user_id, ["music", "art"])
        self.assertIsNotNone(tag_provider.session_instance.statement._for_update_arg)

        user_provider = CapturingSessionProvider()
        SqlAlchemyUserFeaturesRepository(user_provider).get_user_features_for_update(user_id)
        self.assertIsNotNone(user_provider.session_instance.statement._for_update_arg)
