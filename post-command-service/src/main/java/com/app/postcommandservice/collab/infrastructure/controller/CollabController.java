package com.app.postcommandservice.collab.infrastructure.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.collab.application.commands.LeaveCollabCommand;
import com.app.postcommandservice.collab.application.commands.BanCollabMemberCommand;
import com.app.postcommandservice.collab.application.commands.AcceptCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.commands.CancelCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.commands.DeclineCollabJoinRequestCommand;
import com.app.postcommandservice.collab.application.dto.CancelCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.dto.DeclineCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.commands.RequestToJoinCollabCommand;
import com.app.postcommandservice.collab.application.dto.AcceptCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.usecase.AcceptCollabJoinRequestUseCase;
import com.app.postcommandservice.collab.application.usecase.CancelCollabJoinRequestUseCase;
import com.app.postcommandservice.collab.application.commands.CloseCollabCommand;
import com.app.postcommandservice.collab.application.usecase.DeclineCollabJoinRequestUseCase;
import com.app.postcommandservice.collab.application.commands.OpenCollabAndCreatePostCommand;
import com.app.postcommandservice.collab.application.commands.RequestToJoinCollabCommand;
import com.app.postcommandservice.collab.application.dto.CollabMemberResponse;
import com.app.postcommandservice.collab.application.dto.DeclineCollabJoinRequestResponse;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.usecase.LeaveCollabUseCase;
import com.app.postcommandservice.collab.application.usecase.BanCollabMemberUseCase;
import com.app.postcommandservice.collab.application.usecase.CloseCollabUseCase;
import com.app.postcommandservice.collab.application.usecase.OpenCollabAndCreatePostUseCase;
import com.app.postcommandservice.collab.application.usecase.RequestToJoinCollabUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

@RestController
@RequestMapping("/api/collabs")
@RequiredArgsConstructor
public class CollabController {

    private final AcceptCollabJoinRequestUseCase acceptCollabJoinRequestUseCase;
    private final CancelCollabJoinRequestUseCase cancelCollabJoinRequestUseCase;
    private final CloseCollabUseCase closeCollabUseCase;
    private final DeclineCollabJoinRequestUseCase declineCollabJoinRequestUseCase;
    private final OpenCollabAndCreatePostUseCase openCollabAndCreatePostUseCase;
    private final LeaveCollabUseCase leaveCollabUseCase;
    private final BanCollabMemberUseCase banCollabMemberUseCase;
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

    @DeleteMapping("/{collabId}/requests")
    public ResponseEntity<CancelCollabJoinRequestResponse> cancelJoinRequest(
            @PathVariable("collabId") java.util.UUID collabId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new CancelCollabJoinRequestCommand(collabId, currentUserId);
        return ResponseEntity.ok(cancelCollabJoinRequestUseCase.cancel(command));
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

    @PutMapping("/{collabId}/requests/{userId}/decline")
    public ResponseEntity<DeclineCollabJoinRequestResponse> declineJoinRequest(
            @PathVariable UUID collabId,
            @PathVariable UUID userId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new DeclineCollabJoinRequestCommand(collabId, userId, currentUserId);
        return ResponseEntity.ok(declineCollabJoinRequestUseCase.decline(command));
    }

    @RequestMapping(path = "/{collabId}/members/{userId}/ban", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<Void> banMember(
            @PathVariable("collabId") java.util.UUID collabId,
            @PathVariable("userId") java.util.UUID userId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        banCollabMemberUseCase.ban(new BanCollabMemberCommand(collabId, userId, currentUserId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{collabId}/leave")
    public ResponseEntity<Void> leaveCollab(@PathVariable("collabId") java.util.UUID collabId) {
        leaveCollabUseCase.leave(buildLeaveCommand(collabId));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{collabId}/leave")
    public ResponseEntity<Void> leaveCollabDelete(@PathVariable("collabId") java.util.UUID collabId) {
        leaveCollabUseCase.leave(buildLeaveCommand(collabId));
        return ResponseEntity.noContent().build();
    }

    private LeaveCollabCommand buildLeaveCommand(java.util.UUID collabId) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        return new LeaveCollabCommand(collabId, currentUserId);
    }
}
