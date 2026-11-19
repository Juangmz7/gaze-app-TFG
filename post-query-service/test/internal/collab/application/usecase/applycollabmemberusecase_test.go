package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Juangmz7/gaze-app-TFG/post-query-service/internal/collab/application/usecase"
)

type memberKey struct{ collabID, userID uuid.UUID }

type storedMember struct {
	status      string
	role        string
	requestedAt time.Time
	updatedAt   time.Time
	version     int64
}

// fakeMemberStore is collab_members in memory, with the version gate of
// MemberRepository.UpdateMember.
type fakeMemberStore struct {
	rows      map[memberKey]storedMember
	inserts   int
	updates   int
	findErr   error
	insertErr error
	updateErr error
	expected  []int64
}

func newFakeMemberStore() *fakeMemberStore {
	return &fakeMemberStore{rows: map[memberKey]storedMember{}}
}

func (f *fakeMemberStore) FindMember(_ context.Context, collabID, userID uuid.UUID) (usecase.CollabMemberState, bool, error) {
	if f.findErr != nil {
		return usecase.CollabMemberState{}, false, f.findErr
	}
	row, found := f.rows[memberKey{collabID, userID}]
	return usecase.CollabMemberState{Status: row.status, UpdatedAt: row.updatedAt, Version: row.version}, found, nil
}

func (f *fakeMemberStore) InsertMember(_ context.Context, input usecase.ApplyCollabMemberInput) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	f.inserts++
	f.rows[memberKey{input.CollabID, input.UserID}] = storedMember{
		status: input.Status, role: input.Role, requestedAt: input.RequestedAt, updatedAt: input.OccurredAt, version: 1,
	}
	return nil
}

func (f *fakeMemberStore) UpdateMember(_ context.Context, input usecase.ApplyCollabMemberInput, expectedVersion int64) error {
	f.expected = append(f.expected, expectedVersion)
	if f.updateErr != nil {
		return f.updateErr
	}
	key := memberKey{input.CollabID, input.UserID}
	row := f.rows[key]
	if row.version != expectedVersion {
		return errors.New("version conflict")
	}
	f.updates++
	row.status, row.role, row.updatedAt, row.version = input.Status, input.Role, input.OccurredAt, row.version+1
	f.rows[key] = row
	return nil
}

var memberBase = time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)

func memberEvent(collabID, userID uuid.UUID, status string, occurredAfter time.Duration) usecase.ApplyCollabMemberInput {
	return usecase.ApplyCollabMemberInput{
		CollabID:    collabID,
		UserID:      userID,
		Status:      status,
		Role:        usecase.CollabMemberRoleMember,
		RequestedAt: memberBase,
		OccurredAt:  memberBase.Add(occurredAfter),
	}
}

func TestApplyCollabMemberUsecase_Execute_AppliesTransitionsOnlyForwardInTime(t *testing.T) {
	collabID, userID := uuid.New(), uuid.New()

	cases := map[string]struct {
		events     []usecase.ApplyCollabMemberInput
		wantStatus string
		results    []usecase.ApplyCollabMemberResult
	}{
		"request then decline": {
			events: []usecase.ApplyCollabMemberInput{
				memberEvent(collabID, userID, usecase.CollabMemberStatusPending, 0),
				memberEvent(collabID, userID, usecase.CollabMemberStatusRejected, time.Minute),
			},
			wantStatus: usecase.CollabMemberStatusRejected,
			results:    []usecase.ApplyCollabMemberResult{usecase.CollabMemberInserted, usecase.CollabMemberUpdated},
		},
		"accept processed before its request": {
			events: []usecase.ApplyCollabMemberInput{
				memberEvent(collabID, userID, usecase.CollabMemberStatusAccepted, time.Minute),
				memberEvent(collabID, userID, usecase.CollabMemberStatusPending, 0),
			},
			wantStatus: usecase.CollabMemberStatusAccepted,
			results:    []usecase.ApplyCollabMemberResult{usecase.CollabMemberInserted, usecase.CollabMemberStale},
		},
		"accept, then ban, with the accept redelivered late": {
			events: []usecase.ApplyCollabMemberInput{
				memberEvent(collabID, userID, usecase.CollabMemberStatusPending, 0),
				memberEvent(collabID, userID, usecase.CollabMemberStatusBanned, 2*time.Minute),
				memberEvent(collabID, userID, usecase.CollabMemberStatusAccepted, time.Minute),
			},
			wantStatus: usecase.CollabMemberStatusBanned,
			results:    []usecase.ApplyCollabMemberResult{usecase.CollabMemberInserted, usecase.CollabMemberUpdated, usecase.CollabMemberStale},
		},
		"the same time twice is not newer": {
			events: []usecase.ApplyCollabMemberInput{
				memberEvent(collabID, userID, usecase.CollabMemberStatusPending, time.Minute),
				memberEvent(collabID, userID, usecase.CollabMemberStatusDeleted, time.Minute),
			},
			wantStatus: usecase.CollabMemberStatusPending,
			results:    []usecase.ApplyCollabMemberResult{usecase.CollabMemberInserted, usecase.CollabMemberStale},
		},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeMemberStore()
			uc := usecase.NewApplyCollabMember(store)
			for index, event := range tc.events {
				result, err := uc.Execute(context.Background(), event)
				if err != nil {
					t.Fatalf("Execute(%d) error = %v", index, err)
				}
				if result != tc.results[index] {
					t.Fatalf("Execute(%d) result = %s, want %s", index, result, tc.results[index])
				}
			}
			if got := store.rows[memberKey{collabID, userID}].status; got != tc.wantStatus {
				t.Fatalf("status = %s, want %s", got, tc.wantStatus)
			}
		})
	}
}

