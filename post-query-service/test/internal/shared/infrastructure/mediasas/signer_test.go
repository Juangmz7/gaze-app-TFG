package mediasas_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/mediasas"
)

// Azurite's well-known development account, which is public and no secret.
const (
	azuriteAccountURL  = "http://azurite:10000/devstoreaccount1"
	azuriteAccountName = "devstoreaccount1"
	azuriteAccountKey  = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
	mediaContainer     = "post-media-dev"
	blobURL            = azuriteAccountURL + "/" + mediaContainer + "/5b0f4c1e-8f7a-4d4e-9a57-2c1d7e0b6f10"
)

// windowStart is a UTC midnight: with the default 24h window, every instant of that day
// falls in the window that starts there.
var windowStart = time.Date(2026, 11, 18, 0, 0, 0, 0, time.UTC)

// clock is a settable clock for one test.
type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) Set(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = now
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func sharedKeySource(t *testing.T) *mediasas.SharedKeySource {
	t.Helper()

	source, err := mediasas.NewSharedKeySource(azuriteAccountName, azuriteAccountKey)
	if err != nil {
		t.Fatalf("NewSharedKeySource() error = %v", err)
	}
	return source
}

func newSigner(t *testing.T, accountURL string, window time.Duration, source mediasas.CredentialSource, now *clock) *mediasas.Signer {
	t.Helper()

	signer, err := mediasas.New(mediasas.Options{AccountURL: accountURL, Container: mediaContainer, Window: window, Source: source, Now: now.Now})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	return signer
}

func sign(t *testing.T, signer *mediasas.Signer, rawURL string) string {
	t.Helper()

	signed, err := signer.SignReadURL(context.Background(), rawURL)
	if err != nil {
		t.Fatalf("SignReadURL(%q) error = %v", rawURL, err)
	}
	return signed
}

func sasQuery(t *testing.T, signed string) url.Values {
	t.Helper()

	parsed, err := url.Parse(signed)
	if err != nil {
		t.Fatalf("url.Parse(%q) error = %v", signed, err)
	}
	return parsed.Query()
}

func TestSignReadURL_ReturnsTheSameURLWithinOneWindowAndANewOneInTheNext(t *testing.T) {
	now := &clock{now: windowStart.Add(30 * time.Minute)}
	signer := newSigner(t, azuriteAccountURL, 24*time.Hour, sharedKeySource(t), now)

	first := sign(t, signer, blobURL)
	now.Set(windowStart.Add(23*time.Hour + 59*time.Minute))
	sameWindow := sign(t, signer, blobURL)
	now.Set(windowStart.Add(24 * time.Hour))
	nextWindow := sign(t, signer, blobURL)

	if first != sameWindow {
		t.Fatalf("URLs signed in one window differ:\n%s\n%s", first, sameWindow)
	}
	if nextWindow == first {
		t.Fatalf("URL signed in the next window = %s, want a new one", nextWindow)
	}
	if !strings.HasPrefix(first, blobURL+"?") {
		t.Fatalf("signed URL = %s, want the bare URL plus a SAS", first)
	}
}

func TestSignReadURL_GrantsReadOnlyOnTheBlobForTwoWindows(t *testing.T) {
	now := &clock{now: windowStart.Add(17 * time.Hour)}
	signer := newSigner(t, azuriteAccountURL, 24*time.Hour, sharedKeySource(t), now)

	query := sasQuery(t, sign(t, signer, blobURL))

	want := map[string]string{
		"sp":  "r",
		"sr":  "b",
		"st":  windowStart.Add(-5 * time.Minute).Format(sas.TimeFormat),
		"se":  windowStart.Add(48 * time.Hour).Format(sas.TimeFormat),
		"spr": "https,http",
	}
	for name, value := range want {
		if got := query.Get(name); got != value {
			t.Fatalf("SAS %s = %q, want %q (query %v)", name, got, value, query)
		}
	}
	if query.Get("sig") == "" {
		t.Fatalf("SAS = %v, want a signature", query)
	}
}

func TestSignReadURL_IsHTTPSOnlyForAnHTTPSAccount(t *testing.T) {
	const accountURL = "https://gazemedia.blob.core.windows.net"
	now := &clock{now: windowStart}
	signer := newSigner(t, accountURL, 24*time.Hour, sharedKeySource(t), now)

	query := sasQuery(t, sign(t, signer, accountURL+"/"+mediaContainer+"/clip.mp4"))

	if got := query.Get("spr"); got != "https" {
		t.Fatalf("SAS spr = %q, want https", got)
	}
}

func TestSignReadURL_FollowsTheConfiguredWindow(t *testing.T) {
	now := &clock{now: windowStart.Add(3*time.Hour + 20*time.Minute)}
	signer := newSigner(t, azuriteAccountURL, time.Hour, sharedKeySource(t), now)

	query := sasQuery(t, sign(t, signer, blobURL))

	hourStart := windowStart.Add(3 * time.Hour)
	if got, want := query.Get("st"), hourStart.Add(-5*time.Minute).Format(sas.TimeFormat); got != want {
		t.Fatalf("SAS st = %q, want %q", got, want)
	}
	if got, want := query.Get("se"), hourStart.Add(2*time.Hour).Format(sas.TimeFormat); got != want {
		t.Fatalf("SAS se = %q, want %q", got, want)
	}
}

func TestSignReadURL_LeavesEveryOtherURLUnchanged(t *testing.T) {
	now := &clock{now: windowStart}
	signer := newSigner(t, azuriteAccountURL, 24*time.Hour, sharedKeySource(t), now)

	tests := []struct {
		name string
		url  string
	}{
		{"empty", ""},
		{"another host", "https://cdn.example.org/devstoreaccount1/post-media-dev/avatar.jpg"},
		{"another port", "http://azurite:10001/devstoreaccount1/post-media-dev/avatar.jpg"},
		{"another scheme", "https://azurite:10000/devstoreaccount1/post-media-dev/avatar.jpg"},
		{"another account", "http://azurite:10000/otheraccount/post-media-dev/avatar.jpg"},
		{"another container", "http://azurite:10000/devstoreaccount1/profile-pictures/avatar.jpg"},
		{"a container whose name starts like it", "http://azurite:10000/devstoreaccount1/post-media-dev-old/avatar.jpg"},
		{"the container itself", "http://azurite:10000/devstoreaccount1/post-media-dev/"},
		{"already holds a query", blobURL + "?sv=2025-01-05&sig=abc"},
		{"has a fragment", blobURL + "#preview"},
		{"malformed", "http://azurite:10000/%zz"},
		{"not a url", "avatar.jpg"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := sign(t, signer, tt.url); got != tt.url {
				t.Fatalf("SignReadURL(%q) = %q, want it unchanged", tt.url, got)
			}
		})
	}
}

