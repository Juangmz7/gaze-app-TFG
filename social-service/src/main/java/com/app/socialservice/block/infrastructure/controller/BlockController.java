package com.app.socialservice.block.infrastructure.controller;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.commands.UnblockUserCommand;
import com.app.socialservice.block.application.dto.BlockResponse;
import com.app.socialservice.block.application.dto.BlockedUserResponse;
import com.app.socialservice.block.application.service.BlockService;
import com.app.socialservice.block.infrastructure.request.BlockUserRequest;
import jakarta.validation.constraints.Min;
import java.util.List;
import com.app.socialservice.shared.infrastructure.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@Validated
@RestController
@RequestMapping("/api/social/block")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;
    private final SecurityUtils securityUtils;

    @PostMapping
    public ResponseEntity<BlockResponse> blockUser(@Valid @RequestBody BlockUserRequest request) {
        var blockerUserId = securityUtils.getUserId();
        if (blockerUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        var response = blockService.blockUser(new BlockUserCommand(blockerUserId, request.blockedUserId()));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> unblockUser(@Valid @RequestBody BlockUserRequest request) {
        var unblockerUserId = securityUtils.getUserId();
        if (unblockerUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        blockService.unblockUser(new UnblockUserCommand(unblockerUserId, request.blockedUserId()));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users")
    public ResponseEntity<List<BlockedUserResponse>> getBlockedUsers(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be greater than or equal to 0") int page) {
        var requesterUserId = securityUtils.getUserId();
        if (requesterUserId == null) {
            throw new AuthenticationCredentialsNotFoundException("User authentication failed");
        }

        return ResponseEntity.ok(blockService.getBlockedUsers(requesterUserId, page));
    }
}