func TestApplyCollabMemberUsecase_Execute_ComparesAtMillisecondPrecision(t *testing.T) {
	store := newFakeMemberStore()
	uc := usecase.NewApplyCollabMember(store)
	collabID, userID := uuid.New(), uuid.New()
	event := memberEvent(collabID, userID, usecase.CollabMemberStatusPending, 0)
	event.OccurredAt = memberBase.Add(123456 * time.Microsecond)

	if _, err := uc.Execute(context.Background(), event); err != nil {
		t.Fatalf("Execute() error = %v", err)
	}
	stored := store.rows[memberKey{collabID, userID}]
	if !stored.updatedAt.Equal(memberBase.Add(123 * time.Millisecond)) {
		t.Fatalf("updated_at = %v, want the event time truncated to the millisecond MongoDB keeps", stored.updatedAt)
	}

	// The same event again (its microseconds intact) must compare equal, not newer.
	redelivered := event
	redelivered.Status = usecase.CollabMemberStatusAccepted
	result, err := uc.Execute(context.Background(), redelivered)
	if err != nil || result != usecase.CollabMemberStale {
		t.Fatalf("Execute() = %s, %v, want stale for the same millisecond", result, err)
	}
}

func TestApplyCollabMemberUsecase_Execute_UpdatesWithTheVersionItRead(t *testing.T) {
	store := newFakeMemberStore()
	uc := usecase.NewApplyCollabMember(store)
	collabID, userID := uuid.New(), uuid.New()

	for index, status := range []string{usecase.CollabMemberStatusPending, usecase.CollabMemberStatusAccepted, usecase.CollabMemberStatusLeft} {
		if _, err := uc.Execute(context.Background(), memberEvent(collabID, userID, status, time.Duration(index)*time.Minute)); err != nil {
			t.Fatalf("Execute(%s) error = %v", status, err)
		}
	}

	if len(store.expected) != 2 || store.expected[0] != 1 || store.expected[1] != 2 {
		t.Fatalf("expected versions = %v, want [1 2]", store.expected)
	}
	if row := store.rows[memberKey{collabID, userID}]; row.version != 3 || !row.requestedAt.Equal(memberBase) {
		t.Fatalf("row = %+v, want version 3 and requested_at kept", row)
	}
}

func TestApplyCollabMemberUsecase_Execute_RejectsInvalidInputBeforeAnyRead(t *testing.T) {
	valid := memberEvent(uuid.New(), uuid.New(), usecase.CollabMemberStatusPending, 0)
	cases := map[string]func(input *usecase.ApplyCollabMemberInput){
		"no collab id":     func(input *usecase.ApplyCollabMemberInput) { input.CollabID = uuid.Nil },
		"no user id":       func(input *usecase.ApplyCollabMemberInput) { input.UserID = uuid.Nil },
		"unknown status":   func(input *usecase.ApplyCollabMemberInput) { input.Status = "INVITED" },
		"lowercase status": func(input *usecase.ApplyCollabMemberInput) { input.Status = "pending" },
		"unknown role":     func(input *usecase.ApplyCollabMemberInput) { input.Role = "OWNER" },
		"no requested at":  func(input *usecase.ApplyCollabMemberInput) { input.RequestedAt = time.Time{} },
		"no occurred at":   func(input *usecase.ApplyCollabMemberInput) { input.OccurredAt = time.Time{} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			store := newFakeMemberStore()
			store.findErr = errors.New("must not be read")
			input := valid
			mutate(&input)
			if _, err := usecase.NewApplyCollabMember(store).Execute(context.Background(), input); err == nil || errors.Is(err, store.findErr) {
				t.Fatalf("Execute() error = %v, want a validation error", err)
			}
		})
	}
}

func TestApplyCollabMemberUsecase_Execute_PropagatesRepositoryFailures(t *testing.T) {
	failure := errors.New("mongo unavailable")
	collabID, userID := uuid.New(), uuid.New()

	find := newFakeMemberStore()
	find.findErr = failure
	insert := newFakeMemberStore()
	insert.insertErr = failure
	update := newFakeMemberStore()
	update.rows[memberKey{collabID, userID}] = storedMember{status: usecase.CollabMemberStatusPending, updatedAt: memberBase, version: 1}
	update.updateErr = failure

	for name, store := range map[string]*fakeMemberStore{"find": find, "insert": insert, "update": update} {
		_, err := usecase.NewApplyCollabMember(store).Execute(context.Background(), memberEvent(collabID, userID, usecase.CollabMemberStatusAccepted, time.Minute))
		if !errors.Is(err, failure) {
			t.Fatalf("%s: Execute() error = %v, want %v", name, err, failure)
		}
	}
}
