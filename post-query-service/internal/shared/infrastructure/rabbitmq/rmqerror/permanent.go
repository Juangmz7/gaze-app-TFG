// Package rmqerror classifies RabbitMQ handler errors as permanent or
// transient so the retry middleware knows whether a failure deserves
// exponential backoff or should be dead-lettered immediately.
package rmqerror

import "errors"

// Permanent wraps an error that must never be retried: malformed payloads,
// missing envelope fields, or any other validation failure that will not
// succeed on a later attempt. Handlers return a Permanent error to send the
// message straight to its dead-letter queue instead of wasting retry
// attempts on it.
type Permanent struct {
	err error
}

// NewPermanent wraps err as a Permanent error.
func NewPermanent(err error) *Permanent {
	return &Permanent{err: err}
}

// Error implements the error interface.
func (p *Permanent) Error() string {
	return p.err.Error()
}

// Unwrap allows errors.Is/errors.As to see the wrapped error.
func (p *Permanent) Unwrap() error {
	return p.err
}

// IsPermanent reports whether err (or any error it wraps) is a Permanent
// error.
func IsPermanent(err error) bool {
	var permanent *Permanent
	return errors.As(err, &permanent)
}
