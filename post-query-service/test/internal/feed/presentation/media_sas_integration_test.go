package presentation_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/feed/application/usecase"
	postusecase "github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/post/application/usecase"
	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/shared/infrastructure/mediasas"
)

// Azurite's well-known development account, which is public and no secret.
const (
	azuriteImage       = "mcr.microsoft.com/azure-storage/azurite:3.35.0"
	azuriteAccountName = "devstoreaccount1"
	azuriteAccountKey  = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
	mediaContainer     = "post-media-test"
)

// azurite is a private media container on a real Azurite, like post-command-service's.
type azurite struct {
	accountURL string
	client     *azblob.Client
}

func startAzurite(t *testing.T, ctx context.Context) *azurite {
	t.Helper()

	container, err := testcontainers.Run(ctx, azuriteImage,
		testcontainers.WithExposedPorts("10000/tcp"),
		testcontainers.WithCmd("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck"),
		testcontainers.WithWaitStrategy(wait.ForListeningPort("10000/tcp")),
	)
	testcontainers.CleanupContainer(t, container)
	if err != nil {
		t.Fatalf("start azurite: %v", err)
	}
	endpoint, err := container.PortEndpoint(ctx, "10000/tcp", "http")
	if err != nil {
		t.Fatalf("azurite endpoint: %v", err)
	}

	accountURL := endpoint + "/" + azuriteAccountName
	credential, err := azblob.NewSharedKeyCredential(azuriteAccountName, azuriteAccountKey)
	if err != nil {
		t.Fatalf("NewSharedKeyCredential() error = %v", err)
	}
	client, err := azblob.NewClientWithSharedKeyCredential(accountURL, credential, nil)
	if err != nil {
		t.Fatalf("NewClientWithSharedKeyCredential() error = %v", err)
	}
	// No public access level: the container is private, as in every environment.
	if _, err := client.CreateContainer(ctx, mediaContainer, nil); err != nil {
		t.Fatalf("CreateContainer() error = %v", err)
	}

	return &azurite{accountURL: accountURL, client: client}
}

// upload stores content under a random blob name and returns the bare blob URL, the
// shape post-command-service stores.
func (a *azurite) upload(t *testing.T, ctx context.Context, content []byte) string {
	t.Helper()

	blobName := uuid.NewString()
	if _, err := a.client.UploadBuffer(ctx, mediaContainer, blobName, content, nil); err != nil {
		t.Fatalf("UploadBuffer() error = %v", err)
	}
	return a.accountURL + "/" + mediaContainer + "/" + blobName
}

type signedCard struct {
	PostID  string `json:"postId"`
	Creator struct {
		PictureURL *string `json:"pictureUrl"`
	} `json:"creator"`
	Media []struct {
		URL          string  `json:"url"`
		ThumbnailURL *string `json:"thumbnailUrl"`
	} `json:"media"`
	CoverThumbnailURL *string `json:"coverThumbnailUrl"`
}

type signedPage struct {
	Items      []signedCard `json:"items"`
	LastSeenID *string      `json:"lastSeenId"`
}

func (a *feedApp) claimSigned(t *testing.T, query string) signedPage {
	t.Helper()

	recorder := serve(a.mux, http.MethodGet, "/api/feed"+query, "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET /api/feed%s status = %d: %s", query, recorder.Code, recorder.Body)
	}
	var got signedPage
	if err := json.Unmarshal(recorder.Body.Bytes(), &got); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return got
}

func (a *feedApp) insertPost(t *testing.T, ctx context.Context, media []postusecase.PostMedia) uuid.UUID {
	t.Helper()

	postID := uuid.New()
	createdAt := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	if err := a.posts.Insert(ctx, postusecase.CreatePostInput{
		PostID: postID, UserID: a.creator, PostType: "BASIC", Description: "Harbour at dusk",
		Media: media, CreatedAt: createdAt, UpdatedAt: createdAt,
	}); err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	return postID
}

func download(t *testing.T, ctx context.Context, rawURL string) (int, []byte) {
	t.Helper()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		t.Fatalf("NewRequest(%q) error = %v", rawURL, err)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("GET %s error = %v", rawURL, err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read %s: %v", rawURL, err)
	}
	return response.StatusCode, body
}

