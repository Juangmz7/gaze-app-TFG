package usecase

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
)

// ListCommentsInput asks for one page of a post's top-level comments. UserID comes
// from the authenticated context; After is the decoded cursor, nil for the first page.
type ListCommentsInput struct {
	UserID        uuid.UUID
	PostID        uuid.UUID
	After         *CommentsCursor
	Limit         int
	CorrelationID string
}

// TopLevelCommentsQuery reads, in list order (like_count, reply_count, created_at,
// comment_id, all descending), up to Limit top-level comments of PostID strictly after
// After, leaving out the comments of ExcludedAuthorIDs.
type TopLevelCommentsQuery struct {
	PostID            string
	ExcludedAuthorIDs []string
	After             *CommentsCursor
	Limit             int
}

// ListCommentsRepository reads what the top-level comment list is built from.
// Implemented by feed/infrastructure/mongo.HydrationRepository.
type ListCommentsRepository interface {
	commentsReader
	FindTopLevelComments(ctx context.Context, query TopLevelCommentsQuery) ([]ContentComment, error)
}

// ListCommentsUsecase serves the top-level comments of a post, most liked first, then
// most replied, then newest, keyset paged.
type ListCommentsUsecase struct {
	repository ListCommentsRepository
	logger     *slog.Logger
}

// NewListComments creates a ListCommentsUsecase.
func NewListComments(repository ListCommentsRepository, logger *slog.Logger) *ListCommentsUsecase {
	return &ListCommentsUsecase{repository: repository, logger: logger}
}

// Execute returns one page, or ErrCommentsPostNotFound when the caller may not see the
// post. Five queries whatever the page size: the post, the caller's blocks, the page
// (limit+1 rows, an index seek after the cursor), the authors and the caller's likes.
// Comments of users with a block with the caller are left out by the query itself, so
// pages stay full.
func (u *ListCommentsUsecase) Execute(ctx context.Context, input ListCommentsInput) (CommentPage, error) {
	request := commentListRequest{userID: input.UserID, postID: input.PostID, limit: input.Limit, correlationID: input.CorrelationID}
	if err := request.validate(); err != nil {
		return CommentPage{}, fmt.Errorf("list comments usecase: %w", err)
	}

	viewerID := input.UserID.String()
	visible, reason, err := findVisiblePost(ctx, u.repository, viewerID, input.PostID.String())
	if err != nil {
		return CommentPage{}, fmt.Errorf("list comments usecase: %w", err)
	}
	if reason != "" {
		return CommentPage{}, u.notFound(ctx, request, reason)
	}

	rows, err := u.repository.FindTopLevelComments(ctx, TopLevelCommentsQuery{
		PostID:            visible.post.PostID,
		ExcludedAuthorIDs: visible.blockedIDs(),
		After:             input.After,
		Limit:             input.Limit + 1,
	})
	if err != nil {
		return CommentPage{}, fmt.Errorf("list comments usecase: %w", err)
	}
	kept, next := commentRows{rows: rows, limit: input.Limit}.page(func(last ContentComment) string {
		return EncodeCommentsCursor(CommentsCursorOf(last))
	})

	hydration, err := hydrateComments(ctx, u.repository, viewerID, kept, visible.post.CreatorID)
	if err != nil {
		return CommentPage{}, fmt.Errorf("list comments usecase: %w", err)
	}
	if _, found := hydration.users[visible.post.CreatorID]; !found {
		return CommentPage{}, u.notFound(ctx, request, commentsReasonPostCreatorMissing)
	}

	return CommentPage{Items: hydration.items(ctx, u.logger, request, kept), NextCursor: next}, nil
}

func (u *ListCommentsUsecase) notFound(ctx context.Context, request commentListRequest, reason string) error {
	logNotFound(ctx, u.logger, request, reason)
	return fmt.Errorf("list comments usecase: %w", ErrCommentsPostNotFound)
}
