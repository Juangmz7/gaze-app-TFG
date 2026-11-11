// Package config loads post-query-service configuration from environment
// variables. It fails fast when a required variable is missing so the
// service never starts in a half-configured state.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
)

// defaultMongoDatabase is used when POST_QUERY_MONGO_DATABASE is not set.
const defaultMongoDatabase = "post_query"

// defaultRabbitMQHost is used when RABBITMQ_HOST is not set. It matches the
// host a developer would use when running the service outside docker compose
// against a locally forwarded broker.
const defaultRabbitMQHost = "localhost"

// defaultRabbitMQPort is used when RABBITMQ_PORT is not set, matching the
// default exposed in TFG-code/infra/compose.yaml and .env_template.
const defaultRabbitMQPort = "5672"

// Feed defaults, used when the matching FEED_* variable is not set.
const (
	defaultFeedPageSize               = 15
	defaultFeedMaxPageSize            = 15
	defaultFeedMaxReadsPerClaim       = 3
	defaultFeedRetentionHours         = 168
	defaultFeedDisconnectWriteTimeout = 5000
	// Two batches of 30 ids from recommendation-service.
	defaultFeedPrefetchMaxUnserved = 60
	defaultFeedExcludedIDsCap      = 200
	defaultFeedPrefetchTimeout     = 5000
	// Must exceed queue time plus pipeline time. The pipeline takes seconds (see the
	// recommendation defaults); queue time is an estimate.
	defaultFeedLeaseTTL               = 300000
	defaultFeedLeaseReleaseTimeout    = 5000
	defaultFeedLowWatermarkConcurrent = 16
	defaultFeedSweepInterval          = 60000
	defaultFeedSweepStaleAfter        = 180000
	defaultFeedSweepBatchSize         = 100
	// Just above the slowest pipeline run measured, 7.7 s, with a build that just started.
	defaultFeedRefillLeaseWait    = 8000
	defaultFeedRefillPollInterval = 250
	defaultFeedRefillRetryAfter   = 5
	// The gRPC call's 20.5 s (RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS) plus the reads
	// around it and storing the posts.
	defaultFeedRefillLeaseTTL = 30000
)

// Recommendation-service gRPC defaults, used when the matching RECOMMENDATION_GRPC_*
// variable is not set. The timeouts come from a measurement, on CPU in WSL and not in
// production, with 20000 posts: a user with history takes p50 4.4 s, 5.1 s at most, and
// 7.7 s at most with two runs at once. See "Synchronous refill" in the architecture doc.
const (
	defaultRecommendationDialTimeout = 2000
	// Above the slowest run, because a retry on a timeout runs the pipeline again.
	defaultRecommendationPerRetryTimeout = 10000
	// Two runs that time out plus the first wait, and it expires during the second wait
	// (20.2 to 20.3 s, then 0.4 to 0.6 s), so no third run starts that could not finish.
	// Fast failures still get every attempt. Below the 30 s of the HTTPRoute.
	defaultRecommendationOverallTimeout = 20500
	// The total: 3 retries. In go-grpc-middleware v2 WithMax counts attempts.
	defaultRecommendationMaxAttempts   = 4
	defaultRecommendationBackoffBase   = 250
	defaultRecommendationBackoffMax    = 1000
	defaultRecommendationBackoffJitter = 20
	maxPercent                         = 100
)

// MongoConfig holds the settings required to connect to MongoDB.
type MongoConfig struct {
	// URI is the MongoDB connection string, e.g. mongodb://host:27017.
	URI string
	// Database is the name of the database this service reads from and writes to.
	Database string
}

// RabbitMQConfig holds the settings required to connect to the RabbitMQ
// broker shared with post-command-service. Env var names mirror the ones
// already used by infra/.env_template and compose.yaml (RABBITMQ_HOST,
// RABBITMQ_PORT, RABBITMQ_USER, RABBITMQ_PASSWORD) so a single .env file
// configures every service in the stack.
type RabbitMQConfig struct {
	// Host is the RabbitMQ broker hostname.
	Host string
	// Port is the RabbitMQ AMQP port (not the management UI port).
	Port string
	// User is the AMQP username.
	User string
	// Password is the AMQP password.
	Password string
}

// amqpConnectionTimeoutMs bounds each dial (TCP connect and AMQP handshake);
// amqp091-go defaults to 30 s. Read from the URI by every amqp.Dial, so it
// applies to the outbox publisher and to the Watermill subscribers alike.
const amqpConnectionTimeoutMs = "5000"

