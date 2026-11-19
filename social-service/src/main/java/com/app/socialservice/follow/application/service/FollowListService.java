package com.app.socialservice.follow.application.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.app.socialservice.follow.application.dto.FollowListEntry;
import com.app.socialservice.follow.application.dto.FollowListItemResponse;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.shared.application.pagination.KeysetPage;
import com.app.socialservice.shared.domain.pagination.KeysetCursor;
import com.app.socialservice.shared.domain.pagination.PageLimit;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserProfileNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The followers and following lists of a user, keyset paginated, newest follow first. Users in a
 * block relation with the requester are left out; a list owner the requester cannot see
 * (unknown, deleted, banned, or blocked either way) answers 404 like GET /profile/{userId}.
 * Not cached: following depends on the requester and the lists change with every follow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowListService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public KeysetPage<FollowListItemResponse> getFollowers(
            UUID requesterUserId,
            UUID listOwnerUserId,
            String cursor,
            String limit
    ) {
        var pageLimit = PageLimit.parseOptional(limit);
        var after = KeysetCursor.parseOptional(cursor).orElse(null);
        ensureListOwnerVisible(requesterUserId, listOwnerUserId);

        var rows = followRepository.findFollowersPage(
                listOwnerUserId, requesterUserId, after, pageLimit.lookaheadSize());
        var page = toResponsePage(requesterUserId, KeysetPage.fromLookahead(rows, pageLimit, FollowListEntry::sortKey));

        log.info("Followers page of user {} requested by user {}: items={}, hasNext={}",
                listOwnerUserId, requesterUserId, page.items().size(), page.nextCursor() != null);
        return page;
    }

    @Transactional(readOnly = true)
    public KeysetPage<FollowListItemResponse> getFollowing(
            UUID requesterUserId,
            UUID listOwnerUserId,
            String cursor,
            String limit
    ) {
        var pageLimit = PageLimit.parseOptional(limit);
        var after = KeysetCursor.parseOptional(cursor).orElse(null);
        ensureListOwnerVisible(requesterUserId, listOwnerUserId);

        var rows = followRepository.findFollowingPage(
                listOwnerUserId, requesterUserId, after, pageLimit.lookaheadSize());
        var page = toResponsePage(requesterUserId, KeysetPage.fromLookahead(rows, pageLimit, FollowListEntry::sortKey));

        log.info("Following page of user {} requested by user {}: items={}, hasNext={}",
                listOwnerUserId, requesterUserId, page.items().size(), page.nextCursor() != null);
        return page;
    }

    private void ensureListOwnerVisible(UUID requesterUserId, UUID listOwnerUserId) {
        var relationship = userRepository.findProfileRelationship(requesterUserId, listOwnerUserId);
        if (!relationship.targetVisible() || relationship.blocked()) {
            log.info("Follow lists of user {} hidden from user {}: visible={}, blocked={}",
                    listOwnerUserId, requesterUserId, relationship.targetVisible(), relationship.blocked());
            throw new UserProfileNotFoundException(listOwnerUserId);
        }
    }

    private KeysetPage<FollowListItemResponse> toResponsePage(UUID requesterUserId, KeysetPage<FollowListEntry> page) {
        var followedByRequester = findFollowedByRequester(requesterUserId, page.items());
        return page.map(entry -> new FollowListItemResponse(
                entry.userId(),
                entry.username(),
                entry.profilePic(),
                followedByRequester.contains(entry.userId())
        ));
    }

    private Set<UUID> findFollowedByRequester(UUID requesterUserId, List<FollowListEntry> entries) {
        if (entries.isEmpty()) {
            return Set.of();
        }
        var listedUserIds = entries.stream()
                .map(FollowListEntry::userId)
                .toList();
        return followRepository.findActivelyFollowedAmong(requesterUserId, listedUserIds);
    }
}
