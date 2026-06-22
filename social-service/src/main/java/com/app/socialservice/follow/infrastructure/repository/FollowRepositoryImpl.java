package com.app.socialservice.follow.infrastructure.repository;

import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FollowRepositoryImpl implements FollowRepository {

    private final JpaFollowRepository jpaFollowRepository;

    @Override
    public void markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId) {
        if (firstUserId == null) {
            throw new IllegalArgumentException("firstUserId must not be null");
        }
        if (secondUserId == null) {
            throw new IllegalArgumentException("secondUserId must not be null");
        }

        var followIds = List.of(
                new FollowEntityId(firstUserId, secondUserId),
                new FollowEntityId(secondUserId, firstUserId)
        );

        var follows = jpaFollowRepository.findAllById(followIds);
        if (follows.isEmpty()) {
            return;
        }

        follows.forEach(follow -> follow.setStatus(FollowStatus.BLOCKED));
        jpaFollowRepository.saveAll(follows);
    }
}
