from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.model.user.user_features import UserFeatures
from post.model.collab import Collab
from post.model.user_post_comment_interaction import UserPostCommentInteraction
from post.model.user_post_interactions import UserPostInteractions
from rabbitmq.event.post.post_events import CollabStatus

from impl.repo_impl.models import (
    CollabRecord,
    PostFeaturesRecord,
    PostTagFeaturesRecord,
    UserCreatorFeaturesRecord,
    UserFeaturesRecord,
    UserPostCommentInteractionRecord,
    UserPostInteractionsRecord,
)


def raw_stats_from_record(record) -> RawInteractionStats:
    return RawInteractionStats(
        impressions=record.raw_impressions,
        views=record.raw_views,
        likes=record.raw_likes,
        comments=record.raw_comments,
        commentsLikes=record.raw_comments_likes,
        shares=record.raw_shares,
        fast_skips=record.raw_fast_skips,
        collab_requests=record.raw_collab_requests,
        collab_requests_accepted=record.raw_collab_requests_accepted,
        watch_time_average_percent=record.raw_watch_time_average_percent,
        watch_time=record.raw_watch_time,
    )


def decayed_stats_from_record(record) -> DecayedInteractionStats:
    return DecayedInteractionStats(
        impressions=record.decayed_impressions,
        views_engagement=record.decayed_views_engagement,
        likes=record.decayed_likes,
        comments=record.decayed_comments,
        commentsLikes=record.decayed_comments_likes,
        shares=record.decayed_shares,
        fast_skips=record.decayed_fast_skips,
        collab_requests=record.decayed_collab_requests,
        collab_requests_accepted=record.decayed_collab_requests_accepted,
        watch_time_average_percent=record.decayed_watch_time_average_percent,
        watch_time=record.decayed_watch_time,
    )


def raw_stats_values(stats: RawInteractionStats) -> dict[str, object]:
    return {
        "raw_impressions": stats.impressions,
        "raw_views": stats.views,
        "raw_likes": stats.likes,
        "raw_comments": stats.comments,
        "raw_comments_likes": stats.commentsLikes,
        "raw_shares": stats.shares,
        "raw_fast_skips": stats.fast_skips,
        "raw_collab_requests": stats.collab_requests,
        "raw_collab_requests_accepted": stats.collab_requests_accepted,
        "raw_watch_time_average_percent": stats.watch_time_average_percent,
        "raw_watch_time": stats.watch_time,
    }


def decayed_stats_values(stats: DecayedInteractionStats) -> dict[str, object]:
    return {
        "decayed_impressions": stats.impressions,
        "decayed_views_engagement": stats.views_engagement,
        "decayed_likes": stats.likes,
        "decayed_comments": stats.comments,
        "decayed_comments_likes": stats.commentsLikes,
        "decayed_shares": stats.shares,
        "decayed_fast_skips": stats.fast_skips,
        "decayed_collab_requests": stats.collab_requests,
        "decayed_collab_requests_accepted": stats.collab_requests_accepted,
        "decayed_watch_time_average_percent": stats.watch_time_average_percent,
        "decayed_watch_time": stats.watch_time,
    }


def post_features_from_record(record: PostFeaturesRecord) -> PostFeatures:
    return PostFeatures(
        post_id=record.post_id,
        creator_id=record.creator_id,
        collab_id=record.collab_id,
        collab_title=record.collab_title,
        description=record.description,
        tags=list(record.tags),
        tagged_users_ids=list(record.tagged_users_ids),
        semantic_embedding=list(record.semantic_embedding),
        created_at=record.created_at,
    )


def post_features_values(post_features: PostFeatures) -> dict[str, object]:
    return {
        "post_id": post_features.post_id,
        "creator_id": post_features.creator_id,
        "collab_id": post_features.collab_id,
        "collab_title": post_features.collab_title,
        "description": post_features.description,
        "tags": list(post_features.tags),
        "tagged_users_ids": list(post_features.tagged_users_ids),
        "semantic_embedding": list(post_features.semantic_embedding),
        "created_at": post_features.created_at,
    }


