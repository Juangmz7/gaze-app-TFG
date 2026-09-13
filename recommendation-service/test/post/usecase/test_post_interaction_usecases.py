from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.config.constants import (
    COLLAB_REQUEST_DELETED_PENALISATION_WEIGHT,
    COLLAB_REQUEST_WEIGHT,
    COMMENT_ACCUMULATION_DECAY,
    POST_COMMENT_DELETED_PENALISATION_WEIGHT,
    POST_COMMENT_WEIGHT,
    POST_LIKE_WEIGHT,
    POST_UNLIKE_PENALISATION_WEIGHT,
)
from post.command.post_commands import (
    CreatePostCollabRequestCommand,
    CreatePostCommentCommand,
    CreatePostLikeCommand,
    DeletePostCollabRequestCommand,
    DeletePostCommentCommand,
    DeletePostLikeCommand,
)
from post.model.user_post_interactions import UserPostInteractions
from post.repository.collab_repository import CollabRepository
from post.repository.comment_post_repository import CommentPostRepository
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from post.usecase.create_post_collab_request_usecase import CreatePostCollabRequestUsecase
from post.usecase.create_post_comment_usecase import CreatePostCommentUsecase
from post.usecase.create_post_like_usecase import CreatePostLikeUsecase
from post.usecase.delete_post_collab_request_usecase import DeletePostCollabRequestUsecase
from post.usecase.delete_post_comment_usecase import DeletePostCommentUsecase
from post.usecase.delete_post_like_usecase import DeletePostLikeUsecase
from post.usecase.post_interaction_updater import PostInteractionUpdater
from rabbitmq.event.post.post_events import InteractionSource
from shared.enum.interaction_metric import InteractionMetric


pytestmark = pytest.mark.unit

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


def test_create_like_applies_like_once_and_marks_interaction():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    repository = Mock(spec=UserPostInteractionsRepository)
    repository.get.return_value = UserPostInteractions(post_id=post_id, user_id=user_id)
    usecase = CreatePostLikeUsecase(updater, repository)
    command = CreatePostLikeCommand(uuid4(), uuid4(), NOW, post_id, user_id, InteractionSource.HOME_FEED, 3)

    # Act
    usecase.execute(command)

    # Assert
    kwargs = updater.apply.call_args.kwargs
    assert kwargs["embedding_weight"] == POST_LIKE_WEIGHT
    assert kwargs["metric_updates"][0].metric is InteractionMetric.LIKES
    assert kwargs["metric_updates"][0].raw_delta == 1
    assert kwargs["metric_updates"][0].decayed_delta == 1
    saved = repository.save.call_args.args[0]
    assert saved.ever_liked is True


def test_duplicate_like_is_ignored():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    repository = Mock(spec=UserPostInteractionsRepository)
    repository.get.return_value = UserPostInteractions(post_id=post_id, user_id=user_id, ever_liked=True)
    usecase = CreatePostLikeUsecase(updater, repository)

    # Act
    usecase.execute(CreatePostLikeCommand(uuid4(), uuid4(), NOW, post_id, user_id, InteractionSource.SEARCH, 1))

    # Assert
    updater.apply.assert_not_called()
    repository.save.assert_not_called()


def test_delete_like_uses_raw_delta_decayed_penalty_and_semantic_penalty_once():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    repository = Mock(spec=UserPostInteractionsRepository)
    repository.get.return_value = UserPostInteractions(post_id=post_id, user_id=user_id)
    usecase = DeletePostLikeUsecase(updater, repository)

    # Act
    usecase.execute(DeletePostLikeCommand(uuid4(), uuid4(), NOW, post_id, user_id, InteractionSource.SEARCH, 1))

    # Assert
    kwargs = updater.apply.call_args.kwargs
    assert kwargs["embedding_weight"] == -POST_UNLIKE_PENALISATION_WEIGHT
    assert kwargs["metric_updates"][0].raw_delta == -1
    assert kwargs["metric_updates"][0].decayed_delta == -0.25
    assert repository.save.call_args.args[0].ever_unliked is True


def test_create_comment_applies_accumulation_decay_for_repeated_comments():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    comment_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get.return_value = UserPostInteractions(
        post_id=post_id,
        user_id=user_id,
        comment_count=2,
    )
    comment_repository = Mock(spec=CommentPostRepository)
    usecase = CreatePostCommentUsecase(updater, interaction_repository, comment_repository)

    # Act
    usecase.execute(CreatePostCommentCommand(uuid4(), uuid4(), NOW, comment_id, post_id, user_id))

    # Assert
    assert updater.apply.call_args.kwargs["embedding_weight"] == pytest.approx(
        POST_COMMENT_WEIGHT * (COMMENT_ACCUMULATION_DECAY ** 2)
    )
    saved = interaction_repository.save.call_args.args[0]
    assert saved.comment_count == 3
    comment_repository.save_comment_post.assert_called_once_with(comment_id, post_id)


