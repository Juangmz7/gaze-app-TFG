package com.app.socialservice.block.infrastructure.controller;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.commands.UnblockUserCommand;
import com.app.socialservice.block.application.dto.BlockResponse;
import com.app.socialservice.block.application.service.BlockService;
import com.app.socialservice.block.infrastructure.request.BlockUserRequest;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;
    private final SecurityUtils securityUtils;

    @PostMapping("/block")
    public ResponseEntity<BlockResponse> blockUser(@Valid @RequestBody BlockUserRequest request) {
        var blockerUserId = securityUtils.getUserId();
        if (blockerUserId == null) {
            throw new IllegalArgumentException("Authenticated user id cannot be found");
        }

        var response = blockService.blockUser(new BlockUserCommand(blockerUserId, request.blockedUserId()));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/block")
    public ResponseEntity<Void> unblockUser(@Valid @RequestBody BlockUserRequest request) {
        var unblockerUserId = securityUtils.getUserId();
        if (unblockerUserId == null) {
            throw new IllegalArgumentException("Authenticated user id cannot be found");
        }

        blockService.unblockUser(new UnblockUserCommand(unblockerUserId, request.blockedUserId()));
        return ResponseEntity.ok().build();
    }
}
