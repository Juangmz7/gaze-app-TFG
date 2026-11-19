package com.app.socialservice.user.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.application.commands.RequestProfilePictureUploadCommand;
import com.app.socialservice.user.application.dto.ProfilePictureUploadUrlResponse;
import com.app.socialservice.user.application.port.ProfilePictureStorage;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureSizeException;
import com.app.socialservice.user.domain.exception.UnsupportedProfilePictureContentTypeException;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;
import com.app.socialservice.user.testutil.UserMother;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilePictureUploadServiceTest {

    private static final UUID USER_ID = UUID.fromString("0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11");
    private static final String JPEG = "image/jpeg";
    private static final BigDecimal VALID_SIZE = BigDecimal.valueOf(204_800);

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfilePictureStorage profilePictureStorage;

    @InjectMocks
    private ProfilePictureUploadService profilePictureUploadService;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "image/gif", "IMAGE/JPEG", "image/jpg", "text/plain", "image/png; charset=x"})
    void shouldRejectUnsupportedContentTypes(String contentType) {
        var command = new RequestProfilePictureUploadCommand(USER_ID, contentType, VALID_SIZE);

        assertThatThrownBy(() -> profilePictureUploadService.requestUploadUrl(command))
                .isInstanceOf(UnsupportedProfilePictureContentTypeException.class);
        verifyNoInteractions(userRepository, profilePictureStorage);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "5242881", "1.5", "99999999999999999999999"})
    void shouldRejectSizesOutsideOneByteToFiveMebibytes(String sizeBytes) {
        var size = sizeBytes == null ? null : new BigDecimal(sizeBytes);
        var command = new RequestProfilePictureUploadCommand(USER_ID, JPEG, size);

        assertThatThrownBy(() -> profilePictureUploadService.requestUploadUrl(command))
                .isInstanceOf(InvalidProfilePictureSizeException.class);
        verifyNoInteractions(userRepository, profilePictureStorage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5242880", "1024.0"})
    void shouldAcceptTheSizeBounds(String sizeBytes) {
        givenAcceptedUser();
        when(profilePictureStorage.createUploadUrl(any())).thenReturn(uploadUrl());

        profilePictureUploadService.requestUploadUrl(
                new RequestProfilePictureUploadCommand(USER_ID, JPEG, new BigDecimal(sizeBytes)));

        verify(profilePictureStorage).createUploadUrl(any());
    }

    @Test
    void shouldNameTheBlobAvatarsUserIdUuidWithTheContentTypesExtension() {
        givenAcceptedUser();
        var expected = uploadUrl();
        when(profilePictureStorage.createUploadUrl(any())).thenReturn(expected);
        var blobName = ArgumentCaptor.forClass(ProfilePictureBlobName.class);

        var response = profilePictureUploadService.requestUploadUrl(
                new RequestProfilePictureUploadCommand(USER_ID, "image/webp", VALID_SIZE));

        verify(profilePictureStorage).createUploadUrl(blobName.capture());
        assertThat(blobName.getValue().value()).matches("avatars/" + USER_ID + "/[0-9a-f-]{36}\\.webp");
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void shouldRejectAnUnknownOrNotAcceptedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profilePictureUploadService.requestUploadUrl(
                new RequestProfilePictureUploadCommand(USER_ID, JPEG, VALID_SIZE)))
                .isInstanceOf(UserNotFoundException.class);
        verify(profilePictureStorage, never()).createUploadUrl(any());
    }

    private void givenAcceptedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(UserMother.accepted(USER_ID, "avatar-user")));
    }

    private static ProfilePictureUploadUrlResponse uploadUrl() {
        return new ProfilePictureUploadUrlResponse(
                "https://account.blob.core.windows.net/post-media/avatars/x.jpg?sp=cw",
                "https://account.blob.core.windows.net/post-media/avatars/x.jpg",
                Instant.parse("2026-11-19T16:00:00Z"));
    }
}
