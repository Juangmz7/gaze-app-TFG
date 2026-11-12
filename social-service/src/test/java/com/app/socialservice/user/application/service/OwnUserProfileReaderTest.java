package com.app.socialservice.user.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.application.dto.OwnUserProfileData;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.UserStats;
import com.app.socialservice.user.application.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnUserProfileReaderTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStatsService userStatsService;

    @InjectMocks
    private OwnUserProfileReader ownUserProfileReader;

    @Test
    void shouldCombineTheProfileWithItsStats() {
        var userId = UUID.randomUUID();
        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.of(new OwnUserProfileData(
                "someone", "bio", Map.of("github", "someone"), 0L, "https://a/b.png", false)));
        when(userStatsService.getStats(userId)).thenReturn(new UserStats(4, 5, 6));

        assertThat(ownUserProfileReader.read(userId)).isEqualTo(new OwnUserProfileResponse(
                userId, "someone", "bio", Map.of("github", "someone"), 4, 5, 6, "https://a/b.png"));
    }

    @Test
    void shouldThrowForAMissingUserSoNothingIsCached() {
        var userId = UUID.randomUUID();
        when(userRepository.findOwnProfileById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownUserProfileReader.read(userId)).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(userStatsService);
    }
}
