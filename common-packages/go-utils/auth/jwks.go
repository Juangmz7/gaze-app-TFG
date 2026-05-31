package auth

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/lestrrat-go/jwx/v2/jwk"
)

// KeyCache wraps the jwk.Cache and exposes a method to get the current key set.
// The JWKS endpoint can rotate keys. The cache re-fetches automatically
// in the background without blocking your requests.
type KeyCache struct {
	cache 	*jwk.Cache
	jwksURL string
}

func NewCacheKey(ctx context.Context, jwksURL string) (*KeyCache, error) {
	if jwksURL == "" {
		return nil, fmt.Errorf("JWKS URL is required")
	}

	cache := jwk.NewCache(ctx)

	slog.Info("Initializing JWKS cache for URL: " + jwksURL)

	// Register the URL with a refresh interval.
    // jwk.Cache will fetch once immediately, then refresh every 15 minutes.
	err := cache.Register(jwksURL, jwk.WithMinRefreshInterval(15*time.Minute))
	if err != nil {
		return nil, fmt.Errorf("failed to register JWKS URL: %w", err)
	}

	// Force an initial fetch so we fail at startup if the URL is wrong,
    // rather than failing on the first real request.
	_, err = cache.Refresh(ctx, jwksURL)
	if err != nil {
        slog.Error("failed to fetch JWKS on startup", "error", err)
        return nil, fmt.Errorf("failed to fetch JWKS on startup: %w", err)
    }

	slog.Info("JWKS cache initialized successfully for URL: " + jwksURL)

    return &KeyCache{cache: cache, jwksURL: jwksURL}, nil
}

func (k *KeyCache) Get(ctx context.Context) (jwk.Set, error) {
    return k.cache.Get(ctx, k.jwksURL)
}