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
			DefaultPageSize:        15,
			MaxPageSize:            15,
			MaxReadsPerClaim:       3,
			Retention:              168 * time.Hour,
			DisconnectWriteTimeout: 5 * time.Second,
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

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.FeedConfig{
		DefaultPageSize:        10,
		MaxPageSize:            30,
		MaxReadsPerClaim:       5,
		Retention:              24 * time.Hour,
		DisconnectWriteTimeout: 750 * time.Millisecond,
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
	unsetFeedEnv(t)
}

func setAuthEnv(t *testing.T) {
	t.Helper()
	setEnv(t, "JWKS_URL", "http://keycloak:8080/certs")
	setEnv(t, "JWT_ISSUER", "http://localhost:8080/realms/auth-service")
	setEnv(t, "JWT_AUDIENCE", "account")
}

func unsetFeedEnv(t *testing.T) {
	t.Helper()
	for _, name := range []string{"FEED_DEFAULT_PAGE_SIZE", "FEED_MAX_PAGE_SIZE", "FEED_MAX_READS_PER_CLAIM", "FEED_RETENTION_HOURS", "FEED_DISCONNECT_WRITE_TIMEOUT_MS"} {
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
