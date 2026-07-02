package com.app.socialservice.user.infrastructure.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.dto.OwnUserProfileResponse;
import com.app.socialservice.user.application.service.UserService;

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
    }
}
