// Package mediasas signs the blob URLs this service returns with a short-lived,
// read-only SAS, so clients can download media from a private container. It is the one
// place a SAS is built: response mappers call it through a port they declare, handlers
// never build one, and a signed URL is never stored.
//
// Signing is a local HMAC-SHA256. The only network call is fetching a user delegation
// key in production, once per window, never per URL or per request.
//
// URLs are cache friendly: time is cut into fixed windows of Options.Window (24h by
// default), counted from the Unix epoch. Every URL signed within one window carries the
// same SAS, so the same blob gets a byte-identical URL (the app's image cache keys on
// it), and that SAS stays valid for at least one more window after it was issued:
//
//	window start = floor(now / window) * window
//	st           = window start - 5 min (clock skew)
//	se           = window start + 2 * window (48h by default)
package mediasas

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"
)

// ClockSkew is how far before its window a SAS starts, so a client whose clock runs a
// little behind can use it right away.
const ClockSkew = 5 * time.Minute

// MinWindow and MaxWindow bound Options.Window. Above MaxWindow a SAS would outlive the
// 7 days a user delegation key can last: a SAS lasts two windows plus the clock skew.
const (
	MinWindow = time.Minute
	MaxWindow = 72 * time.Hour
)

// readPermission is the only permission ever granted: read, never write, delete or list.
const readPermission = "r"

// Validity is when a SAS, and the key that signs it, is valid.
type Validity struct {
	// Start is the SAS start time (st).
	Start time.Time
	// Expiry is the SAS expiry time (se).
	Expiry time.Time
}

// Credential signs SAS values with a key.
type Credential interface {
	// Sign signs values and returns the SAS query parameters.
	Sign(values sas.BlobSignatureValues) (sas.QueryParameters, error)
}

// CredentialSource hands out the credential that signs the SAS of one window.
type CredentialSource interface {
	// Credential returns a credential valid for the whole of validity.
	Credential(ctx context.Context, validity Validity) (Credential, error)
}

// Options configures a Signer.
type Options struct {
	// AccountURL is the storage account's blob endpoint: https://<account>.blob.core.windows.net,
	// or http://<host>:10000/<account> for Azurite (path style).
	AccountURL string
	// Container is the only container whose blobs are signed.
	Container string
	// Window is the length of a signing window, between MinWindow and MaxWindow.
	Window time.Duration
	// Source provides the signing key.
	Source CredentialSource
	// Now reads the clock; nil means time.Now.
	Now func() time.Time
}

// Signer adds a read-only SAS to the URLs of blobs in the configured container.
type Signer struct {
	scheme    string
	host      string
	prefix    string
	container string
	window    time.Duration
	protocol  sas.Protocol
	source    CredentialSource
	now       func() time.Time
}

// New validates options and creates a Signer.
func New(options Options) (*Signer, error) {
	account, err := ParseAccountURL(options.AccountURL)
	if err != nil {
		return nil, err
	}
	if err := validateContainer(options.Container); err != nil {
		return nil, err
	}
	if options.Window < MinWindow || options.Window > MaxWindow {
		return nil, fmt.Errorf("media sas: window %s must be between %s and %s", options.Window, MinWindow, MaxWindow)
	}
	if options.Source == nil {
		return nil, errors.New("media sas: a credential source is required")
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}

	// HTTPS only, unless the account itself is served over plain HTTP (Azurite).
	protocol := sas.ProtocolHTTPS
	if account.Scheme == "http" {
		protocol = sas.ProtocolHTTPSandHTTP
	}

	return &Signer{
		scheme:    account.Scheme,
		host:      account.Host,
		prefix:    account.BasePath + "/" + options.Container + "/",
		container: options.Container,
		window:    options.Window,
		protocol:  protocol,
		source:    options.Source,
		now:       now,
	}, nil
}

// SignReadURL returns rawURL with a read-only SAS for its blob. A URL that is not a bare
// blob URL of the configured account and container (another host, another container, a
// query string already present, a malformed URL, an empty string) is returned unchanged.
//
// When the key of the current window cannot be fetched, it signs with the previous
// window's key if it holds it: that SAS is still valid until the end of the current
// window.
func (s *Signer) SignReadURL(ctx context.Context, rawURL string) (string, error) {
	blobName, ok := s.blobName(rawURL)
	if !ok {
		return rawURL, nil
	}

	validity := s.ValidityAt(s.now())
	credential, err := s.source.Credential(ctx, validity)
	if err != nil {
		previous := Validity{Start: validity.Start.Add(-s.window), Expiry: validity.Expiry.Add(-s.window)}
		fallback, fallbackErr := s.source.Credential(ctx, previous)
		if fallbackErr != nil {
			return "", fmt.Errorf("media sas: credential for %s: %w", validity.Start.Format(time.RFC3339), err)
		}
		validity, credential = previous, fallback
	}

	query, err := credential.Sign(sas.BlobSignatureValues{
		Protocol:      s.protocol,
		StartTime:     validity.Start,
		ExpiryTime:    validity.Expiry,
		Permissions:   readPermission,
		ContainerName: s.container,
		BlobName:      blobName,
	})
	if err != nil {
		return "", fmt.Errorf("media sas: sign blob: %w", err)
	}

	return rawURL + "?" + query.Encode(), nil
}