// AMQPURI builds the amqp:// connection URI from the configured host, port,
// and credentials.
func (c RabbitMQConfig) AMQPURI() string {
	u := url.URL{
		Scheme:   "amqp",
		User:     url.UserPassword(c.User, c.Password),
		Host:     c.Host + ":" + c.Port,
		Path:     "/",
		RawQuery: url.Values{"connection_timeout": {amqpConnectionTimeoutMs}}.Encode(),
	}
	return u.String()
}

// FeedConfig holds the limits of the feed endpoints and its stored units.
type FeedConfig struct {
	// DefaultPageSize is the page size of a claim with no limit.
	DefaultPageSize int
	// MaxPageSize is the largest limit a claim can ask for; above it the claim is
	// rejected.
	MaxPageSize int
	// MaxReadsPerClaim bounds how many times a claim reads further to replace items
	// dropped at hydration.
	MaxReadsPerClaim int
	// Retention is how long a served feed unit is kept (TTL index on served_at).
	Retention time.Duration
	// DisconnectWriteTimeout bounds the disconnect write, which outlives its request.
	DisconnectWriteTimeout time.Duration
	// InstanceID identifies this replica as the owner of a lease: FEED_INSTANCE_ID, or
	// the hostname, which is the pod name in Kubernetes.
	InstanceID string
	// PrefetchMaxUnserved is the backlog at which every asynchronous trigger skips.
	PrefetchMaxUnserved int
	// ExcludedIDsCap caps the ids sent to recommendation-service as already known.
	ExcludedIDsCap int
	// PrefetchTimeout bounds the transaction of one prefetch request.
	PrefetchTimeout time.Duration
	// LeaseTTL is the recovery from a crashed lease holder. It must exceed queue time
	// plus pipeline time.
	LeaseTTL time.Duration
	// LeaseReleaseTimeout bounds a lease release, which outlives its caller.
	LeaseReleaseTimeout time.Duration
	// LowWatermarkConcurrency bounds the background low watermark checks.
	LowWatermarkConcurrency int
	// SweepInterval is the pause between sweeps.
	SweepInterval time.Duration
	// SweepStaleAfter is how old a last heartbeat must be for the sweeper to act.
	SweepStaleAfter time.Duration
	// SweepBatchSize is how many sessions one sweep read returns.
	SweepBatchSize int
	// RefillLeaseWait is how long a claim that found nothing polls the stored feed
	// while another build holds the lease.
	RefillLeaseWait time.Duration
	// RefillPollInterval is the pause between those reads.
	RefillPollInterval time.Duration
	// RefillRetryAfter is what the degraded response tells a client to wait.
	RefillRetryAfter time.Duration
	// RefillLeaseTTL is the recovery from a holder that died during a synchronous
	// refill. It must exceed the gRPC call's overall timeout, and not exceed LeaseTTL.
	RefillLeaseTTL time.Duration
}

// RecommendationConfig holds the settings of the gRPC client of recommendation-service.
type RecommendationConfig struct {
	// Address is the gRPC target: dns:/// plus the headless Service in a cluster.
	Address string
	// DialTimeout bounds one connection attempt.
	DialTimeout time.Duration
	// PerRetryTimeout bounds each attempt. It must exceed the pipeline's duration,
	// because a retry on a timeout runs the pipeline again.
	PerRetryTimeout time.Duration
	// OverallTimeout bounds the whole call, every attempt and wait included.
	OverallTimeout time.Duration
	// MaxAttempts is the total number of attempts, the first one included.
	MaxAttempts int
	// BackoffBase is the wait before the first retry; it doubles with every retry.
	BackoffBase time.Duration
	// BackoffMax caps every wait.
	BackoffMax time.Duration
	// BackoffJitterPercent is how much each wait varies, up or down.
	BackoffJitterPercent int
}

// AppConfig holds the post-query-service-specific configuration. The HTTP
// port is intentionally not duplicated here: it is already loaded by
// common-packages/go-utils/server.LoadServer, which callers should use
// directly to avoid two packages parsing HTTP_PORT the same way.
//
// AppConfig is the single configuration type for this service. There is no
// second Go service with real code yet (social-service and
// recommendation-service are still empty directories), so RabbitMQ settings
// are centralized here instead of being extracted into
// common-packages/go-utils; extract only once a second service needs the
// same env parsing.
type AppConfig struct {
	// Mongo holds MongoDB connection settings.
	Mongo MongoConfig
	// RabbitMQ holds RabbitMQ broker connection settings.
	RabbitMQ RabbitMQConfig
	// Auth holds the JWT validation settings of the HTTP API.
	Auth auth.AuthConfig
	// Feed holds the limits of the feed endpoints and its stored units.
	Feed FeedConfig
	// Recommendation holds the settings of the gRPC client of recommendation-service.
	Recommendation RecommendationConfig
}

