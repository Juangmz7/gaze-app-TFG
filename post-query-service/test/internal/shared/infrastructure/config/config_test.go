package config_test

import (
	"os"
	"testing"
	"time"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
)

func TestLoad_ParsesValidEnvironmentVariablesIntoConfig(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	setEnv(t, "POST_QUERY_MONGO_DATABASE", "post_query_read_models")
	setEnv(t, "RABBITMQ_HOST", "rabbitmq")
	setEnv(t, "RABBITMQ_PORT", "5673")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")
	setAuthEnv(t)
	unsetFeedEnv(t)
	setEnv(t, "FEED_INSTANCE_ID", "post-query-service-7d9f")
	setEnv(t, "RECOMMENDATION_GRPC_ADDR", "dns:///recommendation-service:50051")
	setMediaEnv(t)

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.AppConfig{
		Mongo: config.MongoConfig{
			URI:      "mongodb://post-query-mongo:27017",
			Database: "post_query_read_models",
		},
		RabbitMQ: config.RabbitMQConfig{
			Host:     "rabbitmq",
			Port:     "5673",
			User:     "post_query_user",
			Password: "post_query_password",
		},
		Auth: auth.AuthConfig{
			JWKSUrl:  "http://keycloak:8080/certs",
			Issuer:   "http://localhost:8080/realms/auth-service",
			Audience: "account",
		},
		Feed: config.FeedConfig{
			DefaultPageSize:         15,
			MaxPageSize:             15,
			MaxReadsPerClaim:        3,
			Retention:               168 * time.Hour,
			DisconnectWriteTimeout:  5 * time.Second,
			InstanceID:              "post-query-service-7d9f",
			PrefetchMaxUnserved:     60,
			ExcludedIDsCap:          200,
			PrefetchTimeout:         5 * time.Second,
			LeaseTTL:                5 * time.Minute,
			LeaseReleaseTimeout:     5 * time.Second,
			LowWatermarkConcurrency: 16,
			SweepInterval:           time.Minute,
			SweepStaleAfter:         3 * time.Minute,
			SweepBatchSize:          100,
			RefillLeaseWait:         8 * time.Second,
			RefillPollInterval:      250 * time.Millisecond,
			RefillRetryAfter:        5 * time.Second,
			RefillLeaseTTL:          30 * time.Second,
		},
		Recommendation: config.RecommendationConfig{
			Address:              "dns:///recommendation-service:50051",
			DialTimeout:          2 * time.Second,
			PerRetryTimeout:      10 * time.Second,
			OverallTimeout:       20500 * time.Millisecond,
			MaxAttempts:          4,
			BackoffBase:          250 * time.Millisecond,
			BackoffMax:           time.Second,
			BackoffJitterPercent: 20,
		},
		Media: config.MediaStorageConfig{
			AccountURL:    azuriteAccountURL,
			Container:     "post-media-dev",
			AccountKey:    azuriteAccountKey,
			ReadSASWindow: 24 * time.Hour,
		},
	}

	if got != want {
		t.Fatalf("Load() = %+v, want %+v", got, want)
	}
}

func TestLoad_AppliesDefaultsWhenOptionalVariablesAreMissing(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	unsetEnv(t, "POST_QUERY_MONGO_DATABASE")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")
	unsetEnv(t, "RABBITMQ_HOST")
	unsetEnv(t, "RABBITMQ_PORT")
	setAuthEnv(t)
	unsetFeedEnv(t)
	setEnv(t, "RECOMMENDATION_GRPC_ADDR", "dns:///recommendation-service:50051")
	setMediaEnv(t)

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	if got.Mongo.Database == "" {
		t.Fatal("Load() Mongo.Database = \"\", want a non-empty default")
	}
	if got.RabbitMQ.Host == "" {
		t.Fatal("Load() RabbitMQ.Host = \"\", want a non-empty default")
	}
	if got.RabbitMQ.Port == "" {
		t.Fatal("Load() RabbitMQ.Port = \"\", want a non-empty default")
	}
}

