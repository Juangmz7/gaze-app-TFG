// Package config loads post-query-service configuration from environment
// variables. It fails fast when a required variable is missing so the
// service never starts in a half-configured state.
package config

import (
	"fmt"
	"os"
	"strings"
)

// defaultMongoDatabase is used when POST_QUERY_MONGO_DATABASE is not set.
const defaultMongoDatabase = "post_query"

// MongoConfig holds the settings required to connect to MongoDB.
type MongoConfig struct {
	// URI is the MongoDB connection string, e.g. mongodb://host:27017.
	URI string
	// Database is the name of the database this service reads from and writes to.
	Database string
}

// AppConfig holds the post-query-service-specific configuration. The HTTP
// port is intentionally not duplicated here: it is already loaded by
// common-packages/go-utils/server.LoadServer, which callers should use
// directly to avoid two packages parsing HTTP_PORT the same way.
type AppConfig struct {
	// Mongo holds MongoDB connection settings.
	Mongo MongoConfig
}

// Load reads AppConfig from environment variables. It returns an error when
// a required variable is missing or blank; POST_QUERY_MONGO_URI is required.
// POST_QUERY_MONGO_DATABASE falls back to a sensible default.
func Load() (AppConfig, error) {
	uri := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_URI"))
	if uri == "" {
		return AppConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "POST_QUERY_MONGO_URI")
	}

	database := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_DATABASE"))
	if database == "" {
		database = defaultMongoDatabase
	}

	return AppConfig{
		Mongo: MongoConfig{
			URI:      uri,
			Database: database,
		},
	}, nil
}
