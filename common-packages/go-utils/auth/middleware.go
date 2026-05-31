package auth

import (
	"context"
	"net/http"
	"log/slog"
	"strings"

	"github.com/lestrrat-go/jwx/v2/jwk"
	"github.com/lestrrat-go/jwx/v2/jwt"

	"github.com/Juangmz7/TFG/common-packages/go-utils/common"
)

// type for context keys in this package.
type contextKey string

const TokenClaimsKey contextKey = "token_claims"

// It extracts the Bearer token, validates it, and stores the parsed token
// in the request context so handlers can read claims without re-parsing.
func Authenticate(cache *KeyCache, issuer, audience string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authHeader := r.Header.Get("Authorization")
			if authHeader == "" {
				slog.Error("missing Authorization header")
				common.RespondWithError(
					w,
					r,
					http.StatusUnauthorized,
					"missing_authorization_header",
					"Missing Authorization header",
					nil,
				)
				return
			}

			parts := strings.SplitN(authHeader, " ", 2)
			if len(parts) != 2 || strings.ToLower(parts[0]) != "bearer" {
				slog.Error("invalid Authorization header format")
				common.RespondWithError(
					w,
					r,
					http.StatusUnauthorized,
					"invalid_authorization_header",
					"Invalid Authorization header format",
					nil,
				)
				return
			}
			rawToken := parts[1]

			// Get the current key set from cache
			keySet, err := cache.Get(r.Context())
			if err != nil {
				slog.Error("failed to get signing keys from cache", "error", err)
				common.RespondWithError(
					w,
					r,
					http.StatusInternalServerError,
					"internal_server_error",
					"Internal server error",
					nil,
				)
				return
			}

			token, err := parseAndValidateToken(rawToken, keySet, issuer, audience)
			if err != nil {
				slog.Error("failed to parse and validate token", "error", err)
				common.RespondWithError(
					w,
					r,
					http.StatusUnauthorized,
					"invalid_token",
					"Invalid token",
					nil,
				)
				return
			}

			// 4. Store the validated token in context for handlers to use
			ctx := context.WithValue(r.Context(), TokenClaimsKey, token)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// TODO: Helper function to retrieve token claims from context in handlers

func parseAndValidateToken(rawToken string, keySet jwk.Set, issuer, audience string) (jwt.Token, error) {
	return jwt.Parse(
				[]byte(rawToken),
				jwt.WithKeySet(keySet),
				jwt.WithValidate(true),
				jwt.WithIssuer(issuer),
				jwt.WithAudience(audience),
			)
}