func assertReadOnlySAS(t *testing.T, field, signed string) {
	t.Helper()

	parsed, err := url.Parse(signed)
	if err != nil {
		t.Fatalf("%s = %q is not a url: %v", field, signed, err)
	}
	query := parsed.Query()
	if query.Get("sig") == "" || query.Get("sp") != "r" || query.Get("sr") != "b" {
		t.Fatalf("%s = %s, want a read-only blob SAS (sig, sp=r, sr=b)", field, signed)
	}
}

func TestFeedClaim_ServesMediaURLsThatDownloadFromAzurite(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	storage := startAzurite(t, ctx)
	// One instant for the whole test, so both claims fall in one window whatever the
	// wall clock does meanwhile. The SAS it yields is valid now.
	signedAt := time.Now()
	signer, err := mediasas.New(mediasas.Options{
		AccountURL: storage.accountURL,
		Container:  mediaContainer,
		Window:     24 * time.Hour,
		Source:     mustSharedKeySource(t),
		Now:        func() time.Time { return signedAt },
	})
	if err != nil {
		t.Fatalf("mediasas.New() error = %v", err)
	}
	app := newFeedAppSigning(t, ctx, signer)

	blobs := map[string][]byte{
		"image":     []byte("jpeg bytes of the harbour"),
		"video":     []byte("mp4 bytes of the harbour"),
		"thumbnail": []byte("jpeg bytes of the first frame"),
		"picture":   []byte("jpeg bytes of the creator"),
	}
	urls := map[string]string{}
	for name, content := range blobs {
		urls[name] = storage.upload(t, ctx, content)
	}
	if _, err := app.db.Collection("users").UpdateOne(ctx, bson.D{{Key: "user_id", Value: app.creator.String()}},
		bson.D{{Key: "$set", Value: bson.D{{Key: "picture_url", Value: urls["picture"]}}}}); err != nil {
		t.Fatalf("set the creator's picture: %v", err)
	}

	duration := int64(12000)
	thumbnail := urls["thumbnail"]
	const foreignURL = "https://cdn.example.org/media/harbour.jpg"
	withMedia := app.insertPost(t, ctx, []postusecase.PostMedia{
		{ID: uuid.New(), URL: urls["image"], MediaType: "IMAGE", Order: 1},
		{ID: uuid.New(), URL: urls["video"], ThumbnailURL: &thumbnail, MediaType: "VIDEO", Duration: &duration, Order: 2},
	})
	elsewhere := app.insertPost(t, ctx, []postusecase.PostMedia{{ID: uuid.New(), URL: foreignURL, MediaType: "IMAGE", Order: 1}})
	repeated := app.insertPost(t, ctx, []postusecase.PostMedia{{ID: uuid.New(), URL: urls["image"], MediaType: "IMAGE", Order: 1}})
	if _, err := app.build.Execute(ctx, usecase.BuildFeedUnitsInput{UserID: app.viewerID, PostIDs: []uuid.UUID{withMedia, elsewhere, repeated}}); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}

	first := app.claimSigned(t, "?limit=2")
	if len(first.Items) != 2 || first.Items[0].PostID != withMedia.String() || first.Items[1].PostID != elsewhere.String() {
		t.Fatalf("first page = %+v", first)
	}

	t.Run("every media URL in the container has a read SAS and downloads", func(t *testing.T) {
		card := first.Items[0]
		fields := map[string]struct {
			signed *string
			blob   string
		}{
			"media[0].url":          {&card.Media[0].URL, "image"},
			"media[1].url":          {&card.Media[1].URL, "video"},
			"media[1].thumbnailUrl": {card.Media[1].ThumbnailURL, "thumbnail"},
			"coverThumbnailUrl":     {card.CoverThumbnailURL, "image"},
			"creator.pictureUrl":    {card.Creator.PictureURL, "picture"},
		}
		for field, value := range fields {
			if value.signed == nil {
				t.Fatalf("%s = null, want a signed url", field)
			}
			assertReadOnlySAS(t, field, *value.signed)
			status, body := download(t, ctx, *value.signed)
			if status != http.StatusOK || !bytes.Equal(body, blobs[value.blob]) {
				t.Fatalf("GET %s = %d %q, want 200 with the %s blob", field, status, body, value.blob)
			}
		}
		if card.Media[0].ThumbnailURL != nil {
			t.Fatalf("image thumbnailUrl = %q, want null to stay null", *card.Media[0].ThumbnailURL)
		}
	})

	t.Run("the bare URL does not download: the container is private", func(t *testing.T) {
		if status, _ := download(t, ctx, urls["image"]); status == http.StatusOK {
			t.Fatalf("GET bare blob url = %d, want it refused without a SAS", status)
		}
	})

	t.Run("a URL of another host is returned unchanged", func(t *testing.T) {
		card := first.Items[1]
		if card.Media[0].URL != foreignURL || card.CoverThumbnailURL == nil || *card.CoverThumbnailURL != foreignURL {
			t.Fatalf("foreign media = %q, cover = %v, want %q unchanged", card.Media[0].URL, card.CoverThumbnailURL, foreignURL)
		}
	})

	t.Run("two requests in the same window get the same signed URL for the same blob", func(t *testing.T) {
		second := app.claimSigned(t, "?lastSeenId="+elsewhere.String())
		again := app.claimSigned(t, "?lastSeenId="+elsewhere.String())
		if len(second.Items) != 1 || len(again.Items) != 1 {
			t.Fatalf("pages = %+v, %+v, want the repeated post on both", second, again)
		}
		if second.Items[0].Media[0].URL != again.Items[0].Media[0].URL {
			t.Fatalf("signed urls differ between requests:\n%s\n%s", second.Items[0].Media[0].URL, again.Items[0].Media[0].URL)
		}
		if second.Items[0].Media[0].URL != first.Items[0].Media[0].URL {
			t.Fatalf("the same blob on another post = %s, want %s", second.Items[0].Media[0].URL, first.Items[0].Media[0].URL)
		}
		if *second.Items[0].Creator.PictureURL != *first.Items[0].Creator.PictureURL {
			t.Fatal("the creator picture's signed url changed between requests")
		}
	})

	t.Run("GET /api/content/posts/{postId} signs the same URLs as the feed and they download", func(t *testing.T) {
		recorder := serve(app.mux, http.MethodGet, "/api/content/posts/"+withMedia.String(), "", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("status = %d: %s", recorder.Code, recorder.Body)
		}
		var view signedCard
		if err := json.Unmarshal(recorder.Body.Bytes(), &view); err != nil {
			t.Fatalf("Unmarshal() error = %v", err)
		}
		card := first.Items[0]
		if view.Media[1].URL != card.Media[1].URL || *view.Media[1].ThumbnailURL != *card.Media[1].ThumbnailURL ||
			*view.CoverThumbnailURL != *card.CoverThumbnailURL || *view.Creator.PictureURL != *card.Creator.PictureURL {
			t.Fatalf("post view urls = %+v, want the feed's signed urls %+v", view, card)
		}
		assertReadOnlySAS(t, "media[0].url", view.Media[0].URL)
		if status, body := download(t, ctx, view.Media[0].URL); status != http.StatusOK || !bytes.Equal(body, blobs["image"]) {
			t.Fatalf("GET media[0].url = %d %q, want 200 with the image blob", status, body)
		}
	})

	t.Run("nothing signed is stored", func(t *testing.T) {
		for _, collection := range []string{"posts", "feed_units", "users"} {
			cursor, err := app.db.Collection(collection).Find(ctx, bson.D{})
			if err != nil {
				t.Fatalf("Find(%s) error = %v", collection, err)
			}
			var documents []bson.M
			if err := cursor.All(ctx, &documents); err != nil {
				t.Fatalf("All(%s) error = %v", collection, err)
			}
			for _, document := range documents {
				raw, err := bson.MarshalExtJSON(document, false, false)
				if err != nil {
					t.Fatalf("MarshalExtJSON() error = %v", err)
				}
				if strings.Contains(string(raw), "sig=") {
					t.Fatalf("%s document holds a SAS, want only bare urls stored: %s", collection, raw)
				}
			}
		}
	})
}

func mustSharedKeySource(t *testing.T) *mediasas.SharedKeySource {
	t.Helper()

	source, err := mediasas.NewSharedKeySource(azuriteAccountName, azuriteAccountKey)
	if err != nil {
		t.Fatalf("NewSharedKeySource() error = %v", err)
	}
	return source
}