// Load reads AppConfig from environment variables. It returns an error when
// a required variable is missing or blank; POST_QUERY_MONGO_URI, RABBITMQ_USER,
// RABBITMQ_PASSWORD, JWKS_URL, JWT_ISSUER, JWT_AUDIENCE and RECOMMENDATION_GRPC_ADDR are
// required. POST_QUERY_MONGO_DATABASE, RABBITMQ_HOST, RABBITMQ_PORT, the FEED_* variables
// and the other RECOMMENDATION_GRPC_* variables fall back to sensible defaults; a value
// among them that makes no sense is an error.
func Load() (AppConfig, error) {
	uri := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_URI"))
	if uri == "" {
		return AppConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "POST_QUERY_MONGO_URI")
	}

	database := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_DATABASE"))
	if database == "" {
		database = defaultMongoDatabase
	}

	rabbitMQ, err := loadRabbitMQConfig()
	if err != nil {
		return AppConfig{}, err
	}

	authConfig, err := loadAuthConfig()
	if err != nil {
		return AppConfig{}, err
	}

	feed, err := loadFeedConfig()
	if err != nil {
		return AppConfig{}, err
	}

	recommendation, err := loadRecommendationConfig(feed.RefillLeaseTTL)
	if err != nil {
		return AppConfig{}, err
	}

	return AppConfig{
		Mongo: MongoConfig{
			URI:      uri,
			Database: database,
		},
		RabbitMQ:       rabbitMQ,
		Auth:           authConfig,
		Feed:           feed,
		Recommendation: recommendation,
	}, nil
}

// loadAuthConfig reads the JWT settings. All three are required: without them the
// feed endpoints could validate no token at all.
func loadAuthConfig() (auth.AuthConfig, error) {
	authConfig := auth.LoadAuth()
	for name, value := range map[string]string{
		"JWKS_URL":     authConfig.JWKSUrl,
		"JWT_ISSUER":   authConfig.Issuer,
		"JWT_AUDIENCE": authConfig.Audience,
	} {
		if strings.TrimSpace(value) == "" {
			return auth.AuthConfig{}, fmt.Errorf("load config: required environment variable %s is missing", name)
		}
	}

	return authConfig, nil
}

// loadFeedConfig reads FeedConfig from environment variables.
func loadFeedConfig() (FeedConfig, error) {
	defaultPageSize, err := positiveIntEnv("FEED_DEFAULT_PAGE_SIZE", defaultFeedPageSize)
	if err != nil {
		return FeedConfig{}, err
	}
	maxPageSize, err := positiveIntEnv("FEED_MAX_PAGE_SIZE", defaultFeedMaxPageSize)
	if err != nil {
		return FeedConfig{}, err
	}
	if defaultPageSize > maxPageSize {
		return FeedConfig{}, fmt.Errorf("load config: FEED_DEFAULT_PAGE_SIZE %d is above FEED_MAX_PAGE_SIZE %d", defaultPageSize, maxPageSize)
	}
	maxReads, err := positiveIntEnv("FEED_MAX_READS_PER_CLAIM", defaultFeedMaxReadsPerClaim)
	if err != nil {
		return FeedConfig{}, err
	}
	retentionHours, err := positiveIntEnv("FEED_RETENTION_HOURS", defaultFeedRetentionHours)
	if err != nil {
		return FeedConfig{}, err
	}
	disconnectTimeoutMs, err := positiveIntEnv("FEED_DISCONNECT_WRITE_TIMEOUT_MS", defaultFeedDisconnectWriteTimeout)
	if err != nil {
		return FeedConfig{}, err
	}

	feed := FeedConfig{
		DefaultPageSize:        defaultPageSize,
		MaxPageSize:            maxPageSize,
		MaxReadsPerClaim:       maxReads,
		Retention:              time.Duration(retentionHours) * time.Hour,
		DisconnectWriteTimeout: time.Duration(disconnectTimeoutMs) * time.Millisecond,
	}
	if err := loadFeedPrefetchConfig(&feed); err != nil {
		return FeedConfig{}, err
	}

	return feed, nil
}

