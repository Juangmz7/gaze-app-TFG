package com.app.socialservice.user.infrastructure.controller;

import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.commands.UpdateOwnUserProfileCommand;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.service.UserService;
import com.app.socialservice.user.infrastructure.request.UpdateOwnUserProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserService userService;
    private final SecurityUtils securityUtils;

    @GetMapping("/me")
    public ResponseEntity<OwnUserProfileResponse> getOwnProfile() {
        var userId = securityUtils.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Authenticated user id cannot be found");
        }

        return ResponseEntity.ok(userService.getOwnProfile(userId));
    @PutMapping("/profile")
    public ResponseEntity<OwnUserProfileResponse> updateOwnProfile(
            @Valid @RequestBody UpdateOwnUserProfileRequest request) {

        var authenticatedUserId = securityUtils.getUserId();
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user id cannot be found");
        }

        var response = userService.updateOwnUserProfile(new UpdateOwnUserProfileCommand(
                authenticatedUserId,
                request.description(),
                request.profilePicture(),
                request.socialMedia()
        ));

        return ResponseEntity.ok(response);
    }
}
