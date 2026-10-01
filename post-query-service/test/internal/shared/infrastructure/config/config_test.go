package config_test

import (
	"os"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/config"
)

func TestLoad_ParsesValidEnvironmentVariablesIntoConfig(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	setEnv(t, "POST_QUERY_MONGO_DATABASE", "post_query_read_models")
	setEnv(t, "HTTP_PORT", "9090")

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	want := config.AppConfig{
		Mongo: config.MongoConfig{
			URI:      "mongodb://post-query-mongo:27017",
			Database: "post_query_read_models",
		},
		Port: "9090",
	}

	if got != want {
		t.Fatalf("Load() = %+v, want %+v", got, want)
	}
}

func TestLoad_AppliesDefaultsWhenOptionalVariablesAreMissing(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "mongodb://post-query-mongo:27017")
	unsetEnv(t, "POST_QUERY_MONGO_DATABASE")
	unsetEnv(t, "HTTP_PORT")

	got, err := config.Load()
	if err != nil {
		t.Fatalf("Load() unexpected error: %v", err)
	}

	if got.Mongo.Database == "" {
		t.Fatal("Load() Mongo.Database = \"\", want a non-empty default")
	}

	if got.Port == "" {
		t.Fatal("Load() Port = \"\", want a non-empty default")
	}
}

func TestLoad_ReturnsErrorWhenMongoURIIsMissing(t *testing.T) {
	unsetEnv(t, "POST_QUERY_MONGO_URI")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for missing POST_QUERY_MONGO_URI")
	}
}

func TestLoad_ReturnsErrorWhenMongoURIIsBlank(t *testing.T) {
	setEnv(t, "POST_QUERY_MONGO_URI", "   ")

	_, err := config.Load()
	if err == nil {
		t.Fatal("Load() error = nil, want error for blank POST_QUERY_MONGO_URI")
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