func TestLoad_ReturnsErrorWhenMongoURIIsMissing(t *testing.T) {
	unsetEnv(t, "POST_QUERY_MONGO_URI")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for missing POST_QUERY_MONGO_URI")
	}
}

func TestLoad_ReturnsErrorWhenMongoURIIsBlank(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "   ")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for blank POST_QUERY_MONGO_URI")
	}
}

func TestLoad_ReturnsErrorWhenRabbitMQUserIsMissing(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	unsetEnv(t, "RABBITMQ_USER")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for missing RABBITMQ_USER")
	}
}

func TestLoad_ReturnsErrorWhenRabbitMQPasswordIsMissing(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	unsetEnv(t, "RABBITMQ_PASSWORD")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for missing RABBITMQ_PASSWORD")
	}
}

func TestLoad_ReturnsErrorWhenAnAuthVariableIsMissing(t *testing.T) {
	for _, name := range []string{"JWKS_URL", "JWT_ISSUER", "JWT_AUDIENCE"} {
		t.Run(name, func(t *testing.T) {
			setRequiredEnv(t)
			unsetEnv(t, name)

			if _, err := config.Load(); err == nil {
				t.Fatalf("Load() error = nil, want error for missing %s", name)
			}
		})
	}
}

func TestLoad_ReadsTheFeedSettings(t *testing.T) {
	setRequiredEnv(t)
	setEnv(t, "FEED_DEFAULT_PAGE_SIZE", "10")
	setEnv(t, "FEED_MAX_PAGE_SIZE", "30")
	setEnv(t, "FEED_MAX_READS_PER_CLAIM", "5")
	setEnv(t, "FEED_RETENTION_HOURS", "24")
	setEnv(t, "FEED_DISCONNECT_WRITE_TIMEOUT_MS", "750")
	setEnv(t, "FEED_INSTANCE_ID", "post-query-service-7d9f")
	setEnv(t, "FEED_PREFETCH_MAX_UNSERVED", "40")
	setEnv(t, "FEED_PREFETCH_EXCLUDED_IDS_CAP", "90")
	setEnv(t, "FEED_PREFETCH_TIMEOUT_MS", "800")
	setEnv(t, "FEED_LEASE_TTL_MS", "120000")
	setEnv(t, "FEED_LEASE_RELEASE_TIMEOUT_MS", "900")
	setEnv(t, "FEED_LOW_WATERMARK_CONCURRENCY", "4")
	setEnv(t, "FEED_SWEEP_INTERVAL_MS", "30000")
	setEnv(t, "FEED_SWEEP_STALE_AFTER_MS", "60000")
	setEnv(t, "FEED_SWEEP_BATCH_SIZE", "25")
	setEnv(t, "FEED_REFILL_LEASE_WAIT_MS", "1500")
	setEnv(t, "FEED_REFILL_POLL_INTERVAL_MS", "150")
	setEnv(t, "FEED_REFILL_RETRY_AFTER_SECONDS", "9")
	setEnv(t, "FEED_REFILL_LEASE_TTL_MS", "45000")

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.FeedConfig{
		DefaultPageSize:         10,
		MaxPageSize:             30,
		MaxReadsPerClaim:        5,
		Retention:               24 * time.Hour,
		DisconnectWriteTimeout:  750 * time.Millisecond,
		InstanceID:              "post-query-service-7d9f",
		PrefetchMaxUnserved:     40,
		ExcludedIDsCap:          90,
		PrefetchTimeout:         800 * time.Millisecond,
		LeaseTTL:                2 * time.Minute,
		LeaseReleaseTimeout:     900 * time.Millisecond,
		LowWatermarkConcurrency: 4,
		SweepInterval:           30 * time.Second,
		SweepStaleAfter:         time.Minute,
		SweepBatchSize:          25,
		RefillLeaseWait:         1500 * time.Millisecond,
		RefillPollInterval:      150 * time.Millisecond,
		RefillRetryAfter:        9 * time.Second,
		RefillLeaseTTL:          45 * time.Second,
	}
	if got.Feed != want {
		t.Fatalf("Load() Feed = %+v, want %+v", got.Feed, want)
	}
}

