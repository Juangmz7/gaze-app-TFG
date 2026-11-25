// Package usecase implements the use case that projects PostViewedEvent into the
// viewing history read model (one row per (user, post), holding the latest view) and
// into the viewed post's view counter, which counts at most a daily limit of views per
// user and post.
package usecase

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// RecordPostViewInput is one view of a post by a user, at ViewedAt (the event's
// serverTimestamp, set by post-command-service when it accepted the view).
// CorrelationID is only logged.
type RecordPostViewInput struct {
	UserID        uuid.UUID
	PostID        uuid.UUID
	ViewedAt      time.Time
	CorrelationID uuid.UUID
}

// viewDayLayout formats a view's UTC calendar day as stored in counted_day. Days in this
// layout sort as strings in calendar order.
const viewDayLayout = "2006-01-02"

// RecordPostViewHistory is one view as the history repository records it: the view,
// its UTC calendar day (Day, YYYY-MM-DD, taken from ViewedAt) and the most views of
// one user and post counted on one day (DailyLimit, at least 1).
type RecordPostViewHistory struct {
	UserID     uuid.UUID
	PostID     uuid.UUID
	ViewedAt   time.Time
	Day        string
	DailyLimit int
}

// ViewCountDecision is what the history repository decided about counting a view on
// the post's view counter.
type ViewCountDecision int

const (
	// ViewCounted means the view is one of the first DailyLimit views of its day.
	ViewCounted ViewCountDecision = iota + 1
	// ViewOverDailyLimit means DailyLimit views of the same day were already counted.
	ViewOverDailyLimit
	// ViewFromPastDay means the view's day is older than the last day counted for the
	// user and post (a late delivery): the count of the later day is kept.
	ViewFromPastDay
)

// RecordPostViewRepository keeps the viewing history. Record stores the row of
// (UserID, PostID) with last_viewed_at = max(stored, ViewedAt): one row per user and
// post however many views there are, and an older view delivered late never moves it
// back. In the same single-document write it counts the view against the daily limit
// of its Day and reports the decision. Implemented by
// view/infrastructure/mongo.HistoryRepository.
type RecordPostViewRepository interface {
	Record(ctx context.Context, view RecordPostViewHistory) (ViewCountDecision, error)
}

// RecordPostViewCounter adds delta to the post read model's view counter in one
// atomic update and reports whether the post is projected. It never creates a post.
// Implemented by post/infrastructure/mongo.Repository.
type RecordPostViewCounter interface {
	AdjustViewCount(ctx context.Context, postID uuid.UUID, delta int64) (found bool, err error)
}

// RecordPostViewUsecase projects a PostViewedEvent into the viewing history and the
// post's view counter.
type RecordPostViewUsecase struct {
	repository RecordPostViewRepository
	postViews  RecordPostViewCounter
	dailyLimit int
	logger     *slog.Logger
}

// NewRecordPostView creates a RecordPostViewUsecase backed by repository and postViews
// that counts at most dailyLimit views of one user and post per UTC day
// (config.ViewHistoryConfig.ViewCountDailyLimitPerUser). Execute refuses to run with a
// dailyLimit below 1.
func NewRecordPostView(repository RecordPostViewRepository, postViews RecordPostViewCounter, dailyLimit int, logger *slog.Logger) *RecordPostViewUsecase {
	return &RecordPostViewUsecase{repository: repository, postViews: postViews, dailyLimit: dailyLimit, logger: logger}
}

// Execute records the view and counts it once on the post when the history reports it
// as one of the user's first dailyLimit views of the post on the view's UTC day (the
// day of ViewedAt, never the consumer's clock). A view over the limit, or from a day
// older than the last one counted, still moves the history entry but adds nothing. It
// runs inside the caller's transaction (with the idempotency check and mark), so a
// redelivered event neither counts nor uses up a daily view, and a failed write counts
// nothing either.
//
// It does not require the post to be projected: the history read leaves out posts that
// do not exist (yet or any more), and the TTL on last_viewed_at removes rows nobody
// refreshes. Such a view is recorded in the history but counted on no post, with a
// warning: a retry could not count it either, as the post may never be projected.
func (u *RecordPostViewUsecase) Execute(ctx context.Context, input RecordPostViewInput) error {
	if err := input.validate(); err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}

	if u.dailyLimit < 1 {
		return fmt.Errorf("record post view usecase: daily view count limit %d must be at least 1", u.dailyLimit)
	}

	viewedAt := input.ViewedAt.UTC()
	decision, err := u.repository.Record(ctx, RecordPostViewHistory{
		UserID:     input.UserID,
		PostID:     input.PostID,
		ViewedAt:   viewedAt,
		Day:        viewedAt.Format(viewDayLayout),
		DailyLimit: u.dailyLimit,
	})
	if err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}

	switch decision {
	case ViewCounted:
		return u.countView(ctx, input)
	case ViewOverDailyLimit:
		u.logger.DebugContext(ctx, "post view not counted: daily limit reached", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID, "daily_limit", u.dailyLimit)
		return nil
	case ViewFromPastDay:
		u.logger.DebugContext(ctx, "post view not counted: a later day is already counted", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID, "viewed_at", viewedAt)
		return nil
	default:
		return fmt.Errorf("record post view usecase: unknown view count decision %d", decision)
	}
}

// countView adds the view to the post's counter. A post that is not projected is
// logged, not failed.
func (u *RecordPostViewUsecase) countView(ctx context.Context, input RecordPostViewInput) error {
	found, err := u.postViews.AdjustViewCount(ctx, input.PostID, 1)
	if err != nil {
		return fmt.Errorf("record post view usecase: %w", err)
	}
	if !found {
		u.logger.WarnContext(ctx, "post view not counted: the post is not projected", "correlation_id", input.CorrelationID, "userId", input.UserID, "post_id", input.PostID)
	}

	return nil
}

func (i RecordPostViewInput) validate() error {
	if i.UserID == uuid.Nil {
		return errors.New("user id is required")
	}
	if i.PostID == uuid.Nil {
		return errors.New("post id is required")
	}
	if i.ViewedAt.IsZero() {
		return errors.New("viewed at is required")
	}

	return nil
}
