package config_test

import (
	"os"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
)

func TestLoad_ParsesValidEnvironmentVariablesIntoConfig(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	setEnv(t, "POST_QUERY_MONGO_DATABASE", "post_query_read_models")
	setEnv(t, "RABBITMQ_HOST", "rabbitmq")
	setEnv(t, "RABBITMQ_PORT", "5673")
	setEnv(t, "RABBITMQ_USER", "post_query_user")
	setEnv(t, "RABBITMQ_PASSWORD", "post_query_password")

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

func TestRabbitMQConfig_AMQPURI_BuildsConnectionStringFromFields(t *testing.T) {
	cfg := config.RabbitMQConfig{
		Host:     "rabbitmq",
		Port:     "5672",
		User:     "post_query_user",
		Password: "post_query_password",
	}

	want := "amqp://post_query_user:post_query_password@rabbitmq:5672/"
	if got := cfg.AMQPURI(); got != want {
		t.Fatalf("AMQPURI() = %q, want %q", got, want)
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
