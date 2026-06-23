package com.app.socialservice.block.application.service;

import java.util.UUID;

import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BlockNodeService {

    private final FollowGraphRepository followGraphRepository;

    @Transactional("neo4jTransactionManager")
    public void deleteBidirectionalFollowRelationship(UUID blockerUserId, UUID blockedUserId) {
        if (blockerUserId == null) {
            throw new IllegalArgumentException("blockerUserId must not be null");
        }
        if (blockedUserId == null) {
            throw new IllegalArgumentException("blockedUserId must not be null");
        }

        followGraphRepository.deleteBidirectionalFollowRelationship(blockerUserId, blockedUserId);
    }
}
