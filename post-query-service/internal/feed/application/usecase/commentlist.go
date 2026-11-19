package usecase

import (
	"context"
	"errors"
	"log/slog"

	"github.com/google/uuid"
)

// ErrCommentsPostNotFound is returned when the post whose comments are asked for
// cannot be shown to the caller: it is not in the read model (never projected,
// deleted or banned), its creator is not, or there is a block between the caller and
// its creator. One error for all of them, so the answer never reveals a block.
var ErrCommentsPostNotFound = errors.New("post not found")

// ErrCommentNotFound is returned when the parent comment of a replies request is not
// a comment of that post the caller may see: missing, of another post, or written by
// a user with a block with the caller (or no longer projected).
var ErrCommentNotFound = errors.New("comment not found")

// Not-found reasons, logged and never answered.
const (
	commentsReasonPostMissing         = "post_missing"
	commentsReasonPostCreatorMissing  = "creator_missing"
	commentsReasonPostCreatorBlocked  = "blocked"
	commentsReasonParentMissing       = "comment_missing"
	commentsReasonParentOfAnotherPost = "comment_of_another_post"
	commentsReasonParentAuthorBlocked = "comment_author_blocked"
	commentsReasonParentAuthorMissing = "comment_author_missing"
	commentsDropReasonAuthorMissing   = "author_missing"
)

// commentsReader is what both comment lists read besides the comments themselves: one
// projected query per method. Implemented by feed/infrastructure/mongo.HydrationRepository.
type commentsReader interface {
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
	FindCreators(ctx context.Context, userIDs []string) ([]FeedCreator, error)
	FindAllBlockRelatedUserIDs(ctx context.Context, viewerID string) ([]string, error)
	FindLikedCommentIDs(ctx context.Context, viewerID string, commentIDs []string) ([]string, error)
}

// commentListRequest is what both lists share in their input, already validated.
type commentListRequest struct {
	userID        uuid.UUID
	postID        uuid.UUID
	limit         int
	correlationID string
}

// validate checks the domain rules every comment list input must meet.
func (r commentListRequest) validate() error {
	if r.userID == uuid.Nil {
		return errors.New("user id is required")
	}
	if r.postID == uuid.Nil {
		return errors.New("post id is required")
	}

	return validLimit(r.limit)
}

// visiblePost is a post the caller may read the comments of, with the users that
// have a block with the caller in either direction.
type visiblePost struct {
	post    FeedPost
	blocked map[string]struct{}
}

// findVisiblePost reads the post and every block of the caller (two queries). It
// returns a not-found reason, and no error, when the post is missing or its creator
// has a block with the caller. The creator's own projection is checked later, with
// the authors, so the users are read once.
func findVisiblePost(ctx context.Context, reader commentsReader, viewerID, postID string) (visiblePost, string, error) {
	posts, err := reader.FindPosts(ctx, []string{postID})
	if err != nil {
		return visiblePost{}, "", err
	}
	post, found := findFeedPost(posts, postID)
	if !found {
		return visiblePost{}, commentsReasonPostMissing, nil
	}

	blocked, err := reader.FindAllBlockRelatedUserIDs(ctx, viewerID)
	if err != nil {
		return visiblePost{}, "", err
	}
	visible := visiblePost{post: post, blocked: idSet(blocked)}
	if _, isBlocked := visible.blocked[post.CreatorID]; isBlocked {
		return visiblePost{}, commentsReasonPostCreatorBlocked, nil
	}

	return visible, "", nil
}

// blockedIDs lists the users whose comments the query must leave out.
func (v visiblePost) blockedIDs() []string {
	ids := make([]string, 0, len(v.blocked))
	for id := range v.blocked {
		ids = append(ids, id)
	}

	return ids
}

// commentRows is one read of a list: limit+1 rows, so the extra row only says that a
// next page exists.
type commentRows struct {
	rows  []ContentComment
	limit int
}

// page keeps the first limit rows and, when more were read, the cursor of the last
// row kept, made by encode.
func (r commentRows) page(encode func(ContentComment) string) ([]ContentComment, *string) {
	if len(r.rows) <= r.limit {
		return r.rows, nil
	}
	kept := r.rows[:r.limit]
	next := encode(kept[len(kept)-1])

	return kept, &next
}

// commentHydration is the users and the caller's likes around one page.
type commentHydration struct {
	users map[string]FeedCreator
	liked map[string]struct{}
}

// hydrateComments reads the authors of rows plus extraUserIDs with one $in on users,
// and which of rows the caller liked with one $in on comment_likes, so a page never
// costs a query per comment.
func hydrateComments(ctx context.Context, reader commentsReader, viewerID string, rows []ContentComment, extraUserIDs ...string) (commentHydration, error) {
	userIDs := make([]string, 0, len(rows)+len(extraUserIDs))
	seen := make(map[string]struct{}, cap(userIDs))
	commentIDs := make([]string, 0, len(rows))
	addUser := func(id string) {
		if _, found := seen[id]; !found {
			seen[id] = struct{}{}
			userIDs = append(userIDs, id)
		}
	}
	for _, id := range extraUserIDs {
		addUser(id)
	}
	for _, row := range rows {
		addUser(row.AuthorID)
		commentIDs = append(commentIDs, row.CommentID)
	}

	users, err := reader.FindCreators(ctx, userIDs)
	if err != nil {
		return commentHydration{}, err
	}
	liked, err := reader.FindLikedCommentIDs(ctx, viewerID, commentIDs)
	if err != nil {
		return commentHydration{}, err
	}

	hydration := commentHydration{users: make(map[string]FeedCreator, len(users)), liked: idSet(liked)}
	for _, user := range users {
		hydration.users[user.UserID] = user
	}

	return hydration, nil
}

// items builds the page's items in the order read. A comment whose author left the
// users read model (a deleted user) cannot carry a UserSummary and is left out; the
// cursor still comes from the last row read, so the next page is unaffected.
func (h commentHydration) items(ctx context.Context, logger *slog.Logger, request commentListRequest, rows []ContentComment) []CommentItem {
	items := make([]CommentItem, 0, len(rows))
	dropped := 0
	for _, row := range rows {
		author, found := h.users[row.AuthorID]
		if !found {
			dropped++
			continue
		}
		_, liked := h.liked[row.CommentID]
		items = append(items, CommentItem{Comment: row, Author: author, LikedByMe: liked})
	}
	if dropped > 0 {
		logger.WarnContext(ctx, "comments left out of a page", "correlation_id", request.correlationID, "userId", request.userID,
			"postId", request.postID, "reason", commentsDropReasonAuthorMissing, "count", dropped)
	}

	return items
}

// logNotFound logs why a list is not shown, which the answer never says.
func logNotFound(ctx context.Context, logger *slog.Logger, request commentListRequest, reason string, attributes ...any) {
	logger.InfoContext(ctx, "comments not shown to the caller", append([]any{"correlation_id", request.correlationID, "userId", request.userID,
		"postId", request.postID, "reason", reason}, attributes...)...)
}
