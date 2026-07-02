package com.app.socialservice.user.infrastructure.controller;

import java.util.UUID;

import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.dto.UserProfileResponse;
import com.app.socialservice.user.application.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final SecurityUtils securityUtils;

    @GetMapping("/profile/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable UUID userId) {
        var requesterUserId = securityUtils.getUserId();
        if (requesterUserId == null) {
            throw new IllegalArgumentException("Authenticated user id cannot be found");
        }

        var response = userProfileService.getUserProfile(requesterUserId, userId);
        return ResponseEntity.ok(response);
    }
}