// ValidityAt returns the SAS validity of the window that holds now. See the package
// comment.
func (s *Signer) ValidityAt(now time.Time) Validity {
	window := s.window.Nanoseconds()
	start := time.Unix(0, now.UnixNano()/window*window).UTC()

	return Validity{Start: start.Add(-ClockSkew), Expiry: start.Add(2 * s.window)}
}

// blobName returns the blob name of a bare blob URL of the configured container.
func (s *Signer) blobName(rawURL string) (string, bool) {
	if rawURL == "" {
		return "", false
	}
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Opaque != "" || parsed.User != nil || parsed.RawQuery != "" || parsed.ForceQuery || parsed.Fragment != "" {
		return "", false
	}
	if !strings.EqualFold(parsed.Scheme, s.scheme) || !strings.EqualFold(parsed.Host, s.host) {
		return "", false
	}
	blobName, found := strings.CutPrefix(parsed.Path, s.prefix)
	if !found || blobName == "" || strings.HasSuffix(blobName, "/") {
		return "", false
	}

	return blobName, true
}

// AccountURL is a parsed storage account URL.
type AccountURL struct {
	// Scheme is http or https.
	Scheme string
	// Host is the host, with its port when it has one.
	Host string
	// BasePath is "/<account>" for a path-style URL (Azurite), empty otherwise.
	BasePath string
	// AccountName is the account: the path segment of a path-style URL, otherwise the
	// first label of the host.
	AccountName string
}

// ParseAccountURL parses and validates a storage account URL.
func ParseAccountURL(raw string) (AccountURL, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return AccountURL{}, fmt.Errorf("media sas: account url: %w", err)
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return AccountURL{}, fmt.Errorf("media sas: account url %q must be http or https", raw)
	}
	if parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return AccountURL{}, fmt.Errorf("media sas: account url %q must be a bare http(s)://host[/account] url", raw)
	}

	path := strings.Trim(parsed.Path, "/")
	if strings.Contains(path, "/") {
		return AccountURL{}, fmt.Errorf("media sas: account url %q may hold at most the account in its path", raw)
	}
	if path != "" {
		return AccountURL{Scheme: parsed.Scheme, Host: parsed.Host, BasePath: "/" + path, AccountName: path}, nil
	}

	accountName, _, _ := strings.Cut(parsed.Hostname(), ".")
	return AccountURL{Scheme: parsed.Scheme, Host: parsed.Host, AccountName: accountName}, nil
}

// validateContainer checks the container name rules of Azure Storage: 3 to 63 lowercase
// letters, digits and single hyphens, starting and ending with a letter or a digit.
func validateContainer(name string) error {
	const minLength, maxLength = 3, 63
	valid := len(name) >= minLength && len(name) <= maxLength &&
		name[0] != '-' && name[len(name)-1] != '-' && !strings.Contains(name, "--")
	for _, r := range name {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
			valid = false
		}
	}
	if !valid {
		return fmt.Errorf("media sas: container %q is not a valid container name", name)
	}

	return nil
}

// Settings is what NewFromSettings builds a Signer from.
type Settings struct {
	// AccountURL is the storage account's blob endpoint.
	AccountURL string
	// Container is the media container.
	Container string
	// AccountKey signs with the account key (Azurite). Empty means a user delegation key
	// through Workload Identity.
	AccountKey string
	// Window is the length of a signing window.
	Window time.Duration
}

// NewFromSettings builds the service's one Signer: with an account key when one is
// given (Azurite), otherwise with user delegation keys fetched through Workload
// Identity. It makes no network call.
func NewFromSettings(settings Settings, logger *slog.Logger) (*Signer, error) {
	account, err := ParseAccountURL(settings.AccountURL)
	if err != nil {
		return nil, err
	}

	var source CredentialSource
	if settings.AccountKey != "" {
		source, err = NewSharedKeySource(account.AccountName, settings.AccountKey)
	} else {
		source, err = newAzureUserDelegationSource(settings.AccountURL, logger)
	}
	if err != nil {
		return nil, err
	}

	return New(Options{AccountURL: settings.AccountURL, Container: settings.Container, Window: settings.Window, Source: source})
}

func newAzureUserDelegationSource(accountURL string, logger *slog.Logger) (*UserDelegationSource, error) {
	fetcher, err := NewAzureDelegationKeyFetcher(accountURL)
	if err != nil {
		return nil, err
	}

	return NewUserDelegationSource(fetcher, nil, logger)
}
