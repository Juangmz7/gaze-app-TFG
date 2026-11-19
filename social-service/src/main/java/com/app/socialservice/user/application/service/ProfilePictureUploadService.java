package com.app.socialservice.user.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.user.application.commands.RequestProfilePictureUploadCommand;
import com.app.socialservice.user.application.dto.ProfilePictureUploadUrlResponse;
import com.app.socialservice.user.application.port.ProfilePictureStorage;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.enums.ProfilePictureContentType;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureBlobName;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureSize;
import com.app.socialservice.user.domain.model.valueobj.UserId;

/**
 * Hands out a short-lived, write-only upload URL for a new avatar. The blob name is generated
 * here ({@code avatars/{userId}/{uuid}.{ext}}), never taken from the client. Not transactional:
 * it only reads whether the user exists, and signing is local.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePictureUploadService {

    private final UserRepository userRepository;
    private final ProfilePictureStorage profilePictureStorage;

    public ProfilePictureUploadUrlResponse requestUploadUrl(RequestProfilePictureUploadCommand command) {
        var contentType = ProfilePictureContentType.fromMimeType(command.contentType());
        var size = ProfilePictureSize.of(command.sizeBytes());
        var owner = new UserId(command.userId());
        assertUserExists(owner);

        var blobName = ProfilePictureBlobName.generate(owner, contentType);
        var response = profilePictureStorage.createUploadUrl(blobName);

        log.info("Avatar upload URL issued for user {}: blob={}, contentType={}, sizeBytes={}, expiresAt={}",
                owner.value(), blobName.value(), contentType.mimeType(), size.bytes(), response.expiresAt());
        return response;
    }

    private void assertUserExists(UserId owner) {
        if (userRepository.findById(owner.value()).isEmpty()) {
            throw new UserNotFoundException(owner.value());
        }
    }
}
