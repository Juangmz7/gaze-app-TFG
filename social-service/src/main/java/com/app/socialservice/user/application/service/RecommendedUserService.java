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
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
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


    private void assertRequesterExists(UUID requesterUserId) {
        if (userRepository.findById(requesterUserId).isEmpty()) {
            throw new UserNotFoundException(requesterUserId);
        }
    }

    private List<RecommendedFollowCandidate> deduplicateCandidates(List<RecommendedFollowCandidate> graphCandidates) {
        var distinctCandidates = new HashMap<UUID, RecommendedFollowCandidate>();
        for (var candidate : graphCandidates) {
            distinctCandidates.merge(
                    candidate.userId(),
                    candidate,
                    (left, right) -> left.commonConnections() >= right.commonConnections() ? left : right
            );
        }
        return new ArrayList<>(distinctCandidates.values());
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
            profilesById.put(profile.id(), profile);
        }
        return profilesById;
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
