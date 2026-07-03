package com.app.socialservice.user.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.follow.application.dto.RecommendedFollowCandidate;
import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import com.app.socialservice.user.application.dto.RecommendedUserDetails;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendedUserServiceTest {

    @Mock
    private BlockRepository blockRepository;

    @Mock
    private FollowGraphRepository followGraphRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RecommendedUserService recommendedUserService;

    @Test
    void shouldThrowWhenRequesterUserIdIsNull() {
        assertThatThrownBy(() -> recommendedUserService.getRecommendedUsers(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requesterUserId must not be null");

        verifyNoInteractions(blockRepository, followGraphRepository, userRepository);
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenRequesterDoesNotExist() {
        var requesterUserId = UUID.randomUUID();
        when(userRepository.findById(requesterUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recommendedUserService.getRecommendedUsers(requesterUserId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + requesterUserId);

        verify(userRepository).findById(requesterUserId);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(blockRepository, followGraphRepository);
    }

    @Test
    void shouldReturnRecommendedUsersExcludingBlockedAndBanned() {
        var requesterUserId = UUID.randomUUID();
        var blockedUserId = UUID.randomUUID();
        var allowedUserId = UUID.randomUUID();
        var bannedUserId = UUID.randomUUID();

        when(userRepository.findById(requesterUserId)).thenReturn(Optional.of(buildUser(requesterUserId)));
        when(blockRepository.findBlockedUserIds(requesterUserId)).thenReturn(Set.of(blockedUserId));
        when(followGraphRepository.findRecommendedUsers(
                requesterUserId,
                Set.of(blockedUserId),
                RecommendedUserService.GRAPH_CANDIDATE_LIMIT
        )).thenReturn(List.of(
                new RecommendedFollowCandidate(allowedUserId, 4),
                new RecommendedFollowCandidate(bannedUserId, 3)
        ));
        when(userRepository.findRecommendedUsersByIds(anyList(), eq(requesterUserId)))
                .thenReturn(List.of(
                        new RecommendedUserDetails(
                                allowedUserId,
                                "allowed-user",
                                "Allowed description",
                                "https://cdn.example/allowed.png",
                                true,
                                Instant.parse("2026-06-01T00:00:00Z")
                        )
                ));

        var responses = recommendedUserService.getRecommendedUsers(requesterUserId);

        assertThat(responses).singleElement()
                .extracting("username", "description", "profilePic", "followsYou")
                .containsExactly("allowed-user", "Allowed description", "https://cdn.example/allowed.png", true);

        var blockedIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(followGraphRepository).findRecommendedUsers(
                eq(requesterUserId),
                blockedIdsCaptor.capture(),
                eq(RecommendedUserService.GRAPH_CANDIDATE_LIMIT)
        );
        assertThat(blockedIdsCaptor.getValue()).containsExactly(blockedUserId);
    }

    @Test
    void shouldOrderRecommendedUsersByCommonConnections() {
        var requesterUserId = UUID.randomUUID();
        var higherRankUserId = UUID.randomUUID();
        var newerTieUserId = UUID.randomUUID();
        var olderTieUserId = UUID.randomUUID();

        when(userRepository.findById(requesterUserId)).thenReturn(Optional.of(buildUser(requesterUserId)));
        when(blockRepository.findBlockedUserIds(requesterUserId)).thenReturn(Set.of());
        when(followGraphRepository.findRecommendedUsers(
                requesterUserId,
                Set.of(),
                RecommendedUserService.GRAPH_CANDIDATE_LIMIT
        )).thenReturn(List.of(
                new RecommendedFollowCandidate(olderTieUserId, 2),
                new RecommendedFollowCandidate(higherRankUserId, 5),
                new RecommendedFollowCandidate(newerTieUserId, 2)
        ));
        when(userRepository.findRecommendedUsersByIds(anyList(), eq(requesterUserId))).thenReturn(List.of(
                new RecommendedUserDetails(olderTieUserId, "older-tie", null, null, false,
                        Instant.parse("2026-01-01T00:00:00Z")),
                new RecommendedUserDetails(newerTieUserId, "newer-tie", null, null, false,
                        Instant.parse("2026-05-01T00:00:00Z")),
                new RecommendedUserDetails(higherRankUserId, "higher-rank", null, null, false,
                        Instant.parse("2025-12-01T00:00:00Z"))
        ));

        var responses = recommendedUserService.getRecommendedUsers(requesterUserId);

        assertThat(responses)
                .extracting("username")
                .containsExactly("higher-rank", "newer-tie", "older-tie");
    }

    @Test
    void shouldLimitTo20Users() {
        var requesterUserId = UUID.randomUUID();
        var candidates = IntStream.range(0, 25)
                .mapToObj(index -> new RecommendedFollowCandidate(UUID.randomUUID(), 25L - index))
                .toList();
        var profiles = candidates.stream()
                .map(candidate -> new RecommendedUserDetails(
                        candidate.userId(),
                        "user-" + candidate.commonConnections(),
                        null,
                        null,
                        false,
                        Instant.parse("2026-01-01T00:00:00Z")
                ))
                .toList();

        when(userRepository.findById(requesterUserId)).thenReturn(Optional.of(buildUser(requesterUserId)));
        when(blockRepository.findBlockedUserIds(requesterUserId)).thenReturn(Set.of());
        when(followGraphRepository.findRecommendedUsers(
                requesterUserId,
                Set.of(),
                RecommendedUserService.GRAPH_CANDIDATE_LIMIT
        )).thenReturn(candidates);
        when(userRepository.findRecommendedUsersByIds(anyList(), eq(requesterUserId))).thenReturn(profiles);

        var responses = recommendedUserService.getRecommendedUsers(requesterUserId);

        assertThat(responses).hasSize(20);
        assertThat(responses.getFirst().username()).isEqualTo("user-25");
        assertThat(responses.getLast().username()).isEqualTo("user-6");
    }

    @Test
    void shouldCorrectlySetFollowsYouFlagForEachRecommendedUser() {
        var requesterUserId = UUID.randomUUID();
        var firstUserId = UUID.randomUUID();
        var secondUserId = UUID.randomUUID();

        when(userRepository.findById(requesterUserId)).thenReturn(Optional.of(buildUser(requesterUserId)));
        when(blockRepository.findBlockedUserIds(requesterUserId)).thenReturn(Set.of());
        when(followGraphRepository.findRecommendedUsers(
                requesterUserId,
                Set.of(),
                RecommendedUserService.GRAPH_CANDIDATE_LIMIT
        )).thenReturn(List.of(
                new RecommendedFollowCandidate(firstUserId, 3),
                new RecommendedFollowCandidate(secondUserId, 2)
        ));
        when(userRepository.findRecommendedUsersByIds(anyList(), eq(requesterUserId)))
                .thenReturn(List.of(
                        new RecommendedUserDetails(firstUserId, "first-user", null, null, true,
                                Instant.parse("2026-04-01T00:00:00Z")),
                        new RecommendedUserDetails(secondUserId, "second-user", null, null, false,
                                Instant.parse("2026-03-01T00:00:00Z"))
                ));

        var responses = recommendedUserService.getRecommendedUsers(requesterUserId);

        assertThat(responses)
                .extracting("username", "followsYou")
                .containsExactly(
                        Tuple.tuple("first-user", true),
                        Tuple.tuple("second-user", false)
                );
    }

    private User buildUser(UUID userId) {
        return new User(new UserId(userId), new Username("requester"), new Email("requester@example.com"));
    }
}
