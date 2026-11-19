package mongo

import (
	"context"
	"errors"
	"fmt"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
)

// Read models of the comment lists (task 48). The comment context writes them and
// creates their indexes (comment/infrastructure/mongo EnsureIndexes).
const (
	commentsCollectionName     = "post_comments"
	commentLikesCollectionName = "comment_likes"
)

// topLevelCommentsSort is the order of the top-level list. Its keys, after the
// post_id and reply_to equalities, are exactly those of topLevelCommentsIndex, so the
// index walk is the order and there is no SORT stage.
var topLevelCommentsSort = bson.D{
	{Key: "like_count", Value: -1},
	{Key: "reply_count", Value: -1},
	{Key: "created_at", Value: -1},
	{Key: "comment_id", Value: -1},
}

// topLevelCommentsIndex is post_comments' index for the top-level list, hinted so the
// planner never picks another plan.
var topLevelCommentsIndex = bson.D{
	{Key: "post_id", Value: 1},
	{Key: "reply_to", Value: 1},
	{Key: "like_count", Value: -1},
	{Key: "reply_count", Value: -1},
	{Key: "created_at", Value: -1},
	{Key: "comment_id", Value: -1},
}

// repliesSort is the conversation order of the replies list, the keys of repliesIndex
// after the reply_to equality.
var repliesSort = bson.D{
	{Key: "created_at", Value: 1},
	{Key: "comment_id", Value: 1},
}

// repliesIndex is post_comments' index for the replies list, hinted.
var repliesIndex = bson.D{
	{Key: "reply_to", Value: 1},
	{Key: "created_at", Value: 1},
	{Key: "comment_id", Value: 1},
}

// commentProjection is every field a comment page returns.
var commentProjection = bson.D{
	{Key: "comment_id", Value: 1},
	{Key: "post_id", Value: 1},
	{Key: "user_id", Value: 1},
	{Key: "content", Value: 1},
	{Key: "reply_to", Value: 1},
	{Key: "like_count", Value: 1},
	{Key: "reply_count", Value: 1},
	{Key: "created_at", Value: 1},
	{Key: "updated_at", Value: 1},
}

// FindTopLevelComments reads one page of a post's top-level comments (reply_to stored
// as ""). With a cursor, "sort tuple strictly after the cursor" is split into the
// ranges a compound comparison expands to, read in sort order (see findKeysetPage), so
// page N costs what page 1 costs: never a skip.
func (r *HydrationRepository) FindTopLevelComments(ctx context.Context, query usecase.TopLevelCommentsQuery) ([]usecase.ContentComment, error) {
	if query.Limit < 1 {
		return nil, fmt.Errorf("find top-level comments: limit %d must be positive", query.Limit)
	}

	base := appendExcludedAuthors(bson.D{
		{Key: "post_id", Value: query.PostID},
		{Key: "reply_to", Value: ""},
	}, query.ExcludedAuthorIDs)
	ranges := []bson.D{nil}
	if after := query.After; after != nil {
		// Everything after (L, R, C, I) in descending order, nearest range first.
		ranges = []bson.D{
			{
				{Key: "like_count", Value: after.LikeCount},
				{Key: "reply_count", Value: after.ReplyCount},
				{Key: "created_at", Value: after.CreatedAt},
				{Key: "comment_id", Value: bson.D{{Key: "$lt", Value: after.CommentID}}},
			},
			{
				{Key: "like_count", Value: after.LikeCount},
				{Key: "reply_count", Value: after.ReplyCount},
				{Key: "created_at", Value: bson.D{{Key: "$lt", Value: after.CreatedAt}}},
			},
			{
				{Key: "like_count", Value: after.LikeCount},
				{Key: "reply_count", Value: bson.D{{Key: "$lt", Value: after.ReplyCount}}},
			},
			{{Key: "like_count", Value: bson.D{{Key: "$lt", Value: after.LikeCount}}}},
		}
	}

	return r.findKeysetPage(ctx, base, ranges, topLevelCommentsSort, topLevelCommentsIndex, query.Limit)
}

// FindReplies reads one page of the replies of a comment, oldest first, with the same
// keyset shape as FindTopLevelComments on the replies index.
func (r *HydrationRepository) FindReplies(ctx context.Context, query usecase.RepliesQuery) ([]usecase.ContentComment, error) {
	if query.Limit < 1 {
		return nil, fmt.Errorf("find replies: limit %d must be positive", query.Limit)
	}
	if query.ParentID == "" {
		return nil, errors.New("find replies: parent id is required")
	}

	base := appendExcludedAuthors(bson.D{{Key: "reply_to", Value: query.ParentID}}, query.ExcludedAuthorIDs)
	ranges := []bson.D{nil}
	if after := query.After; after != nil {
		// Everything after (C, I) in ascending order, nearest range first.
		ranges = []bson.D{
			{
				{Key: "created_at", Value: after.CreatedAt},
				{Key: "comment_id", Value: bson.D{{Key: "$gt", Value: after.CommentID}}},
			},
			{{Key: "created_at", Value: bson.D{{Key: "$gt", Value: after.CreatedAt}}}},
		}
	}

	return r.findKeysetPage(ctx, base, ranges, repliesSort, repliesIndex, query.Limit)
}

