package auth

import (
	"context"
	"strings"

	"github.com/lestrrat-go/jwx/v2/jwt"
)

// SubjectFromContext returns the subject (sub claim) of the token Authenticate
// stored in ctx. It reports false when ctx holds no validated token or the token
// has no subject. The value is the raw claim: callers validate its format.
func SubjectFromContext(ctx context.Context) (string, bool) {
	if ctx == nil {
		return "", false
	}

	token, ok := ctx.Value(TokenClaimsKey).(jwt.Token)
	if !ok || token == nil {
		return "", false
	}

	subject := token.Subject()
	if strings.TrimSpace(subject) == "" {
		return "", false
	}

	return subject, true
}