// loadFeedPrefetchConfig reads the settings of the asynchronous prefetch.
func loadFeedPrefetchConfig(feed *FeedConfig) error {
	integers := []struct {
		name     string
		fallback int
		target   *int
	}{
		{"FEED_PREFETCH_MAX_UNSERVED", defaultFeedPrefetchMaxUnserved, &feed.PrefetchMaxUnserved},
		{"FEED_PREFETCH_EXCLUDED_IDS_CAP", defaultFeedExcludedIDsCap, &feed.ExcludedIDsCap},
		{"FEED_LOW_WATERMARK_CONCURRENCY", defaultFeedLowWatermarkConcurrent, &feed.LowWatermarkConcurrency},
		{"FEED_SWEEP_BATCH_SIZE", defaultFeedSweepBatchSize, &feed.SweepBatchSize},
	}
	for _, setting := range integers {
		value, err := positiveIntEnv(setting.name, setting.fallback)
		if err != nil {
			return err
		}
		*setting.target = value
	}

	durations := []struct {
		name     string
		fallback int
		target   *time.Duration
	}{
		{"FEED_PREFETCH_TIMEOUT_MS", defaultFeedPrefetchTimeout, &feed.PrefetchTimeout},
		{"FEED_LEASE_TTL_MS", defaultFeedLeaseTTL, &feed.LeaseTTL},
		{"FEED_LEASE_RELEASE_TIMEOUT_MS", defaultFeedLeaseReleaseTimeout, &feed.LeaseReleaseTimeout},
		{"FEED_SWEEP_INTERVAL_MS", defaultFeedSweepInterval, &feed.SweepInterval},
		{"FEED_SWEEP_STALE_AFTER_MS", defaultFeedSweepStaleAfter, &feed.SweepStaleAfter},
		{"FEED_REFILL_LEASE_WAIT_MS", defaultFeedRefillLeaseWait, &feed.RefillLeaseWait},
		{"FEED_REFILL_POLL_INTERVAL_MS", defaultFeedRefillPollInterval, &feed.RefillPollInterval},
		{"FEED_REFILL_LEASE_TTL_MS", defaultFeedRefillLeaseTTL, &feed.RefillLeaseTTL},
	}
	for _, setting := range durations {
		value, err := positiveIntEnv(setting.name, setting.fallback)
		if err != nil {
			return err
		}
		*setting.target = time.Duration(value) * time.Millisecond
	}

	// Below one page the low watermark could never fire; below the backlog the exclusions
	// could not hold what is already waiting, so the pipeline would return it again.
	if feed.PrefetchMaxUnserved < feed.DefaultPageSize {
		return fmt.Errorf("load config: FEED_PREFETCH_MAX_UNSERVED %d is below FEED_DEFAULT_PAGE_SIZE %d", feed.PrefetchMaxUnserved, feed.DefaultPageSize)
	}
	if feed.ExcludedIDsCap < feed.PrefetchMaxUnserved {
		return fmt.Errorf("load config: FEED_PREFETCH_EXCLUDED_IDS_CAP %d is below FEED_PREFETCH_MAX_UNSERVED %d", feed.ExcludedIDsCap, feed.PrefetchMaxUnserved)
	}
	if feed.LeaseTTL <= feed.PrefetchTimeout {
		return fmt.Errorf("load config: FEED_LEASE_TTL_MS %s must exceed FEED_PREFETCH_TIMEOUT_MS %s", feed.LeaseTTL, feed.PrefetchTimeout)
	}

	if feed.RefillLeaseTTL > feed.LeaseTTL {
		return fmt.Errorf("load config: FEED_REFILL_LEASE_TTL_MS %s is above FEED_LEASE_TTL_MS %s", feed.RefillLeaseTTL, feed.LeaseTTL)
	}
	if feed.RefillPollInterval > feed.RefillLeaseWait {
		return fmt.Errorf("load config: FEED_REFILL_POLL_INTERVAL_MS %s is above FEED_REFILL_LEASE_WAIT_MS %s", feed.RefillPollInterval, feed.RefillLeaseWait)
	}
	retryAfterSeconds, err := positiveIntEnv("FEED_REFILL_RETRY_AFTER_SECONDS", defaultFeedRefillRetryAfter)
	if err != nil {
		return err
	}
	feed.RefillRetryAfter = time.Duration(retryAfterSeconds) * time.Second

	feed.InstanceID = instanceID()

	return nil
}

