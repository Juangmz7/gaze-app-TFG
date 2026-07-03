package com.app.socialservice.user.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.follow.application.dto.RecommendedFollowCandidate;
import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import com.app.socialservice.user.application.dto.RecommendedUserDetails;
import com.app.socialservice.user.application.dto.RecommendedUserResponse;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendedUserService {

    static final int GRAPH_CANDIDATE_LIMIT = 50;
    static final int RESPONSE_LIMIT = 20;

    private final BlockRepository blockRepository;
    private final FollowGraphRepository followGraphRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<RecommendedUserResponse> getRecommendedUsers(UUID requesterUserId) {
        validateRequesterUserId(requesterUserId);
        assertRequesterExists(requesterUserId);

        var blockedUserIds = blockRepository.findBlockedUserIds(requesterUserId);
        var graphCandidates = followGraphRepository.findRecommendedUsers(
                requesterUserId,
                blockedUserIds,
                GRAPH_CANDIDATE_LIMIT
        );
        if (graphCandidates.isEmpty()) {
            return List.of();
        }

        var rankedCandidates = deduplicateCandidates(graphCandidates);
        var profilesById = loadProfilesById(rankedCandidates, requesterUserId);
        if (profilesById.isEmpty()) {
            return List.of();
        }

        log.info("Retrieving recommended user for user {}", requesterUserId);

        return rankedCandidates.stream()
                .filter(candidate -> profilesById.containsKey(candidate.userId()))
                .sorted(candidateComparator(profilesById))
                .limit(RESPONSE_LIMIT)
                .map(candidate -> toResponse(profilesById.get(candidate.userId())))
                .toList();
    }

    private void validateRequesterUserId(UUID requesterUserId) {
        if (requesterUserId == null) {
            throw new IllegalArgumentException("requesterUserId must not be null");
        }
    }

    private void assertRequesterExists(UUID requesterUserId) {
        if (userRepository.findById(requesterUserId).isEmpty()) {
            throw new UserNotFoundException(requesterUserId);
        }
    }

    private List<RecommendedFollowCandidate> deduplicateCandidates(List<RecommendedFollowCandidate> graphCandidates) {
        var distinctCandidates = new HashMap<UUID, RecommendedFollowCandidate>();
        for (var candidate : graphCandidates) {
            validateGraphCandidate(candidate);
            distinctCandidates.merge(
                    candidate.userId(),
                    candidate,
                    (left, right) -> left.commonConnections() >= right.commonConnections() ? left : right
            );
        }
        return new ArrayList<>(distinctCandidates.values());
    }

    private void validateGraphCandidate(RecommendedFollowCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("graph candidate must not be null");
        }
        if (candidate.userId() == null) {
            throw new IllegalArgumentException("graph candidate userId must not be null");
        }
        if (candidate.commonConnections() < 0) {
            throw new IllegalArgumentException("graph candidate commonConnections must not be negative");
        }
    }

    private HashMap<UUID, RecommendedUserDetails> loadProfilesById(
            List<RecommendedFollowCandidate> rankedCandidates,
            UUID requesterUserId
    ) {
        var candidateIds = rankedCandidates.stream()
                .map(RecommendedFollowCandidate::userId)
                .filter(Objects::nonNull)
                .toList();
        var profiles = userRepository.findRecommendedUsersByIds(candidateIds, requesterUserId);
        var profilesById = new HashMap<UUID, RecommendedUserDetails>();
        for (var profile : profiles) {
            validateProfile(profile);
            profilesById.put(profile.id(), profile);
        }
        return profilesById;
    }

    private void validateProfile(RecommendedUserDetails profile) {
        if (profile == null) {
            throw new IllegalArgumentException("recommended user profile must not be null");
        }
        if (profile.id() == null) {
            throw new IllegalArgumentException("recommended user profile id must not be null");
        }
        if (profile.username() == null) {
            throw new IllegalArgumentException("recommended user profile username must not be null");
        }
    }

    private Comparator<RecommendedFollowCandidate> candidateComparator(
            HashMap<UUID, RecommendedUserDetails> profilesById
    ) {
        return Comparator.comparingLong(RecommendedFollowCandidate::commonConnections)
                .reversed()
                .thenComparing(candidate -> profilesById.get(candidate.userId()).createdAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(candidate -> candidate.userId().toString());
    }

    private RecommendedUserResponse toResponse(RecommendedUserDetails profile) {
        return new RecommendedUserResponse(
                profile.id(),
                profile.username(),
                profile.description(),
                profile.profilePic(),
                profile.followsYou()
        );
    }
}
