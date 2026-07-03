package com.app.socialservice.user.infrastructure.controller;

import java.util.UUID;

import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.service.UserProfileService;
import com.app.socialservice.user.infrastructure.request.UpdateOwnUserProfileRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/social/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final SecurityUtils securityUtils;

    @GetMapping("/me")
    public ResponseEntity<OwnUserProfileResponse> getOwnProfile() {
        var userId = securityUtils.getUserId();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        return ResponseEntity.ok(userProfileService.getOwnProfile(userId));
    }
  
    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable UUID userId) {
        var requesterUserId = securityUtils.getUserId();
        if (requesterUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        var response = userProfileService.getUserProfile(requesterUserId, userId);
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<OwnUserProfileResponse> updateOwnProfile(
            @Valid @RequestBody UpdateOwnUserProfileRequest request) {

        var authenticatedUserId = securityUtils.getUserId();
        if (authenticatedUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        var response = userProfileService.updateOwnUserProfile(new UpdateOwnUserProfileCommand(
                authenticatedUserId,
                request.description(),
                request.profilePicture(),
                request.socialMedia()
        ));

        return ResponseEntity.ok(response);
    }
}