func TestLoad_RejectsFeedSettingsThatMakeNoSense(t *testing.T) {
	tests := []struct {
		name string
		env  map[string]string
	}{
		{"default page size is not a number", map[string]string{"FEED_DEFAULT_PAGE_SIZE": "many"}},
		{"max page size is zero", map[string]string{"FEED_MAX_PAGE_SIZE": "0"}},
		{"default above max", map[string]string{"FEED_DEFAULT_PAGE_SIZE": "20", "FEED_MAX_PAGE_SIZE": "10"}},
		{"max reads is negative", map[string]string{"FEED_MAX_READS_PER_CLAIM": "-1"}},
		{"retention is zero", map[string]string{"FEED_RETENTION_HOURS": "0"}},
		{"disconnect timeout is not a number", map[string]string{"FEED_DISCONNECT_WRITE_TIMEOUT_MS": "soon"}},
		{"max unserved is zero", map[string]string{"FEED_PREFETCH_MAX_UNSERVED": "0"}},
		{"max unserved below a page", map[string]string{"FEED_PREFETCH_MAX_UNSERVED": "5"}},
		{"excluded cap below max unserved", map[string]string{"FEED_PREFETCH_EXCLUDED_IDS_CAP": "20"}},
		{"lease ttl is negative", map[string]string{"FEED_LEASE_TTL_MS": "-1"}},
		{"lease ttl not above the prefetch timeout", map[string]string{"FEED_LEASE_TTL_MS": "5000"}},
		{"release timeout is zero", map[string]string{"FEED_LEASE_RELEASE_TIMEOUT_MS": "0"}},
		{"sweep interval is not a number", map[string]string{"FEED_SWEEP_INTERVAL_MS": "often"}},
		{"stale threshold is zero", map[string]string{"FEED_SWEEP_STALE_AFTER_MS": "0"}},
		{"sweep batch is negative", map[string]string{"FEED_SWEEP_BATCH_SIZE": "-5"}},
		{"low watermark concurrency is zero", map[string]string{"FEED_LOW_WATERMARK_CONCURRENCY": "0"}},
		{"lease wait is zero", map[string]string{"FEED_REFILL_LEASE_WAIT_MS": "0"}},
		{"poll interval is not a number", map[string]string{"FEED_REFILL_POLL_INTERVAL_MS": "often"}},
		{"poll interval above the wait", map[string]string{"FEED_REFILL_LEASE_WAIT_MS": "100", "FEED_REFILL_POLL_INTERVAL_MS": "200"}},
		{"retry after is zero", map[string]string{"FEED_REFILL_RETRY_AFTER_SECONDS": "0"}},
		{"refill lease ttl is zero", map[string]string{"FEED_REFILL_LEASE_TTL_MS": "0"}},
		{"refill lease ttl above the lease ttl", map[string]string{"FEED_LEASE_TTL_MS": "25000", "FEED_REFILL_LEASE_TTL_MS": "30000"}},
		{"recommendation address is blank", map[string]string{"RECOMMENDATION_GRPC_ADDR": "  "}},
		{"dial timeout is zero", map[string]string{"RECOMMENDATION_GRPC_DIAL_TIMEOUT_MS": "0"}},
		{"per retry timeout is not a number", map[string]string{"RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS": "soon"}},
		{"overall timeout is negative", map[string]string{"RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS": "-1"}},
		{"max attempts is zero", map[string]string{"RECOMMENDATION_GRPC_MAX_ATTEMPTS": "0"}},
		{"backoff base is zero", map[string]string{"RECOMMENDATION_GRPC_BACKOFF_BASE_MS": "0"}},
		{"backoff cap is zero", map[string]string{"RECOMMENDATION_GRPC_BACKOFF_MAX_MS": "0"}},
		{"jitter above everything", map[string]string{"RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT": "101"}},
		{"backoff base above the cap", map[string]string{"RECOMMENDATION_GRPC_BACKOFF_BASE_MS": "2000", "RECOMMENDATION_GRPC_BACKOFF_MAX_MS": "1000"}},
		{"per retry timeout above the overall", map[string]string{"RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS": "3000", "RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS": "2000"}},
		{"refill lease ttl not above the overall deadline", map[string]string{"FEED_REFILL_LEASE_TTL_MS": "10000", "RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS": "3000", "RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS": "10000"}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			setRequiredEnv(t)
			for name, value := range test.env {
				setEnv(t, name, value)
			}

			if _, err := config.Load(); err == nil {
				t.Fatal("Load() error = nil, want an error")
			}
		})
	}
}

