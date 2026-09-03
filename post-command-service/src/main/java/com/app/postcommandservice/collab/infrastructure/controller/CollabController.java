package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.collab.application.commands.CloseCollabCommand;
import com.app.postcommandservice.collab.application.commands.OpenCollabAndCreatePostCommand;
import com.app.postcommandservice.collab.application.commands.RequestToJoinCollabCommand;
import com.app.postcommandservice.collab.application.dto.CollabMemberResponse;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.usecase.CloseCollabUseCase;
import com.app.postcommandservice.collab.application.usecase.OpenCollabAndCreatePostUseCase;
import com.app.postcommandservice.collab.application.usecase.RequestToJoinCollabUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

@RestController
@RequestMapping("/api/collabs")
@RequiredArgsConstructor
public class CollabController {

    private final CloseCollabUseCase closeCollabUseCase;
    private final OpenCollabAndCreatePostUseCase openCollabAndCreatePostUseCase;
    private final RequestToJoinCollabUseCase requestToJoinCollabUseCase;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<OpenCollabAndCreatePostResponse> openCollab(
            @Valid @RequestBody OpenCollabAndCreatePostRequest request) {

        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    "JWT subject claim is missing or invalid"
            );
        }

        var command = new OpenCollabAndCreatePostCommand(
                request.correlationId(),
                currentUserId,
                request.title(),
                request.description(),
                request.taggedUsers(),
                request.postTags()
        );

        return ResponseEntity.ok(
                openCollabAndCreatePostUseCase.open(command)
        );
    }

    @PostMapping("/{collabId}/requests")
    public ResponseEntity<CollabMemberResponse> requestToJoinCollab(
            @PathVariable UUID collabId) {

        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    "JWT subject claim is missing or invalid"
            );
        }

        var command = new RequestToJoinCollabCommand(
                collabId,
                currentUserId
        );

        return ResponseEntity.ok(
                requestToJoinCollabUseCase.request(command)
        );
    }

    @RequestMapping(
            path = "/{collabId}/close",
            method = {RequestMethod.PUT, RequestMethod.PATCH}
    )
    public ResponseEntity<Void> closeCollab(
            @PathVariable UUID collabId) {

        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    "JWT subject claim is missing or invalid"
            );
        }

        boolean closed = closeCollabUseCase.close(
                new CloseCollabCommand(
                        collabId,
                        currentUserId
                )
        );

        if (!closed) {
            return ResponseEntity.accepted().build();
        }

        return ResponseEntity.noContent().build();
    }
}