// FindComment reads one comment by id, and whether it is projected.
func (r *HydrationRepository) FindComment(ctx context.Context, commentID string) (usecase.ContentComment, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	var document usecase.CommentDocument
	err := r.db.Collection(commentsCollectionName).FindOne(ctx, bson.D{{Key: "comment_id", Value: commentID}},
		options.FindOne().SetProjection(commentProjection)).Decode(&document)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return usecase.ContentComment{}, false, nil
	}
	if err != nil {
		return usecase.ContentComment{}, false, fmt.Errorf("find comment %s: %w", commentID, err)
	}

	return usecase.CommentFromDocument(document), true, nil
}

// FindLikedCommentIDs returns which of commentIDs the viewer liked: one $in on
// comment_likes, served by its unique (comment_id, user_id) index.
func (r *HydrationRepository) FindLikedCommentIDs(ctx context.Context, viewerID string, commentIDs []string) ([]string, error) {
	if len(commentIDs) == 0 {
		return nil, nil
	}

	filter := bson.D{{Key: "comment_id", Value: in(commentIDs)}, {Key: "user_id", Value: viewerID}}
	var documents []struct {
		CommentID string `bson:"comment_id"`
	}
	if err := r.find(ctx, commentLikesCollectionName, filter, bson.D{{Key: "comment_id", Value: 1}}, &documents); err != nil {
		return nil, err
	}

	liked := make([]string, 0, len(documents))
	for _, document := range documents {
		liked = append(liked, document.CommentID)
	}

	return liked, nil
}

// FindAllBlockRelatedUserIDs returns every user with a block with the viewer, in
// either direction: one $or whose branches are served by user_blocks' two compound
// indexes. The comment lists exclude their comments in the query, so a page is never
// emptied after the fact.
func (r *HydrationRepository) FindAllBlockRelatedUserIDs(ctx context.Context, viewerID string) ([]string, error) {
	filter := bson.D{{Key: "$or", Value: bson.A{
		bson.D{{Key: "blocker_user_id", Value: viewerID}},
		bson.D{{Key: "blocked_user_id", Value: viewerID}},
	}}}

	var documents []struct {
		BlockerUserID string `bson:"blocker_user_id"`
		BlockedUserID string `bson:"blocked_user_id"`
	}
	err := r.find(ctx, blocksCollectionName, filter, bson.D{
		{Key: "blocker_user_id", Value: 1},
		{Key: "blocked_user_id", Value: 1},
	}, &documents)
	if err != nil {
		return nil, err
	}

	related := make([]string, 0, len(documents))
	for _, document := range documents {
		if document.BlockerUserID == viewerID {
			related = append(related, document.BlockedUserID)
			continue
		}
		related = append(related, document.BlockerUserID)
	}

	return related, nil
}

// findKeysetPage reads up to limit comments matching base, taking the ranges in order
// until the page is full (see readKeysetRanges).
func (r *HydrationRepository) findKeysetPage(ctx context.Context, base bson.D, ranges []bson.D, sort, hint bson.D, limit int) ([]usecase.ContentComment, error) {
	return readKeysetRanges(base, ranges, limit, func(filter bson.D, remaining int) ([]usecase.ContentComment, error) {
		return r.findComments(ctx, filter, sort, hint, remaining)
	})
}

// findComments runs one page query: projected, sorted along the hinted index, limited.
func (r *HydrationRepository) findComments(ctx context.Context, filter, sort, hint bson.D, limit int) ([]usecase.ContentComment, error) {
	ctx, cancel := context.WithTimeout(ctx, operationTimeout)
	defer cancel()

	opts := options.Find().
		SetProjection(commentProjection).
		SetSort(sort).
		SetHint(hint).
		SetLimit(int64(limit))
	cursor, err := r.db.Collection(commentsCollectionName).Find(ctx, filter, opts)
	if err != nil {
		return nil, fmt.Errorf("find in %s: %w", commentsCollectionName, err)
	}
	var documents []usecase.CommentDocument
	if err := cursor.All(ctx, &documents); err != nil {
		return nil, fmt.Errorf("read from %s: %w", commentsCollectionName, err)
	}

	comments := make([]usecase.ContentComment, 0, len(documents))
	for _, document := range documents {
		comments = append(comments, usecase.CommentFromDocument(document))
	}

	return comments, nil
}

// appendExcludedAuthors leaves out the comments of authors, when there are any.
func appendExcludedAuthors(filter bson.D, authors []string) bson.D {
	if len(authors) == 0 {
		return filter
	}

	return append(filter, bson.E{Key: "user_id", Value: bson.D{{Key: "$nin", Value: authors}}})
}