func TestRabbitMQConfig_AMQPURI_BuildsConnectionStringFromFields(t *testing.T) {
	cfg := config.RabbitMQConfig{
		Host:     "rabbitmq",
		Port:     "5672",
		User:     "post_query_user",
		Password: "post_query_password",
	}

	want := "amqp://post_query_user:post_query_password@rabbitmq:5672/?connection_timeout=5000"
	if got := cfg.AMQPURI(); got != want {
		t.Fatalf("AMQPURI() = %q, want %q", got, want)
	}
}

// setRequiredEnv sets every required variable, with the feed settings at their
// defaults.
func setRequiredEnv(t *testing.T) {
	t.Helper()
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")
	setAuthEnv(t)
	setEnv(t, "RECOMMENDATION_GRPC_ADDR", "dns:///recommendation-service:50051")
	unsetFeedEnv(t)
	setMediaEnv(t)
}

// Azurite's well-known development account, which is public and no secret.
const (
	azuriteAccountURL = "http://azurite:10000/devstoreaccount1"
	azuriteAccountKey = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
)

// setMediaEnv points the media settings at Azurite, with the default window.
func setMediaEnv(t *testing.T) {
	t.Helper()
	setEnv(t, "AZURE_STORAGE_ACCOUNT_URL", azuriteAccountURL)
	setEnv(t, "AZURE_STORAGE_CONTAINER", "post-media-dev")
	setEnv(t, "AZURE_STORAGE_ACCOUNT_KEY", azuriteAccountKey)
	unsetEnv(t, "MEDIA_READ_SAS_WINDOW")
}

func setAuthEnv(t *testing.T) {
	t.Helper()
	setEnv(t, "JWKS_URL", "http://keycloak:8080/certs")
	setEnv(t, "JWT_ISSUER", "http://localhost:8080/realms/auth-service")
	setEnv(t, "JWT_AUDIENCE", "account")
}

func unsetFeedEnv(t *testing.T) {
	t.Helper()
	for _, name := range []string{"FEED_DEFAULT_PAGE_SIZE", "FEED_MAX_PAGE_SIZE", "FEED_MAX_READS_PER_CLAIM", "FEED_RETENTION_HOURS", "FEED_DISCONNECT_WRITE_TIMEOUT_MS",
		"FEED_INSTANCE_ID", "FEED_PREFETCH_MAX_UNSERVED", "FEED_PREFETCH_EXCLUDED_IDS_CAP", "FEED_PREFETCH_TIMEOUT_MS", "FEED_LEASE_TTL_MS", "FEED_LEASE_RELEASE_TIMEOUT_MS",
		"FEED_LOW_WATERMARK_CONCURRENCY", "FEED_SWEEP_INTERVAL_MS", "FEED_SWEEP_STALE_AFTER_MS", "FEED_SWEEP_BATCH_SIZE",
		"FEED_REFILL_LEASE_WAIT_MS", "FEED_REFILL_POLL_INTERVAL_MS", "FEED_REFILL_RETRY_AFTER_SECONDS", "FEED_REFILL_LEASE_TTL_MS",
		"RECOMMENDATION_GRPC_DIAL_TIMEOUT_MS", "RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS", "RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS", "RECOMMENDATION_GRPC_MAX_ATTEMPTS",
		"RECOMMENDATION_GRPC_BACKOFF_BASE_MS", "RECOMMENDATION_GRPC_BACKOFF_MAX_MS", "RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT"} {
		unsetEnv(t, name)
	}
}

