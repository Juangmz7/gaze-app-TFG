package com.app.socialservice.follow.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.socialservice.follow.application.dto.FollowListEntry;
import com.app.socialservice.follow.application.dto.FollowListItemResponse;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.shared.domain.exception.InvalidCursorException;
import com.app.socialservice.shared.domain.exception.InvalidPageLimitException;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;
import com.app.socialservice.user.application.dto.ProfileRelationship;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowListServiceTest {

    private static final UUID REQUESTER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NEWEST = Instant.parse("2026-11-19T10:00:00Z");
    private static final ProfileRelationship VISIBLE = new ProfileRelationship(false, false, false, true);

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FollowListService followListService;

    @Test
    void shouldReturnTheFirstFollowersPageWithANextCursorAndTheCallersFollowingFlags() {
        var entries = entries(PageLimit.DEFAULT + 1);
        var followedByRequester = entries.get(1).userId();
        when(userRepository.findProfileRelationship(REQUESTER_ID, OWNER_ID)).thenReturn(VISIBLE);
        when(followRepository.findFollowersPage(OWNER_ID, REQUESTER_ID, null, PageLimit.DEFAULT + 1))
                .thenReturn(entries);
        when(followRepository.findActivelyFollowedAmong(REQUESTER_ID, userIds(entries.subList(0, PageLimit.DEFAULT))))
                .thenReturn(Set.of(followedByRequester));

        var page = followListService.getFollowers(REQUESTER_ID, OWNER_ID, null, null);

        assertThat(page.items()).hasSize(PageLimit.DEFAULT);
        assertThat(page.items()).extracting(FollowListItemResponse::userId)
                .containsExactlyElementsOf(userIds(entries.subList(0, PageLimit.DEFAULT)));
        assertThat(page.items()).filteredOn(FollowListItemResponse::following)
                .extracting(FollowListItemResponse::userId)
                .containsExactly(followedByRequester);
        assertThat(KeysetCursor.decode(page.nextCursor())).isEqualTo(entries.get(PageLimit.DEFAULT - 1).sortKey());
    }

    @Test
    void shouldPassTheDecodedCursorAndTheLookaheadSizeForTheFollowingList() {
        var cursor = new KeysetCursor(NEWEST, UUID.randomUUID());
        var entries = entries(2);
        when(userRepository.findProfileRelationship(REQUESTER_ID, OWNER_ID)).thenReturn(VISIBLE);
        when(followRepository.findFollowingPage(OWNER_ID, REQUESTER_ID, cursor, 6)).thenReturn(entries);
        when(followRepository.findActivelyFollowedAmong(REQUESTER_ID, userIds(entries))).thenReturn(Set.of());

        var page = followListService.getFollowing(REQUESTER_ID, OWNER_ID, cursor.encode(), "5");

        assertThat(page.items()).extracting(FollowListItemResponse::username).containsExactly("user-0", "user-1");
        assertThat(page.items()).noneMatch(FollowListItemResponse::following);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void shouldNotQueryTheFollowingFlagsForAnEmptyPage() {
        when(userRepository.findProfileRelationship(REQUESTER_ID, OWNER_ID)).thenReturn(VISIBLE);
        when(followRepository.findFollowersPage(OWNER_ID, REQUESTER_ID, null, PageLimit.DEFAULT + 1))
                .thenReturn(List.of());

        var page = followListService.getFollowers(REQUESTER_ID, OWNER_ID, null, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(followRepository, never()).findActivelyFollowedAmong(any(), any());
    }

    @Test
    void shouldAnswerNotFoundWhenTheListOwnerIsNotVisible() {
        when(userRepository.findProfileRelationship(REQUESTER_ID, OWNER_ID))
                .thenReturn(new ProfileRelationship(false, false, false, false));

        assertThatThrownBy(() -> followListService.getFollowers(REQUESTER_ID, OWNER_ID, null, null))
                .isInstanceOf(UserProfileNotFoundException.class);
        verifyNoInteractions(followRepository);
    }

    @Test
    void shouldAnswerNotFoundWhenTheListOwnerIsInABlockRelationWithTheRequester() {
        when(userRepository.findProfileRelationship(REQUESTER_ID, OWNER_ID))
                .thenReturn(new ProfileRelationship(false, false, true, true));

        assertThatThrownBy(() -> followListService.getFollowing(REQUESTER_ID, OWNER_ID, null, null))
                .isInstanceOf(UserProfileNotFoundException.class);
        verifyNoInteractions(followRepository);
    }

    @Test
    void shouldListTheRequestersOwnFollowers() {
        when(userRepository.findProfileRelationship(REQUESTER_ID, REQUESTER_ID)).thenReturn(VISIBLE);
        when(followRepository.findFollowersPage(eq(REQUESTER_ID), eq(REQUESTER_ID), any(), anyInt()))
                .thenReturn(List.of());

        var page = followListService.getFollowers(REQUESTER_ID, REQUESTER_ID, null, null);

        assertThat(page.items()).isEmpty();
    }

    @Test
    void shouldRejectAMalformedCursorBeforeAnyQuery() {
        assertThatThrownBy(() -> followListService.getFollowers(REQUESTER_ID, OWNER_ID, "garbage!", null))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(userRepository, followRepository);
    }

    @Test
    void shouldRejectABadLimitBeforeAnyQuery() {
        assertThatThrownBy(() -> followListService.getFollowing(REQUESTER_ID, OWNER_ID, null, "51"))
                .isInstanceOf(InvalidPageLimitException.class);
        verifyNoInteractions(userRepository, followRepository);
    }

    private static List<FollowListEntry> entries(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new FollowListEntry(
                        UUID.randomUUID(),
                        "user-" + i,
                        null,
                        NEWEST.minusSeconds(i)
                ))
                .toList();
    }

    private static List<UUID> userIds(List<FollowListEntry> entries) {
        return entries.stream().map(FollowListEntry::userId).toList();
    }
}