// loadRecommendationConfig reads RecommendationConfig from environment variables.
// refillLeaseTTL is checked against the deadline: the lease must outlive the call it
// guards.
func loadRecommendationConfig(refillLeaseTTL time.Duration) (RecommendationConfig, error) {
	address := strings.TrimSpace(os.Getenv("RECOMMENDATION_GRPC_ADDR"))
	if address == "" {
		return RecommendationConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "RECOMMENDATION_GRPC_ADDR")
	}

	recommendation := RecommendationConfig{Address: address}
	durations := []struct {
		name     string
		fallback int
		target   *time.Duration
	}{
		{"RECOMMENDATION_GRPC_DIAL_TIMEOUT_MS", defaultRecommendationDialTimeout, &recommendation.DialTimeout},
		{"RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS", defaultRecommendationPerRetryTimeout, &recommendation.PerRetryTimeout},
		{"RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS", defaultRecommendationOverallTimeout, &recommendation.OverallTimeout},
		{"RECOMMENDATION_GRPC_BACKOFF_BASE_MS", defaultRecommendationBackoffBase, &recommendation.BackoffBase},
		{"RECOMMENDATION_GRPC_BACKOFF_MAX_MS", defaultRecommendationBackoffMax, &recommendation.BackoffMax},
	}
	for _, setting := range durations {
		value, err := positiveIntEnv(setting.name, setting.fallback)
		if err != nil {
			return RecommendationConfig{}, err
		}
		*setting.target = time.Duration(value) * time.Millisecond
	}

	maxAttempts, err := positiveIntEnv("RECOMMENDATION_GRPC_MAX_ATTEMPTS", defaultRecommendationMaxAttempts)
	if err != nil {
		return RecommendationConfig{}, err
	}
	recommendation.MaxAttempts = maxAttempts

	jitterPercent, err := positiveIntEnv("RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT", defaultRecommendationBackoffJitter)
	if err != nil {
		return RecommendationConfig{}, err
	}
	if jitterPercent > maxPercent {
		return RecommendationConfig{}, fmt.Errorf("load config: RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT %d is above %d", jitterPercent, maxPercent)
	}
	recommendation.BackoffJitterPercent = jitterPercent

	if recommendation.BackoffBase > recommendation.BackoffMax {
		return RecommendationConfig{}, fmt.Errorf("load config: RECOMMENDATION_GRPC_BACKOFF_BASE_MS %s is above RECOMMENDATION_GRPC_BACKOFF_MAX_MS %s", recommendation.BackoffBase, recommendation.BackoffMax)
	}
	if recommendation.PerRetryTimeout > recommendation.OverallTimeout {
		return RecommendationConfig{}, fmt.Errorf("load config: RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS %s is above RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS %s", recommendation.PerRetryTimeout, recommendation.OverallTimeout)
	}
	if refillLeaseTTL <= recommendation.OverallTimeout {
		return RecommendationConfig{}, fmt.Errorf("load config: FEED_REFILL_LEASE_TTL_MS %s must exceed RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS %s", refillLeaseTTL, recommendation.OverallTimeout)
	}

	return recommendation, nil
}

// instanceID names this replica. A generated id still tells replicas apart when
// neither source is available.
func instanceID() string {
	if configured := strings.TrimSpace(os.Getenv("FEED_INSTANCE_ID")); configured != "" {
		return configured
	}
	if hostname, err := os.Hostname(); err == nil && hostname != "" {
		return hostname
	}

	return uuid.NewString()
}

// positiveIntEnv reads a positive integer, falling back to fallback when the variable
// is not set.
func positiveIntEnv(name string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}

	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("load config: %s must be a positive integer, got %q", name, raw)
	}

	return value, nil
}

// loadRabbitMQConfig reads RabbitMQConfig from environment variables.
func loadRabbitMQConfig() (RabbitMQConfig, error) {
	user := strings.TrimSpace(os.Getenv("RABBITMQ_USER"))
	if user == "" {
		return RabbitMQConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "RABBITMQ_USER")
	}

	password := strings.TrimSpace(os.Getenv("RABBITMQ_PASSWORD"))
	if password == "" {
		return RabbitMQConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "RABBITMQ_PASSWORD")
	}

	host := strings.TrimSpace(os.Getenv("RABBITMQ_HOST"))
	if host == "" {
		host = defaultRabbitMQHost
	}

	port := strings.TrimSpace(os.Getenv("RABBITMQ_PORT"))
	if port == "" {
		port = defaultRabbitMQPort
	}

	return RabbitMQConfig{
		Host:     host,
		Port:     port,
		User:     user,
		Password: password,
	}, nil
}