func TestNew_RejectsOptionsThatMakeNoSense(t *testing.T) {
	source := sharedKeySource(t)
	valid := mediasas.Options{AccountURL: azuriteAccountURL, Container: mediaContainer, Window: 24 * time.Hour, Source: source}

	tests := []struct {
		name   string
		change func(*mediasas.Options)
	}{
		{"no account url", func(o *mediasas.Options) { o.AccountURL = "" }},
		{"an account url that is not http(s)", func(o *mediasas.Options) { o.AccountURL = "ftp://azurite/devstoreaccount1" }},
		{"an account url with a container", func(o *mediasas.Options) { o.AccountURL = azuriteAccountURL + "/" + mediaContainer }},
		{"an invalid container", func(o *mediasas.Options) { o.Container = "Post_Media" }},
		{"a window below the minimum", func(o *mediasas.Options) { o.Window = time.Second }},
		{"a window above the maximum", func(o *mediasas.Options) { o.Window = 80 * time.Hour }},
		{"no credential source", func(o *mediasas.Options) { o.Source = nil }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			options := valid
			tt.change(&options)
			if _, err := mediasas.New(options); err == nil {
				t.Fatalf("New() with %s error = nil, want an error", tt.name)
			}
		})
	}
}

func TestParseAccountURL_FindsTheAccountInEitherStyle(t *testing.T) {
	tests := []struct {
		raw  string
		want mediasas.AccountURL
	}{
		{"http://azurite:10000/devstoreaccount1/", mediasas.AccountURL{Scheme: "http", Host: "azurite:10000", BasePath: "/devstoreaccount1", AccountName: "devstoreaccount1"}},
		{"https://gazemedia.blob.core.windows.net", mediasas.AccountURL{Scheme: "https", Host: "gazemedia.blob.core.windows.net", AccountName: "gazemedia"}},
	}
	for _, tt := range tests {
		t.Run(tt.raw, func(t *testing.T) {
			got, err := mediasas.ParseAccountURL(tt.raw)
			if err != nil {
				t.Fatalf("ParseAccountURL() error = %v", err)
			}
			if got != tt.want {
				t.Fatalf("ParseAccountURL() = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// fakeFetcher hands out a shared-key credential as if it were a user delegation key,
// recording the validity of each fetch.
type fakeFetcher struct {
	t       *testing.T
	mu      sync.Mutex
	fetches []mediasas.Validity
	err     error
}

func (f *fakeFetcher) Fetch(ctx context.Context, start, expiry time.Time) (mediasas.Credential, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fetches = append(f.fetches, mediasas.Validity{Start: start, Expiry: expiry})
	if f.err != nil {
		return nil, f.err
	}
	return sharedKeySource(f.t).Credential(ctx, mediasas.Validity{Start: start, Expiry: expiry})
}

func (f *fakeFetcher) setErr(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.err = err
}

func (f *fakeFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.fetches)
}

func newDelegationSigner(t *testing.T, fetcher *fakeFetcher, now *clock) *mediasas.Signer {
	t.Helper()

	source, err := mediasas.NewUserDelegationSource(fetcher, now.Now, discardLogger())
	if err != nil {
		t.Fatalf("NewUserDelegationSource() error = %v", err)
	}
	return newSigner(t, azuriteAccountURL, 24*time.Hour, source, now)
}

func TestUserDelegationSource_FetchesOneKeyPerWindowValidAsLongAsItsSAS(t *testing.T) {
	now := &clock{now: windowStart.Add(time.Hour)}
	fetcher := &fakeFetcher{t: t}
	signer := newDelegationSigner(t, fetcher, now)

	for range 50 {
		sign(t, signer, blobURL)
	}
	if got := fetcher.fetchCount(); got != 1 {
		t.Fatalf("fetches after 50 signatures in one window = %d, want 1: signing never calls out per URL", got)
	}
	want := mediasas.Validity{Start: windowStart.Add(-5 * time.Minute), Expiry: windowStart.Add(48 * time.Hour)}
	if fetcher.fetches[0] != want {
		t.Fatalf("key validity = %+v, want the SAS validity %+v", fetcher.fetches[0], want)
	}

	now.Set(windowStart.Add(25 * time.Hour))
	sign(t, signer, blobURL)
	if got := fetcher.fetchCount(); got != 2 {
		t.Fatalf("fetches after the next window started = %d, want a new key", got)
	}
}

func TestUserDelegationSource_SignsWithThePreviousKeyWhileAFetchFails(t *testing.T) {
	now := &clock{now: windowStart.Add(time.Hour)}
	fetcher := &fakeFetcher{t: t}
	signer := newDelegationSigner(t, fetcher, now)
	sign(t, signer, blobURL)

	fetcher.setErr(errors.New("entra id unavailable"))
	now.Set(windowStart.Add(24*time.Hour + time.Minute))
	query := sasQuery(t, sign(t, signer, blobURL))

	if got, want := query.Get("se"), windowStart.Add(48*time.Hour).Format(sas.TimeFormat); got != want {
		t.Fatalf("SAS se = %q, want the previous window's %q, still valid for this whole window", got, want)
	}
}

func TestUserDelegationSource_RemembersAFailedFetchBeforeTryingAgain(t *testing.T) {
	now := &clock{now: windowStart.Add(time.Hour)}
	fetcher := &fakeFetcher{t: t, err: errors.New("entra id unavailable")}
	signer := newDelegationSigner(t, fetcher, now)

	for range 10 {
		if _, err := signer.SignReadURL(context.Background(), blobURL); err == nil {
			t.Fatal("SignReadURL() error = nil, want an error while no key can be fetched")
		}
	}
	if got := fetcher.fetchCount(); got != 1 {
		t.Fatalf("fetches during an outage = %d, want 1 until the retry interval passes", got)
	}

	fetcher.setErr(nil)
	now.Set(now.Now().Add(mediasas.FetchRetryInterval))
	sign(t, signer, blobURL)
	if got := fetcher.fetchCount(); got != 2 {
		t.Fatalf("fetches after the retry interval = %d, want 2", got)
	}
}
