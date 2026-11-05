// Package grpc implements feed/application/usecase ports over gRPC.
package grpc

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/grpc-ecosystem/go-grpc-middleware/v2/interceptors/retry"
	"go.opentelemetry.io/otel/propagation"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/backoff"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/infrastructure/grpc/recommendationv1"
)

// CorrelationIDMetadataKey carries the correlation id, as the proto defines it.
const CorrelationIDMetadataKey = "x-correlation-id"

// maxCorrelationIDLength is the bound of the proto: the server keeps 1 to 128
// printable ASCII characters without spaces.
const maxCorrelationIDLength = 128

// RecommendationClientConfig holds the settings of the connection (config.RecommendationConfig).
type RecommendationClientConfig struct {
	// Address is the gRPC target, dns:/// with the headless Service in a cluster.
	Address string
	// DialTimeout bounds one connection attempt.
	DialTimeout time.Duration
	// PerRetryTimeout bounds each attempt, the first one included.
	PerRetryTimeout time.Duration
	// OverallTimeout bounds the whole call, every attempt and wait included.
	OverallTimeout time.Duration
	// MaxAttempts is the TOTAL number of attempts: in go-grpc-middleware v2 the loop is
	// attempt < max, so retry.WithMax(4) means 3 retries.
	MaxAttempts uint
	// BackoffBase is the wait before the first retry; it doubles with every retry.
	BackoffBase time.Duration
	// BackoffMax caps every wait, jitter included.
	BackoffMax time.Duration
	// BackoffJitter is the fraction each wait varies by, up or down.
	BackoffJitter float64
}

// RecommendationClient calls recommendation-service for the synchronous refill. It
// implements usecase.RecommendationClient.
type RecommendationClient struct {
	conn           *googlegrpc.ClientConn
	stub           recommendationv1.RecommendationServiceClient
	overallTimeout time.Duration
	logger         *slog.Logger
}

// NewRecommendationClient creates the connection, with the retry interceptor on it so
// no call site can forget it. It does not wait for the server. Call Close on shutdown.
func NewRecommendationClient(config RecommendationClientConfig, logger *slog.Logger) (*RecommendationClient, error) {
	if err := validateConfig(config); err != nil {
		return nil, err
	}

	serviceConfig, err := roundRobinServiceConfig()
	if err != nil {
		return nil, err
	}

	connectParams := googlegrpc.ConnectParams{Backoff: backoff.DefaultConfig, MinConnectTimeout: config.DialTimeout}
	conn, err := googlegrpc.NewClient(config.Address,
		// In-cluster plaintext, restricted by the NetworkPolicies of both services.
		googlegrpc.WithTransportCredentials(insecure.NewCredentials()),
		googlegrpc.WithDefaultServiceConfig(serviceConfig),
		// Only this file decides how the client balances: nothing from DNS TXT records.
		googlegrpc.WithDisableServiceConfig(),
		googlegrpc.WithConnectParams(connectParams),
		googlegrpc.WithChainUnaryInterceptor(retry.UnaryClientInterceptor(retryOptions(config, logger)...)),
	)
	if err != nil {
		return nil, fmt.Errorf("create recommendation client: %w", err)
	}
	// Connect ahead of time, so the first refill does not pay for it.
	conn.Connect()

	return &RecommendationClient{
		conn:           conn,
		stub:           recommendationv1.NewRecommendationServiceClient(conn),
		overallTimeout: config.OverallTimeout,
		logger:         logger,
	}, nil
}

// Close closes the connection.
func (c *RecommendationClient) Close() error {
	if err := c.conn.Close(); err != nil {
		return fmt.Errorf("close recommendation client: %w", err)
	}
	return nil
}

// GetRecommendedFeed returns the recommended posts, best first, for a user whose feed
// is empty. An empty list means recommendation-service has no candidates. Reason is
// always FEED_EMPTY: this client only serves the synchronous refill.
func (c *RecommendationClient) GetRecommendedFeed(ctx context.Context, request usecase.RecommendationRequest) ([]uuid.UUID, error) {
	if request.UserID == uuid.Nil {
		return nil, fmt.Errorf("get recommended feed: user id is required")
	}

	excluded := make([]string, 0, len(request.ExcludedPostIDs))
	for _, postID := range request.ExcludedPostIDs {
		excluded = append(excluded, postID.String())
	}

	callCtx, cancel := context.WithTimeout(ctx, c.overallTimeout)
	defer cancel()
	callCtx = context.WithValue(callCtx, retryLogFieldsKey{}, retryLogFields{correlationID: request.CorrelationID, userID: request.UserID})
	callCtx = withOutgoingMetadata(callCtx, request.CorrelationID)

	started := time.Now()
	response, err := c.stub.GetRecommendedFeed(callCtx, &recommendationv1.GetRecommendedFeedRequest{
		UserId:          request.UserID.String(),
		ExcludedPostIds: excluded,
		Reason:          recommendationv1.FeedRequestReason_FEED_EMPTY,
	})
	if err != nil {
		return nil, fmt.Errorf("get recommended feed: %w", err)
	}

	postIDs, err := c.parsePostIDs(ctx, request, response.GetPostIds())
	if err != nil {
		return nil, err
	}
	c.logger.InfoContext(ctx, "recommendation grpc call succeeded", "correlation_id", request.CorrelationID, "userId", request.UserID,
		"posts", len(postIDs), "duration_ms", time.Since(started).Milliseconds())

	return postIDs, nil
}

