// Package config loads post-query-service configuration from environment
// variables. It fails fast when a required variable is missing so the
// service never starts in a half-configured state.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strings"
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

// AMQPURI builds the amqp:// connection URI from the configured host, port,
// and credentials.
func (c RabbitMQConfig) AMQPURI() string {
	return fmt.Sprintf("amqp://%s:%s@%s:%s/", url.QueryEscape(c.User), url.QueryEscape(c.Password), c.Host, c.Port)
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
}

// Load reads AppConfig from environment variables. It returns an error when
// a required variable is missing or blank; POST_QUERY_MONGO_URI,
// RABBITMQ_USER, and RABBITMQ_PASSWORD are required. POST_QUERY_MONGO_DATABASE,
// RABBITMQ_HOST, and RABBITMQ_PORT fall back to sensible defaults.
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

	return AppConfig{
		Mongo: MongoConfig{
			URI:      uri,
			Database: database,
		},
		RabbitMQ: rabbitMQ,
	}, nil
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