def user_features_from_record(record: UserFeaturesRecord) -> UserFeatures:
    return UserFeatures(
        user_id=record.user_id,
        semantic_embedding=list(record.semantic_embedding),
        last_updated_at=record.last_updated_at,
    )


def user_features_values(user_features: UserFeatures) -> dict[str, object]:
    return {
        "user_id": user_features.user_id,
        "semantic_embedding": list(user_features.semantic_embedding),
        "last_updated_at": user_features.last_updated_at,
    }


def user_creator_features_from_record(record: UserCreatorFeaturesRecord) -> UserCreatorFeatures:
    return UserCreatorFeatures(
        user_id=record.user_id,
        creator_id=record.creator_id,
        raw_interaction_stats=raw_stats_from_record(record),
        decayed_interaction_stats=decayed_stats_from_record(record),
        last_updated_at=record.last_updated_at,
    )


def user_creator_features_values(features: UserCreatorFeatures) -> dict[str, object]:
    return {
        "user_id": features.user_id,
        "creator_id": features.creator_id,
        **raw_stats_values(features.raw_interaction_stats),
        **decayed_stats_values(features.decayed_interaction_stats),
        "affinity_score": features.affinity_score,
        "last_updated_at": features.last_updated_at,
    }


def post_tag_features_from_record(record: PostTagFeaturesRecord) -> PostTagFeatures:
    return PostTagFeatures(
        user_id=record.user_id,
        tag_name=record.tag_name,
        raw_interaction_stats=raw_stats_from_record(record),
        decayed_interaction_stats=decayed_stats_from_record(record),
        last_updated_at=record.last_updated_at,
    )


def post_tag_features_values(features: PostTagFeatures) -> dict[str, object]:
    return {
        "user_id": features.user_id,
        "tag_name": features.tag_name,
        **raw_stats_values(features.raw_interaction_stats),
        **decayed_stats_values(features.decayed_interaction_stats),
        "affinity_score": features.affinity_score,
        "last_updated_at": features.last_updated_at,
    }


def collab_from_record(record: CollabRecord) -> Collab:
    return Collab(
        collab_id=record.collab_id,
        title=record.title,
        created_by=record.created_by,
        status=CollabStatus(record.status),
        created_at=record.created_at,
    )


def collab_values(collab: Collab) -> dict[str, object]:
    return {
        "collab_id": collab.collab_id,
        "title": collab.title,
        "created_by": collab.created_by,
        "status": collab.status.value,
        "created_at": collab.created_at,
    }


def user_post_interactions_from_record(
    record: UserPostInteractionsRecord,
) -> UserPostInteractions:
    return UserPostInteractions(
        post_id=record.post_id,
        user_id=record.user_id,
        ever_liked=record.ever_liked,
        ever_unliked=record.ever_unliked,
        ever_unshared=record.ever_unshared,
        ever_request_collab_deleted=record.ever_request_collab_deleted,
        ever_shared=record.ever_shared,
        ever_requested_collab=record.ever_requested_collab,
        comment_count=record.comment_count,
    )


def user_post_interactions_values(
    interactions: UserPostInteractions,
) -> dict[str, object]:
    return {
        "post_id": interactions.post_id,
        "user_id": interactions.user_id,
        "ever_liked": interactions.ever_liked,
        "ever_unliked": interactions.ever_unliked,
        "ever_unshared": interactions.ever_unshared,
        "ever_request_collab_deleted": interactions.ever_request_collab_deleted,
        "ever_shared": interactions.ever_shared,
        "ever_requested_collab": interactions.ever_requested_collab,
        "comment_count": interactions.comment_count,
    }


def user_post_comment_interaction_from_record(
    record: UserPostCommentInteractionRecord,
) -> UserPostCommentInteraction:
    return UserPostCommentInteraction(
        comment_id=record.comment_id,
        user_id=record.user_id,
        ever_liked=record.ever_liked,
        ever_unliked=record.ever_unliked,
    )


def user_post_comment_interaction_values(
    interaction: UserPostCommentInteraction,
) -> dict[str, object]:
    return {
        "comment_id": interaction.comment_id,
        "user_id": interaction.user_id,
        "ever_liked": interaction.ever_liked,
        "ever_unliked": interaction.ever_unliked,
    }