// parsePostIDs keeps the ids that are UUIDs, with a warning for the others. When there
// were ids and none is usable the answer is a contract violation, not "no candidates",
// because an empty list would tell the client the feed ended.
func (c *RecommendationClient) parsePostIDs(ctx context.Context, request usecase.RecommendationRequest, raw []string) ([]uuid.UUID, error) {
	postIDs := make([]uuid.UUID, 0, len(raw))
	for _, value := range raw {
		postID, err := uuid.Parse(value)
		if err != nil {
			c.logger.WarnContext(ctx, "discarding a recommended post id that is not a UUID", "correlation_id", request.CorrelationID, "userId", request.UserID)
			continue
		}
		postIDs = append(postIDs, postID)
	}
	if len(raw) > 0 && len(postIDs) == 0 {
		return nil, fmt.Errorf("get recommended feed: none of the %d returned post ids is a UUID", len(raw))
	}

	return postIDs, nil
}

func validateConfig(config RecommendationClientConfig) error {
	if strings.TrimSpace(config.Address) == "" {
		return fmt.Errorf("create recommendation client: address is required")
	}
	if config.MaxAttempts == 0 || config.DialTimeout <= 0 || config.PerRetryTimeout <= 0 || config.OverallTimeout <= 0 || config.BackoffBase <= 0 || config.BackoffMax <= 0 {
		return fmt.Errorf("create recommendation client: attempts, timeouts and backoff must be positive")
	}

	return nil
}

// roundRobinServiceConfig balances per call among the pods the headless Service resolves to.
func roundRobinServiceConfig() (string, error) {
	serviceConfig, err := json.Marshal(map[string]any{
		"loadBalancingConfig": []map[string]any{{"round_robin": map[string]any{}}},
	})
	if err != nil {
		return "", fmt.Errorf("create recommendation client: %w", err)
	}
	return string(serviceConfig), nil
}

// retryOptions retries only what the proto says can pass: a retry on a timeout re-runs
// a heavy pipeline, so PerRetryTimeout must exceed its duration. WithPerRetryTimeout is
// also what makes DEADLINE_EXCEEDED retried: the library never retries that code from
// WithCodes alone, it is listed here to state the policy.
func retryOptions(config RecommendationClientConfig, logger *slog.Logger) []retry.CallOption {
	return []retry.CallOption{
		retry.WithMax(config.MaxAttempts),
		retry.WithBackoff(retry.BackoffExponentialWithJitterBounded(config.BackoffBase, config.BackoffJitter, config.BackoffMax)),
		retry.WithPerRetryTimeout(config.PerRetryTimeout),
		retry.WithCodes(codes.Unavailable, codes.DeadlineExceeded, codes.ResourceExhausted),
		retry.WithOnRetryCallback(func(ctx context.Context, attempt uint, err error) {
			fields, _ := ctx.Value(retryLogFieldsKey{}).(retryLogFields)
			logger.WarnContext(ctx, "recommendation grpc call retrying", "correlation_id", fields.correlationID, "userId", fields.userID,
				"retry", attempt, "max_attempts", config.MaxAttempts, "code", status.Code(err).String(), "error", err)
		}),
	}
}

// retryLogFields reaches the retry callback, which only receives the context.
type retryLogFields struct {
	correlationID string
	userID        uuid.UUID
}

type retryLogFieldsKey struct{}

// withOutgoingMetadata adds the correlation id and the W3C trace context. An unusable
// correlation id is left out, because the server generates one and gRPC would refuse
// to send one that is not printable ASCII.
func withOutgoingMetadata(ctx context.Context, correlationID string) context.Context {
	pairs := make([]string, 0, 6)
	if isUsableCorrelationID(correlationID) {
		pairs = append(pairs, CorrelationIDMetadataKey, correlationID)
	}

	trace := propagation.MapCarrier{}
	propagation.TraceContext{}.Inject(ctx, trace)
	for _, key := range trace.Keys() {
		pairs = append(pairs, key, trace.Get(key))
	}

	return metadata.AppendToOutgoingContext(ctx, pairs...)
}

func isUsableCorrelationID(value string) bool {
	if value == "" || len(value) > maxCorrelationIDLength {
		return false
	}

	return !strings.ContainsFunc(value, func(r rune) bool { return r <= ' ' || r >= 0x7f })
}
