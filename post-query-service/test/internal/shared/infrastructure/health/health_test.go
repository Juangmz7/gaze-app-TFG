package health_test

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/health"
)

// unreachableClient returns a client whose server selection fails fast:
// nothing listens on port 1.
func unreachableClient(t *testing.T) *mongo.Client {
	t.Helper()

	client, err := mongo.Connect(options.Client().ApplyURI("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=300"))
	if err != nil {
		t.Fatalf("mongo.Connect() error = %v, want nil", err)
	}
	return client
}

func serve(t *testing.T, client *mongo.Client, method, path string) int {
	t.Helper()

	mux := http.NewServeMux()
	health.Register(mux, client)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(method, path, nil))
	return rec.Code
}

func TestRegister_Liveness_ReturnsOKWithoutTouchingMongo(t *testing.T) {
	if got := serve(t, unreachableClient(t), http.MethodGet, health.LivePath); got != http.StatusOK {
		t.Fatalf("GET %s status = %d, want %d", health.LivePath, got, http.StatusOK)
	}
}

func TestRegister_Readiness_ReturnsServiceUnavailableWhenMongoIsUnreachable(t *testing.T) {
	if got := serve(t, unreachableClient(t), http.MethodGet, health.ReadyPath); got != http.StatusServiceUnavailable {
		t.Fatalf("GET %s status = %d, want %d", health.ReadyPath, got, http.StatusServiceUnavailable)
	}
}

func TestRegister_Probes_RejectNonGetMethods(t *testing.T) {
	if got := serve(t, unreachableClient(t), http.MethodPost, health.LivePath); got != http.StatusMethodNotAllowed {
		t.Fatalf("POST %s status = %d, want %d", health.LivePath, got, http.StatusMethodNotAllowed)
	}
}