// setEnv sets an environment variable for the duration of the test and
// restores its previous value afterward.
func setEnv(t *testing.T, key, value string) {
	t.Helper()
	t.Setenv(key, value)
}

// unsetEnv clears an environment variable for the duration of the test and
// restores its previous value afterward.
func unsetEnv(t *testing.T, key string) {
	t.Helper()

	previous, existed := os.LookupEnv(key)
	if err := os.Unsetenv(key); err != nil {
		t.Fatalf("os.Unsetenv(%q) error: %v", key, err)
	}

	t.Cleanup(func() {
		if existed {
			_ = os.Setenv(key, previous)
			return
		}
		_ = os.Unsetenv(key)
	})
}

func TestLoad_FallsBackToTheDefaultPrefetchSettings(t *testing.T) {
	setRequiredEnv(t)
	unsetFeedEnv(t)

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	if got.Feed.SweepStaleAfter != 3*time.Minute {
		t.Fatalf("SweepStaleAfter = %s, want the 3 minutes of the feature", got.Feed.SweepStaleAfter)
	}
	if got.Feed.InstanceID == "" {
		t.Fatal("InstanceID is empty, want the hostname or a generated id")
	}
	if got.Feed.LeaseTTL <= got.Feed.PrefetchTimeout || got.Feed.PrefetchMaxUnserved <= 0 || got.Feed.ExcludedIDsCap < got.Feed.PrefetchMaxUnserved {
		t.Fatalf("Feed = %+v, want consistent defaults", got.Feed)
	}
}

func TestLoad_ReadsTheRecommendationSettings(t *testing.T) {
	setRequiredEnv(t)
	setEnv(t, "RECOMMENDATION_GRPC_ADDR", "dns:///recommendation-service.recommendation-service-dev.svc.cluster.local:50051")
	setEnv(t, "RECOMMENDATION_GRPC_DIAL_TIMEOUT_MS", "1200")
	setEnv(t, "RECOMMENDATION_GRPC_PER_RETRY_TIMEOUT_MS", "3000")
	setEnv(t, "RECOMMENDATION_GRPC_OVERALL_TIMEOUT_MS", "9000")
	setEnv(t, "RECOMMENDATION_GRPC_MAX_ATTEMPTS", "3")
	setEnv(t, "RECOMMENDATION_GRPC_BACKOFF_BASE_MS", "100")
	setEnv(t, "RECOMMENDATION_GRPC_BACKOFF_MAX_MS", "800")
	setEnv(t, "RECOMMENDATION_GRPC_BACKOFF_JITTER_PERCENT", "10")

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.RecommendationConfig{
		Address:              "dns:///recommendation-service.recommendation-service-dev.svc.cluster.local:50051",
		DialTimeout:          1200 * time.Millisecond,
		PerRetryTimeout:      3 * time.Second,
		OverallTimeout:       9 * time.Second,
		MaxAttempts:          3,
		BackoffBase:          100 * time.Millisecond,
		BackoffMax:           800 * time.Millisecond,
		BackoffJitterPercent: 10,
	}
	if got.Recommendation != want {
		t.Fatalf("Load() Recommendation = %+v, want %+v", got.Recommendation, want)
	}
}

func TestLoad_RequiresTheRecommendationAddress(t *testing.T) {
	setRequiredEnv(t)
	unsetEnv(t, "RECOMMENDATION_GRPC_ADDR")

	if _, err := config.Load(); err == nil {
		t.Fatal("Load() error = nil, want an error for the missing address")
	}
}

