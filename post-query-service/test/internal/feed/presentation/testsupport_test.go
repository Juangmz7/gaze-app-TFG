package presentation_test

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/lestrrat-go/jwx/v2/jwa"
	"github.com/lestrrat-go/jwx/v2/jwk"
	"github.com/lestrrat-go/jwx/v2/jwt"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
)

const (
	testIssuer   = "https://auth.test/realms/auth-service"
	testAudience = "account"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// tokenIssuer is a real JWKS endpoint plus the private key behind it, so tests run
// the real auth.Authenticate instead of a stand-in.
type tokenIssuer struct {
	key          jwk.Key
	authenticate func(http.Handler) http.Handler
}

var (
	sharedIssuerOnce sync.Once
	sharedIssuerKey  jwk.Key
	sharedIssuerSet  []byte
	sharedIssuerErr  error
)

func generateSigningKey() {
	raw, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		sharedIssuerErr = err
		return
	}
	key, err := jwk.FromRaw(raw)
	if err != nil {
		sharedIssuerErr = err
		return
	}
	if err := key.Set(jwk.KeyIDKey, "test-key"); err != nil {
		sharedIssuerErr = err
		return
	}
	if err := key.Set(jwk.AlgorithmKey, jwa.RS256); err != nil {
		sharedIssuerErr = err
		return
	}
	public, err := key.PublicKey()
	if err != nil {
		sharedIssuerErr = err
		return
	}
	set := jwk.NewSet()
	if err := set.AddKey(public); err != nil {
		sharedIssuerErr = err
		return
	}
	sharedIssuerSet, sharedIssuerErr = json.Marshal(set)
	sharedIssuerKey = key
}

func newTokenIssuer(t *testing.T) *tokenIssuer {
	t.Helper()

	sharedIssuerOnce.Do(generateSigningKey)
	if sharedIssuerErr != nil {
		t.Fatalf("generate signing key: %v", sharedIssuerErr)
	}

	jwks := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write(sharedIssuerSet); err != nil {
			t.Errorf("write JWKS: %v", err)
		}
	}))
	t.Cleanup(jwks.Close)

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	cache, err := auth.NewCacheKey(ctx, jwks.URL)
	if err != nil {
		t.Fatalf("NewCacheKey() error = %v", err)
	}

	return &tokenIssuer{key: sharedIssuerKey, authenticate: auth.Authenticate(cache, testIssuer, testAudience)}
}

func (i *tokenIssuer) sign(t *testing.T, build func(*jwt.Builder) *jwt.Builder) string {
	t.Helper()

	builder := jwt.NewBuilder().
		Issuer(testIssuer).
		Audience([]string{testAudience}).
		IssuedAt(time.Now()).
		Expiration(time.Now().Add(time.Hour))
	token, err := build(builder).Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	signed, err := jwt.Sign(token, jwt.WithKey(jwa.RS256, i.key))
	if err != nil {
		t.Fatalf("Sign() error = %v", err)
	}

	return string(signed)
}

func (i *tokenIssuer) tokenFor(t *testing.T, subject string) string {
	t.Helper()
	return i.sign(t, func(builder *jwt.Builder) *jwt.Builder { return builder.Subject(subject) })
}

// serve runs one request through the mux and returns the recorded response.
func serve(mux *http.ServeMux, method, target, token string, headers map[string]string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, target, nil)
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, request)
	return recorder
}

func jwtPast() time.Time {
	return time.Now().Add(-time.Hour)
}

// withSubject stands in for auth.Authenticate in tests about what comes after it: it
// stores the same kind of token under the same key.
func withSubject(t *testing.T, subject string) func(http.Handler) http.Handler {
	t.Helper()

	token, err := jwt.NewBuilder().Subject(subject).Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), auth.TokenClaimsKey, token)))
		})
	}
}
