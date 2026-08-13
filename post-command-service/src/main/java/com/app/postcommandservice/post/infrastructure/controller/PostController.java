package com.app.postcommandservice.post.infrastructure.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.shared.infrastructure.security.SecurityUtils;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final CreatePostUseCase createPostUseCase;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest request) {
        var currentUserId = securityUtils.getUserId();
        if (currentUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("JWT subject claim is missing or invalid");
        }

        var command = new CreatePostCommand(
                request.correlationId(),
                currentUserId,
                request.description(),
                request.taggedUsers(),
                request.postTags()
        );

        return ResponseEntity.ok(createPostUseCase.createPost(command));
    }
}