// retry.WithMax counts attempts in go-grpc-middleware v2: 4 is 3 retries.
func TestLoad_TheDefaultDeadlineExpiresDuringTheSecondBackoff(t *testing.T) {
	setRequiredEnv(t)

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	recommendation := got.Recommendation
	jitter := float64(recommendation.BackoffJitterPercent) / 100
	firstWaitMax := time.Duration(float64(recommendation.BackoffBase) * (1 + jitter))
	firstWaitMin := time.Duration(float64(recommendation.BackoffBase) * (1 - jitter))
	secondWaitMin := time.Duration(float64(2*recommendation.BackoffBase) * (1 - jitter))
	twoTimeouts := 2 * recommendation.PerRetryTimeout

	if earliest := twoTimeouts + firstWaitMax; recommendation.OverallTimeout <= earliest {
		t.Fatalf("OverallTimeout = %s, want it above %s so the second attempt gets all its time", recommendation.OverallTimeout, earliest)
	}
	if latest := twoTimeouts + firstWaitMin + secondWaitMin; recommendation.OverallTimeout >= latest {
		t.Fatalf("OverallTimeout = %s, want it below %s so no third attempt can start", recommendation.OverallTimeout, latest)
	}
}

func TestLoad_DefaultsToFourAttemptsWithABackoffCappedAtOneSecond(t *testing.T) {
	setRequiredEnv(t)

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	if got.Recommendation.MaxAttempts != 4 {
		t.Fatalf("MaxAttempts = %d, want 4", got.Recommendation.MaxAttempts)
	}
	if got.Recommendation.BackoffMax != time.Second {
		t.Fatalf("BackoffMax = %s, want 1s", got.Recommendation.BackoffMax)
	}
	if got.Recommendation.OverallTimeout <= got.Recommendation.PerRetryTimeout || got.Feed.RefillLeaseTTL <= got.Recommendation.OverallTimeout || got.Feed.RefillLeaseTTL > got.Feed.LeaseTTL {
		t.Fatalf("Recommendation = %+v, RefillLeaseTTL = %s, LeaseTTL = %s, want consistent defaults", got.Recommendation, got.Feed.RefillLeaseTTL, got.Feed.LeaseTTL)
	}
}

func TestLoad_ReadsTheMediaSettings(t *testing.T) {
	setRequiredEnv(t)
	setEnv(t, "AZURE_STORAGE_ACCOUNT_URL", "https://gazemedia.blob.core.windows.net/")
	setEnv(t, "AZURE_STORAGE_CONTAINER", "post-media")
	unsetEnv(t, "AZURE_STORAGE_ACCOUNT_KEY")
	setEnv(t, "MEDIA_READ_SAS_WINDOW", "12h")

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.MediaStorageConfig{
		AccountURL:    "https://gazemedia.blob.core.windows.net",
		Container:     "post-media",
		ReadSASWindow: 12 * time.Hour,
	}
	if got.Media != want {
		t.Fatalf("Load() Media = %+v, want %+v", got.Media, want)
	}
}

func TestLoad_RejectsMediaSettingsThatMakeNoSense(t *testing.T) {
	tests := []struct {
		name string
		env  map[string]string
	}{
		{"account url is missing", map[string]string{"AZURE_STORAGE_ACCOUNT_URL": ""}},
		{"container is missing", map[string]string{"AZURE_STORAGE_CONTAINER": " "}},
		{"account url is not http or https", map[string]string{"AZURE_STORAGE_ACCOUNT_URL": "ftp://azurite:10000/devstoreaccount1"}},
		{"account url holds a container", map[string]string{"AZURE_STORAGE_ACCOUNT_URL": "http://azurite:10000/devstoreaccount1/post-media-dev"}},
		{"account key with an https account", map[string]string{"AZURE_STORAGE_ACCOUNT_URL": "https://gazemedia.blob.core.windows.net"}},
		{"window is not a duration", map[string]string{"MEDIA_READ_SAS_WINDOW": "24"}},
		{"window is below a minute", map[string]string{"MEDIA_READ_SAS_WINDOW": "30s"}},
		{"window outlives a user delegation key", map[string]string{"MEDIA_READ_SAS_WINDOW": "96h"}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setRequiredEnv(t)
			for name, value := range tt.env {
				setEnv(t, name, value)
			}

			if _, err := config.Load(); err == nil {
				t.Fatalf("Load() error = nil, want an error when the %s", tt.name)
			}
		})
	}
}
