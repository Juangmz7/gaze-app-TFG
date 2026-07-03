package com.app.socialservice.user.infrastructure.controller;

import java.util.List;

import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import com.app.socialservice.user.application.dto.RecommendedUserResponse;
import com.app.socialservice.user.application.service.RecommendedUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social/recommended")
@RequiredArgsConstructor
public class RecommendedUserController {

    private final RecommendedUserService recommendedUserService;
    private final SecurityUtils securityUtils;

    @GetMapping("/users")
    public ResponseEntity<List<RecommendedUserResponse>> getRecommendedUsers() {
        var requesterUserId = securityUtils.getUserId();
        if (requesterUserId == null) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        return ResponseEntity.ok(recommendedUserService.getRecommendedUsers(requesterUserId));
    }
}
