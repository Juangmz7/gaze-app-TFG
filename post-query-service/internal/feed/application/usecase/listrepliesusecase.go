package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// ListRepliesInput asks for one page of the replies of a comment. UserID comes from
// the authenticated context; After is the decoded cursor, nil for the first page.
type ListRepliesInput struct {
	UserID        uuid.UUID
	PostID        uuid.UUID
	CommentID     uuid.UUID
	After         *RepliesCursor
	Limit         int
	CorrelationID string
}

// RepliesQuery reads, in conversation order (created_at, comment_id, ascending), up to
// Limit replies of ParentID strictly after After, leaving out the replies of
// ExcludedAuthorIDs.
type RepliesQuery struct {
	ParentID          string
	ExcludedAuthorIDs []string
	After             *RepliesCursor
	Limit             int
}

// ListRepliesRepository reads what the replies list is built from. Implemented by
// feed/infrastructure/mongo.HydrationRepository.
type ListRepliesRepository interface {
	commentsReader
	FindComment(ctx context.Context, commentID string) (ContentComment, bool, error)
	FindReplies(ctx context.Context, query RepliesQuery) ([]ContentComment, error)
}

// ListRepliesUsecase serves the replies of one comment, oldest first, keyset paged.
type ListRepliesUsecase struct {
	repository ListRepliesRepository
	logger     *slog.Logger
}

// NewListReplies creates a ListRepliesUsecase.
func NewListReplies(repository ListRepliesRepository, logger *slog.Logger) *ListRepliesUsecase {
	return &ListRepliesUsecase{repository: repository, logger: logger}
}

// Execute returns one page, ErrCommentsPostNotFound when the caller may not see the
// post, or ErrCommentNotFound when the parent is not a comment of that post the caller
// may see. Six queries whatever the page size: the post, the caller's blocks, the
// parent, the page (limit+1 rows, an index seek after the cursor), the users and the
// caller's likes.
func (u *ListRepliesUsecase) Execute(ctx context.Context, input ListRepliesInput) (CommentPage, error) {
	request := commentListRequest{userID: input.UserID, postID: input.PostID, limit: input.Limit, correlationID: input.CorrelationID}
	if err := request.validate(); err != nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", err)
	}
	if input.CommentID == uuid.Nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: comment id is required")
	}

	viewerID := input.UserID.String()
	visible, reason, err := findVisiblePost(ctx, u.repository, viewerID, input.PostID.String())
	if err != nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", err)
	}
	if reason != "" {
		logNotFound(ctx, u.logger, request, reason, "commentId", input.CommentID)
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", ErrCommentsPostNotFound)
	}

	parent, reason, err := u.findParent(ctx, visible, input.CommentID.String())
	if err != nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", err)
	}
	if reason != "" {
		return CommentPage{}, u.commentNotFound(ctx, request, input.CommentID, reason)
	}

	rows, err := u.repository.FindReplies(ctx, RepliesQuery{
		ParentID:          parent.CommentID,
		ExcludedAuthorIDs: visible.blockedIDs(),
		After:             input.After,
		Limit:             input.Limit + 1,
	})
	if err != nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", err)
	}
	kept, next := commentRows{rows: rows, limit: input.Limit}.page(func(last ContentComment) string {
		return EncodeRepliesCursor(RepliesCursorOf(last))
	})

	hydration, err := hydrateComments(ctx, u.repository, viewerID, kept, visible.post.CreatorID, parent.AuthorID)
	if err != nil {
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", err)
	}
	if _, found := hydration.users[visible.post.CreatorID]; !found {
		logNotFound(ctx, u.logger, request, commentsReasonPostCreatorMissing, "commentId", input.CommentID)
		return CommentPage{}, fmt.Errorf("list replies usecase: %w", ErrCommentsPostNotFound)
	}
	if _, found := hydration.users[parent.AuthorID]; !found {
		return CommentPage{}, u.commentNotFound(ctx, request, input.CommentID, commentsReasonParentAuthorMissing)
	}

	return CommentPage{Items: hydration.items(ctx, u.logger, request, kept), NextCursor: next}, nil
}

// findParent reads the parent comment and returns a not-found reason, and no error,
// when it is missing, belongs to another post or was written by a user with a block
// with the caller. Any depth is a valid parent: post-command-service accepts a reply
// to a reply.
func (u *ListRepliesUsecase) findParent(ctx context.Context, visible visiblePost, commentID string) (ContentComment, string, error) {
	parent, found, err := u.repository.FindComment(ctx, commentID)
	if err != nil {
		return ContentComment{}, "", err
	}
	if !found || parent.CommentID != commentID {
		return ContentComment{}, commentsReasonParentMissing, nil
	}
	if parent.PostID != visible.post.PostID {
		return ContentComment{}, commentsReasonParentOfAnotherPost, nil
	}
	if _, blocked := visible.blocked[parent.AuthorID]; blocked {
		return ContentComment{}, commentsReasonParentAuthorBlocked, nil
	}

	return parent, "", nil
}

func (u *ListRepliesUsecase) commentNotFound(ctx context.Context, request commentListRequest, commentID uuid.UUID, reason string) error {
	logNotFound(ctx, u.logger, request, reason, "commentId", commentID)
	return fmt.Errorf("list replies usecase: %w", ErrCommentNotFound)
}
