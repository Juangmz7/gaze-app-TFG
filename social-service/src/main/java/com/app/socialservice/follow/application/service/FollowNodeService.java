package com.app.socialservice.follow.application.service;

import java.util.UUID;

import com.app.socialservice.follow.application.repository.FollowGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FollowNodeService {

    private final FollowGraphRepository followGraphRepository;

    @Transactional("neo4jTransactionManager")
    public void createFollowRelationship(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("followerUserId must not equal followedUserId");
        }

        followGraphRepository.createFollowRelationship(followerUserId, followedUserId);
    }

    @Transactional("neo4jTransactionManager")
    public void deleteFollowRelationship(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null) {
            throw new IllegalArgumentException("followerUserId must not be null");
        }
        if (followedUserId == null) {
            throw new IllegalArgumentException("followedUserId must not be null");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new IllegalArgumentException("followerUserId must not equal followedUserId");
        }

        followGraphRepository.deleteFollowRelationship(followerUserId, followedUserId);
    }
}
