package com.app.postcommandservice.collab.infrastructure.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.collab.application.commands.AcceptCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.dto.AcceptCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.usecase.AcceptCollabJoinRequestUseCase;
import com.app.postcommandservice.collab.application.commands.OpenCollabAndCreatePostCommand;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.usecase.OpenCollabAndCreatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

@RestController
@RequestMapping("/api/collabs")
@RequiredArgsConstructor
public class CollabController {

    private final AcceptCollabJoinRequestUseCase acceptCollabJoinRequestUseCase;
    private final OpenCollabAndCreatePostUseCase openCollabAndCreatePostUseCase;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<OpenCollabAndCreatePostResponse> openCollab(
            @Valid @RequestBody OpenCollabAndCreatePostRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new OpenCollabAndCreatePostCommand(
                request.correlationId(),
                currentUserId,
                request.title(),
                request.description(),
                request.taggedUsers(),
                request.postTags()
        );

        return ResponseEntity.ok(openCollabAndCreatePostUseCase.open(command));
    }

    @PutMapping("/{collabId}/requests/{userId}/accept")
    public ResponseEntity<AcceptCollabJoinRequestResponse> acceptJoinRequest(
            @PathVariable("collabId") java.util.UUID collabId,
            @PathVariable("userId") java.util.UUID userId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new AcceptCollabJoinRequestCommand(collabId, userId, currentUserId);
        return ResponseEntity.ok(acceptCollabJoinRequestUseCase.accept(command));
    }
}
