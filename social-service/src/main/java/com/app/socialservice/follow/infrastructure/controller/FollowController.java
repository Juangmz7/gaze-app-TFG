package com.app.socialservice.follow.infrastructure.controller;

import com.app.socialservice.follow.application.commands.FollowUserCommand;
import com.app.socialservice.follow.application.commands.UnfollowUserCommand;
import com.app.socialservice.follow.application.dto.FollowResponse;
import com.app.socialservice.follow.application.service.FollowService;
import com.app.socialservice.follow.infrastructure.request.FollowUserRequest;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<FollowResponse> followUser(@Valid @RequestBody FollowUserRequest request) {
        var followerUserId = securityUtils.getUserId();
        if (followerUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        var response = followService.followUser(new FollowUserCommand(followerUserId, request.followedUserId()));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<FollowResponse> unfollowUser(@Valid @RequestBody FollowUserRequest request) {
        var followerUserId = securityUtils.getUserId();
        if (followerUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        var response = followService.unfollowUser(new UnfollowUserCommand(followerUserId, request.followedUserId()));
        return ResponseEntity.ok(response);
    }
}
