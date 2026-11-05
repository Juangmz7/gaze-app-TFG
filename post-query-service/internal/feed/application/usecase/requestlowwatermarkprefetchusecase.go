package usecase

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
)

// RequestLowWatermarkPrefetchConfig holds the trigger's settings (config.FeedConfig).
type RequestLowWatermarkPrefetchConfig struct {
	// PageSize is one full page: below it, the next claim could come up short.
	PageSize int
	// Concurrency bounds the checks in flight. Above it a check is dropped: the
	// user's next claim repeats it.
	Concurrency int
	// Timeout bounds one check and its prefetch, which outlive the claim.
	Timeout time.Duration
}

// RequestLowWatermarkPrefetchInput identifies the claim that may have left the feed low.
type RequestLowWatermarkPrefetchInput struct {
	UserID        uuid.UUID
	CorrelationID string
}

// RequestLowWatermarkPrefetchRequester is RequestFeedPrefetchUsecase.
type RequestLowWatermarkPrefetchRequester interface {
	Execute(ctx context.Context, input RequestFeedPrefetchInput) (RequestFeedPrefetchOutput, error)
}

// RequestLowWatermarkPrefetchUsecase requests a prefetch when a claim leaves less than
// one page unserved, keeping the synchronous refill an exceptional path. It runs after
// the claim's response is decided and never delays it.
type RequestLowWatermarkPrefetchUsecase struct {
	backlog  *FeedBacklog
	prefetch RequestLowWatermarkPrefetchRequester
	config   RequestLowWatermarkPrefetchConfig
	logger   *slog.Logger

	// mu orders stopping against inFlight.Add, so Wait cannot miss a check.
	mu       sync.Mutex
	stopping bool
	slots    chan struct{}
	inFlight sync.WaitGroup
}

// NewRequestLowWatermarkPrefetch creates a RequestLowWatermarkPrefetchUsecase. Call
// Wait on shutdown.
func NewRequestLowWatermarkPrefetch(backlog *FeedBacklog, prefetch RequestLowWatermarkPrefetchRequester, config RequestLowWatermarkPrefetchConfig, logger *slog.Logger) *RequestLowWatermarkPrefetchUsecase {
	return &RequestLowWatermarkPrefetchUsecase{backlog: backlog, prefetch: prefetch, config: config, logger: logger, slots: make(chan struct{}, config.Concurrency)}
}

// Notify starts the check in the background and returns at once. The claim's request
// context ends as soon as the response is written, so the check is detached from it
// and bounded by Timeout instead.
func (u *RequestLowWatermarkPrefetchUsecase) Notify(ctx context.Context, input RequestLowWatermarkPrefetchInput) {
	if input.UserID == uuid.Nil {
		u.logger.WarnContext(ctx, "discarding a low watermark check without a user id", "correlation_id", input.CorrelationID)
		return
	}
	if !u.reserveSlot(ctx, input) {
		return
	}

	checkCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), u.config.Timeout)
	go func() {
		defer func() {
			cancel()
			<-u.slots
			u.inFlight.Done()
		}()
		u.check(checkCtx, input)
	}()
}

// Wait stops new checks and waits for those in flight, each bounded by Timeout.
func (u *RequestLowWatermarkPrefetchUsecase) Wait() {
	u.mu.Lock()
	u.stopping = true
	u.mu.Unlock()

	u.inFlight.Wait()
}

func (u *RequestLowWatermarkPrefetchUsecase) reserveSlot(ctx context.Context, input RequestLowWatermarkPrefetchInput) bool {
	u.mu.Lock()
	defer u.mu.Unlock()

	if u.stopping {
		return false
	}
	select {
	case u.slots <- struct{}{}:
		u.inFlight.Add(1)
		return true
	default:
		u.logger.InfoContext(ctx, "feed low watermark check dropped, all busy", "correlation_id", input.CorrelationID, "userId", input.UserID)
		return false
	}
}

func (u *RequestLowWatermarkPrefetchUsecase) check(ctx context.Context, input RequestLowWatermarkPrefetchInput) {
	unserved, _, err := u.backlog.Unserved(ctx, input.UserID)
	if err != nil {
		u.logger.ErrorContext(ctx, "feed low watermark check failed", "correlation_id", input.CorrelationID, "userId", input.UserID, "error", err)
		return
	}
	if unserved >= int64(u.config.PageSize) {
		return
	}

	prefetchInput := RequestFeedPrefetchInput{UserID: input.UserID, Reason: FeedPrefetchReasonLowWatermark, CorrelationID: input.CorrelationID}
	if _, err := u.prefetch.Execute(ctx, prefetchInput); err != nil {
		u.logger.ErrorContext(ctx, "feed low watermark prefetch failed", "correlation_id", input.CorrelationID, "userId", input.UserID, "error", err)
	}
}
