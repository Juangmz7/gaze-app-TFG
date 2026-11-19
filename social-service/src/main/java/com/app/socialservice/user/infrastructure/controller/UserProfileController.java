package com.app.socialservice.user.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.socialservice.shared.application.media.PictureUrlResponseSigner;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.commands.RequestProfilePictureUploadCommand;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.dto.ProfilePictureUploadUrlResponse;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.service.ProfilePictureUploadService;
import com.app.socialservice.user.application.service.UserProfileService;
import com.app.socialservice.user.infrastructure.request.ProfilePictureUploadUrlRequest;
import com.app.socialservice.user.infrastructure.request.UpdateOwnUserProfileRequest;

@RestController
@RequestMapping("/api/social/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final ProfilePictureUploadService profilePictureUploadService;
    private final PictureUrlResponseSigner pictureUrlResponseSigner;
    private final SecurityUtils securityUtils;

    @GetMapping("/me")
    public ResponseEntity<OwnUserProfileResponse> getOwnProfile() {
        var userId = requireAuthenticatedUserId();

        return ResponseEntity.ok(pictureUrlResponseSigner.sign(userProfileService.getOwnProfile(userId)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable UUID userId) {
        var requesterUserId = requireAuthenticatedUserId();

        var response = userProfileService.getUserProfile(requesterUserId, userId);
        return ResponseEntity.ok(pictureUrlResponseSigner.sign(response));
    }

    /**
     * The same body as /{userId}, for mentions and tagged users that carry only a username.
     * The username is validated (and matched case-insensitively) by the use case.
     */
    @GetMapping("/by-username/{username}")
    public ResponseEntity<UserProfileResponse> getUserProfileByUsername(@PathVariable String username) {
        var requesterUserId = requireAuthenticatedUserId();

        var response = userProfileService.getUserProfileByUsername(requesterUserId, username);
        return ResponseEntity.ok(pictureUrlResponseSigner.sign(response));
    }

    /**
     * Served on /profile (the historical path) and /profile/me.
     */
    @PutMapping({"", "/me"})
    public ResponseEntity<OwnUserProfileResponse> updateOwnProfile(
            @Valid @RequestBody UpdateOwnUserProfileRequest request) {

        var authenticatedUserId = requireAuthenticatedUserId();

        var response = userProfileService.updateOwnUserProfile(new UpdateOwnUserProfileCommand(
                authenticatedUserId,
                request.description(),
                request.profilePicture(),
                request.socialMedia()
        ));

        return ResponseEntity.ok(pictureUrlResponseSigner.sign(response));
    }

    @PostMapping("/me/picture/upload-url")
    public ResponseEntity<ProfilePictureUploadUrlResponse> requestProfilePictureUploadUrl(
            @RequestBody ProfilePictureUploadUrlRequest request) {

        var authenticatedUserId = requireAuthenticatedUserId();

        return ResponseEntity.ok(profilePictureUploadService.requestUploadUrl(new RequestProfilePictureUploadCommand(
                authenticatedUserId,
                request.contentType(),
                request.sizeBytes()
        )));
    }

    private UUID requireAuthenticatedUserId() {
        var userId = securityUtils.getUserId();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }
        return userId;
    }
}
