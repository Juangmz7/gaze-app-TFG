package com.app.socialservice.follow.infrastructure.controller;

import java.util.UUID;

import com.app.socialservice.follow.application.commands.FollowUserCommand;
import com.app.socialservice.follow.application.commands.UnfollowUserCommand;
import com.app.socialservice.follow.application.dto.FollowListItemResponse;
import com.app.socialservice.follow.application.dto.FollowListResponse;
import com.app.socialservice.follow.application.dto.FollowResponse;
import com.app.socialservice.follow.application.service.FollowListService;
import com.app.socialservice.follow.application.service.FollowService;
import com.app.socialservice.follow.infrastructure.request.FollowUserRequest;
import com.app.socialservice.shared.application.media.PictureUrlResponseSigner;
import com.app.socialservice.shared.application.pagination.KeysetPage;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;
    private final FollowListService followListService;
    private final PictureUrlResponseSigner pictureUrlResponseSigner;
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
    public ResponseEntity<Void> unfollowUser(@Valid @RequestBody FollowUserRequest request) {
        var followerUserId = securityUtils.getUserId();
        if (followerUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        followService.unfollowUser(new UnfollowUserCommand(followerUserId, request.followedUserId()));
        return ResponseEntity.noContent().build();
    }

    /**
     * cursor and limit are taken as text: a malformed value answers invalid_cursor / invalid_limit
     * (validated by the use case), not a generic binding error.
     */
    @GetMapping("/{userId}/followers")
    public ResponseEntity<FollowListResponse> getFollowers(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {

        var requesterUserId = requireAuthenticatedUserId();

        var page = followListService.getFollowers(requesterUserId, userId, cursor, limit);
        return ResponseEntity.ok(toSignedResponse(page));
    }

    @GetMapping("/{userId}/following")
    public ResponseEntity<FollowListResponse> getFollowing(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {

        var requesterUserId = requireAuthenticatedUserId();

        var page = followListService.getFollowing(requesterUserId, userId, cursor, limit);
        return ResponseEntity.ok(toSignedResponse(page));
    }

    private FollowListResponse toSignedResponse(KeysetPage<FollowListItemResponse> page) {
        return new FollowListResponse(pictureUrlResponseSigner.signAll(page.items()), page.nextCursor());
    }

    private UUID requireAuthenticatedUserId() {
        var userId = securityUtils.getUserId();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }
        return userId;
    }
}