def test_delete_comment_uses_deletion_penalty_not_full_comment_weight():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    comment_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get.return_value = UserPostInteractions(
        post_id=post_id,
        user_id=user_id,
        comment_count=1,
    )
    comment_repository = Mock(spec=CommentPostRepository)
    usecase = DeletePostCommentUsecase(updater, interaction_repository, comment_repository)

    # Act
    usecase.execute(DeletePostCommentCommand(uuid4(), uuid4(), NOW, comment_id, post_id, user_id))

    # Assert
    kwargs = updater.apply.call_args.kwargs
    assert kwargs["embedding_weight"] == pytest.approx(-POST_COMMENT_DELETED_PENALISATION_WEIGHT)
    assert kwargs["metric_updates"][0].raw_delta == -1
    assert kwargs["metric_updates"][0].decayed_delta == -0.25
    assert interaction_repository.save.call_args.args[0].comment_count == 0
    comment_repository.delete_comment_post.assert_called_once_with(comment_id)


def test_delete_comment_with_empty_counter_only_removes_comment_mapping():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    comment_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get.return_value = UserPostInteractions(post_id=post_id, user_id=user_id)
    comment_repository = Mock(spec=CommentPostRepository)
    usecase = DeletePostCommentUsecase(updater, interaction_repository, comment_repository)

    # Act
    usecase.execute(DeletePostCommentCommand(uuid4(), uuid4(), NOW, comment_id, post_id, user_id))

    # Assert
    updater.apply.assert_not_called()
    interaction_repository.save.assert_not_called()
    comment_repository.delete_comment_post.assert_called_once_with(comment_id)


def test_collab_request_updates_each_post_and_saves_only_new_interactions():
    # Arrange
    post_ids = [uuid4(), uuid4()]
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get_all_by_user.return_value = {
        post_ids[0]: UserPostInteractions(post_id=post_ids[0], user_id=user_id, ever_requested_collab=True)
    }
    collab_repository = Mock(spec=CollabRepository)
    collab_repository.find_posts_id_by_collab_id.return_value = post_ids
    usecase = CreatePostCollabRequestUsecase(updater, interaction_repository, collab_repository)

    # Act
    usecase.execute(CreatePostCollabRequestCommand(uuid4(), uuid4(), NOW, uuid4(), user_id))

    # Assert
    updater.apply.assert_called_once()
    assert updater.apply.call_args.kwargs["post_id"] == post_ids[1]
    assert updater.apply.call_args.kwargs["embedding_weight"] == COLLAB_REQUEST_WEIGHT
    saved = interaction_repository.save_all.call_args.args[0]
    assert len(saved) == 1
    assert saved[0].ever_requested_collab is True


def test_duplicate_collab_request_deletion_is_ignored_per_post():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get_all_by_user.return_value = {
        post_id: UserPostInteractions(post_id=post_id, user_id=user_id, ever_request_collab_deleted=True)
    }
    collab_repository = Mock(spec=CollabRepository)
    collab_repository.find_posts_id_by_collab_id.return_value = [post_id]
    usecase = DeletePostCollabRequestUsecase(updater, interaction_repository, collab_repository)

    # Act
    usecase.execute(DeletePostCollabRequestCommand(uuid4(), uuid4(), NOW, uuid4(), user_id))

    # Assert
    updater.apply.assert_not_called()
    interaction_repository.save_all.assert_called_once_with([])


def test_collab_request_deletion_uses_raw_decayed_and_semantic_penalties():
    # Arrange
    post_id = uuid4()
    user_id = uuid4()
    updater = Mock(spec=PostInteractionUpdater)
    interaction_repository = Mock(spec=UserPostInteractionsRepository)
    interaction_repository.get_all_by_user.return_value = {}
    collab_repository = Mock(spec=CollabRepository)
    collab_repository.find_posts_id_by_collab_id.return_value = [post_id]
    usecase = DeletePostCollabRequestUsecase(updater, interaction_repository, collab_repository)

    # Act
    usecase.execute(DeletePostCollabRequestCommand(uuid4(), uuid4(), NOW, uuid4(), user_id))

    # Assert
    kwargs = updater.apply.call_args.kwargs
    assert kwargs["embedding_weight"] == -COLLAB_REQUEST_DELETED_PENALISATION_WEIGHT
    assert kwargs["metric_updates"][0].raw_delta == -1
    assert kwargs["metric_updates"][0].decayed_delta == -0.25
