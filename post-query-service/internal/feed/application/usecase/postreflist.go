package usecase

import (
	"context"
	"time"
)

// This file reads the lists whose rows point at posts and are kept per user: the
// reposts grid (post_shares, task 49), the liked tab (post_likes) and the history tab
// (view_history, task 50). Each row has its own time (shared, liked, last viewed),
// which orders the list and keys its cursor; the post is read by id and may be gone or
// hidden from the caller, so a page reads on until it is full.

// RefListMaxReads bounds the row reads of one page of a ref list. A row outlives its
// post (a deleted post leaves its shares, likes and views), so a read can come back
// with fewer visible posts than asked for; the page reads on after the last row until
// it is full, at most this many times, then answers a short page whose cursor resumes
// after the last row read.
const RefListMaxReads = 3

// PostRef is one row of a ref list: which post, and the row's own time (when it was
// shared, liked or last viewed).
type PostRef struct {
	PostID string
	At     time.Time
}

// RefListReader reads what a ref list's posts are built from, besides its rows.
// Implemented by feed/infrastructure/mongo.HydrationRepository.
type RefListReader interface {
	gridReader
	FindPosts(ctx context.Context, postIDs []string) ([]FeedPost, error)
}

// refRows reads up to query.Limit rows of a ref list strictly after query.After, in
// the list's order: (time, post id) descending.
type refRows func(ctx context.Context, query UserGridQuery) ([]PostRef, error)

// refPost is a visible post of a ref list and the time of its row.
type refPost struct {
	post FeedPost
	at   time.Time
}

// readRefPage reads one page of the ref list of userID: up to limit posts the caller
// may see, in row order, and the cursor of the next page (nil on the last). blocked
// is the caller's block set. Each read is three queries (rows, then their posts and
// creators with one $in each, never one by one).
func readRefPage(ctx context.Context, reader RefListReader, rows refRows, userID string, after *GridCursor, limit int, blocked map[string]struct{}, drops gridDrops) ([]refPost, *string, error) {
	posts := make([]refPost, 0, limit)
	var next *string
	for read := 1; read <= RefListMaxReads; read++ {
		missing := limit - len(posts)
		refs, err := rows(ctx, UserGridQuery{UserID: userID, After: after, Limit: missing + 1})
		if err != nil {
			return nil, nil, err
		}
		more := len(refs) > missing
		consumed := refs[:min(missing, len(refs))]

		visible, err := visibleRefPosts(ctx, reader, consumed, blocked, drops)
		if err != nil {
			return nil, nil, err
		}
		posts = append(posts, visible...)
		if len(consumed) > 0 {
			last := consumed[len(consumed)-1]
			after = &GridCursor{CreatedAt: last.At, PostID: last.PostID}
		}
		if !more {
			break
		}
		if len(posts) == limit || read == RefListMaxReads {
			next = encodeGridCursorAt(after.CreatedAt, after.PostID)
			break
		}
	}

	return posts, next, nil
}

// visibleRefPosts reads the posts of refs and their creators with one $in each, and
// returns, in row order, those the caller may see: the post exists, its creator is
// projected and has no block with the caller. These are exactly the posts GetPost
// serves, so opening an item never answers 404.
func visibleRefPosts(ctx context.Context, reader RefListReader, refs []PostRef, blocked map[string]struct{}, drops gridDrops) ([]refPost, error) {
	if len(refs) == 0 {
		return nil, nil
	}

	postIDs := make([]string, 0, len(refs))
	for _, ref := range refs {
		postIDs = append(postIDs, ref.PostID)
	}
	found, err := reader.FindPosts(ctx, postIDs)
	if err != nil {
		return nil, err
	}
	posts := make(map[string]FeedPost, len(found))
	creatorIDs := make([]string, 0, len(found))
	for _, post := range found {
		posts[post.PostID] = post
		creatorIDs = append(creatorIDs, post.CreatorID)
	}
	creators, err := reader.FindCreators(ctx, creatorIDs)
	if err != nil {
		return nil, err
	}
	projected := make(map[string]struct{}, len(creators))
	for _, creator := range creators {
		projected[creator.UserID] = struct{}{}
	}

	visible := make([]refPost, 0, len(refs))
	for _, ref := range refs {
		post, found := posts[ref.PostID]
		if !found {
			drops[gridDropReasonPostMissing]++
			continue
		}
		if _, isBlocked := blocked[post.CreatorID]; isBlocked {
			drops[gridDropReasonCreatorBlocked]++
			continue
		}
		if _, isProjected := projected[post.CreatorID]; !isProjected {
			drops[gridDropReasonCreatorMissing]++
			continue
		}
		visible = append(visible, refPost{post: post, at: ref.At})
	}

	return visible, nil
}
