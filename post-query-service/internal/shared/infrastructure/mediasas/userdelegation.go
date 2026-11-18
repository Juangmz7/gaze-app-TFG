package mediasas

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/service"
)

// FetchRetryInterval is how long a failed key fetch is remembered before another
// attempt, so a storage or Entra ID outage does not turn every request into a call.
const FetchRetryInterval = 30 * time.Second

// DelegationKeyFetcher fetches a user delegation key valid from start to expiry.
type DelegationKeyFetcher interface {
	Fetch(ctx context.Context, start, expiry time.Time) (Credential, error)
}

// UserDelegationSource hands out user delegation keys, fetched once per window.
//
// The key of a window is valid exactly as long as the SAS it signs: from the window's
// start minus the clock skew to its expiry. Every replica therefore asks for the same
// validity, and the SAS fields that come from the key (skt, ske) are identical across
// replicas. The key is replaced when the next window starts, long before it expires
// (it outlives its window by one more window), and the previous key is kept so the
// signer can fall back on it while a fetch fails.
type UserDelegationSource struct {
	fetcher DelegationKeyFetcher
	now     func() time.Time
	logger  *slog.Logger

	mu       sync.Mutex
	current  *delegationKey
	previous *delegationKey
	lastErr  error
	retryAt  time.Time
}

type delegationKey struct {
	validity   Validity
	credential Credential
}

// NewUserDelegationSource creates a UserDelegationSource. now nil means time.Now.
func NewUserDelegationSource(fetcher DelegationKeyFetcher, now func() time.Time, logger *slog.Logger) (*UserDelegationSource, error) {
	if fetcher == nil {
		return nil, errors.New("media sas: a delegation key fetcher is required")
	}
	if logger == nil {
		return nil, errors.New("media sas: a logger is required")
	}
	if now == nil {
		now = time.Now
	}

	return &UserDelegationSource{fetcher: fetcher, now: now, logger: logger}, nil
}

// Credential returns the key of validity, fetching it the first time it is asked for.
// After a failed fetch it answers that error, without another call, for
// FetchRetryInterval. Concurrent callers wait for the one fetch in flight.
func (s *UserDelegationSource) Credential(ctx context.Context, validity Validity) (Credential, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, key := range []*delegationKey{s.current, s.previous} {
		if key != nil && key.validity == validity {
			return key.credential, nil
		}
	}
	if s.lastErr != nil && s.now().Before(s.retryAt) {
		return nil, s.lastErr
	}

	credential, err := s.fetcher.Fetch(ctx, validity.Start, validity.Expiry)
	if err != nil {
		s.lastErr = fmt.Errorf("fetch user delegation key: %w", err)
		s.retryAt = s.now().Add(FetchRetryInterval)
		s.logger.WarnContext(ctx, "fetching the user delegation key for media read SAS failed",
			"key_start", validity.Start, "key_expiry", validity.Expiry, "retry_at", s.retryAt, "error", err)
		return nil, s.lastErr
	}

	s.lastErr = nil
	s.previous, s.current = s.current, &delegationKey{validity: validity, credential: credential}
	s.logger.InfoContext(ctx, "fetched the user delegation key for media read SAS",
		"key_start", validity.Start, "key_expiry", validity.Expiry)

	return credential, nil
}

// AzureDelegationKeyFetcher fetches user delegation keys from Blob Storage, authenticated
// through AKS Workload Identity (the AZURE_CLIENT_ID, AZURE_TENANT_ID and
// AZURE_FEDERATED_TOKEN_FILE variables its webhook injects).
type AzureDelegationKeyFetcher struct {
	client *service.Client
}

// NewAzureDelegationKeyFetcher creates an AzureDelegationKeyFetcher for the account at
// accountURL. The identity needs a role that can delegate, such as Storage Blob Delegator,
// plus read on the container (Storage Blob Data Reader).
func NewAzureDelegationKeyFetcher(accountURL string) (*AzureDelegationKeyFetcher, error) {
	credential, err := azidentity.NewWorkloadIdentityCredential(nil)
	if err != nil {
		return nil, fmt.Errorf("media sas: workload identity credential: %w", err)
	}
	client, err := service.NewClient(accountURL, credential, nil)
	if err != nil {
		return nil, fmt.Errorf("media sas: blob service client: %w", err)
	}

	return &AzureDelegationKeyFetcher{client: client}, nil
}

// Fetch asks Blob Storage for a user delegation key valid from start to expiry.
func (f *AzureDelegationKeyFetcher) Fetch(ctx context.Context, start, expiry time.Time) (Credential, error) {
	credential, err := f.client.GetUserDelegationCredential(ctx, service.KeyInfo{
		Start:  to.Ptr(start.UTC().Format(sas.TimeFormat)),
		Expiry: to.Ptr(expiry.UTC().Format(sas.TimeFormat)),
	}, nil)
	if err != nil {
		return nil, err
	}

	return userDelegationCredential{credential: credential}, nil
}

type userDelegationCredential struct {
	credential *service.UserDelegationCredential
}

func (c userDelegationCredential) Sign(values sas.BlobSignatureValues) (sas.QueryParameters, error) {
	return values.SignWithUserDelegation(c.credential)
}
