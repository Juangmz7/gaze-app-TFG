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

// defaultHTTPPort is used when HTTP_PORT is not set, matching
// common-packages/go-utils/server.LoadServer's default.
const defaultHTTPPort = "8080"

// MongoConfig holds the settings required to connect to MongoDB.
type MongoConfig struct {
	// URI is the MongoDB connection string, e.g. mongodb://host:27017.
	URI string
	// Database is the name of the database this service reads from and writes to.
	Database string
}

// AppConfig holds the full post-query-service configuration.
type AppConfig struct {
	// Mongo holds MongoDB connection settings.
	Mongo MongoConfig
	// Port is the HTTP port the service listens on.
	Port string
}

// Load reads AppConfig from environment variables. It returns an error when
// a required variable is missing or blank; POST_QUERY_MONGO_URI is required.
// POST_QUERY_MONGO_DATABASE and HTTP_PORT fall back to sensible defaults.
func Load() (AppConfig, error) {
	uri := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_URI"))
	if uri == "" {
		return AppConfig{}, fmt.Errorf("load config: required environment variable %s is missing", "POST_QUERY_MONGO_URI")
	}

	database := strings.TrimSpace(os.Getenv("POST_QUERY_MONGO_DATABASE"))
	if database == "" {
		database = defaultMongoDatabase
	}

	port := strings.TrimSpace(os.Getenv("HTTP_PORT"))
	if port == "" {
		port = defaultHTTPPort
	}

	return AppConfig{
		Mongo: MongoConfig{
			URI:      uri,
			Database: database,
		},
		Port: port,
	}, nil
}
