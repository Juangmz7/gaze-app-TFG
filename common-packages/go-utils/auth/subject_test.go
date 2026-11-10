package auth_test

import (
	"context"
	"testing"

	"github.com/lestrrat-go/jwx/v2/jwt"

	"github.com/Juangmz7/TFG/common-packages/go-utils/auth"
)

func TestSubjectFromContext(t *testing.T) {
	withSubject, err := jwt.NewBuilder().Subject("0b1f4a52-0d4b-4a54-9f0e-7d0f5a3c9e11").Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	withoutSubject, err := jwt.NewBuilder().Issuer("issuer").Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}

	tests := []struct {
		name        string
		ctx         context.Context
		wantSubject string
		wantOK      bool
	}{
		{"token with a subject", context.WithValue(context.Background(), auth.TokenClaimsKey, withSubject), "0b1f4a52-0d4b-4a54-9f0e-7d0f5a3c9e11", true},
		{"token without a subject", context.WithValue(context.Background(), auth.TokenClaimsKey, withoutSubject), "", false},
		{"no token", context.Background(), "", false},
		{"a value that is not a token", context.WithValue(context.Background(), auth.TokenClaimsKey, "not-a-token"), "", false},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			subject, ok := auth.SubjectFromContext(test.ctx)
			if subject != test.wantSubject || ok != test.wantOK {
				t.Fatalf("SubjectFromContext() = (%q, %v), want (%q, %v)", subject, ok, test.wantSubject, test.wantOK)
			}
		})
	}
}
