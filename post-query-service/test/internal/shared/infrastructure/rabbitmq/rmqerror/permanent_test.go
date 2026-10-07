package rmqerror_test

import (
	"errors"
	"fmt"
	"testing"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/rabbitmq/rmqerror"
)

func TestIsPermanent_DetectsAPermanentErrorEvenWhenWrapped(t *testing.T) {
	base := errors.New("malformed payload")
	wrapped := fmt.Errorf("decode event: %w", rmqerror.NewPermanent(base))

	if !rmqerror.IsPermanent(wrapped) {
		t.Fatal("IsPermanent() = false, want true for a wrapped Permanent error")
	}
}

func TestIsPermanent_ReturnsFalseForATransientError(t *testing.T) {
	transient := errors.New("mongodb connection reset")

	if rmqerror.IsPermanent(transient) {
		t.Fatal("IsPermanent() = true, want false for a plain transient error")
	}
}

func TestPermanent_ErrorReturnsTheWrappedMessage(t *testing.T) {
	permanent := rmqerror.NewPermanent(errors.New("missing event id"))

	if got, want := permanent.Error(), "missing event id"; got != want {
		t.Fatalf("Error() = %q, want %q", got, want)
	}
}
