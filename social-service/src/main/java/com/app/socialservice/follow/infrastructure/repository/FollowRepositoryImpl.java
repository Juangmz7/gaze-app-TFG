package com.app.socialservice.follow.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.follow.infrastructure.entity.FollowEntity;
import com.app.socialservice.follow.infrastructure.entity.FollowEntityId;
import com.app.socialservice.follow.infrastructure.enums.FollowStatus;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FollowRepositoryImpl implements FollowRepository {

    private final JpaFollowRepository jpaFollowRepository;

    @Override
    public Optional<Follow> findActiveByUsers(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        return jpaFollowRepository.findById(new FollowEntityId(followerUserId, followedUserId))
                .filter(entity -> entity.getStatus() == FollowStatus.ACTIVE)
                .map(this::toDomain);
    }

    @Override
    public Optional<Follow> findRemovedByUsers(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        return jpaFollowRepository.findById(new FollowEntityId(followerUserId, followedUserId))
                .filter(entity -> entity.getStatus() == FollowStatus.REMOVED)
                .map(this::toDomain);
    }

    @Override
    public int insertIfAbsent(Follow follow) {
        if (follow == null) {
            throw new IllegalArgumentException("follow must not be null");
        }

        return jpaFollowRepository.insertIfAbsent(
                follow.getFollowerId().value(),
                follow.getFollowedId().value(),
                FollowStatus.ACTIVE.name(),
                follow.getCreatedAt(),
                follow.getCreatedAt()
        );
    }

    @Override
    public boolean existsBlockedByUsers(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        return jpaFollowRepository.findById(new FollowEntityId(followerUserId, followedUserId))
                .map(entity -> entity.getStatus() == FollowStatus.BLOCKED)
                .orElse(false);
    }

    @Override
    public int reactivate(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        return jpaFollowRepository.reactivateIfRemoved(followerUserId, followedUserId);
    }

    @Override
    public int markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId) {
        validateUserIds(firstUserId, secondUserId);

        return jpaFollowRepository.markBidirectionalAsBlocked(firstUserId, secondUserId);
    }

    private Follow toDomain(FollowEntity entity) {
        return new Follow(
                new UserId(entity.getId().getFollowerId()),
                new UserId(entity.getId().getFollowedId()),
                entity.getCreatedAt()
        );
    }

    private void validateUserIds(UUID firstUserId, UUID secondUserId) {
        if (firstUserId == null) {
            throw new IllegalArgumentException("firstUserId must not be null");
        }
        if (secondUserId == null) {
            throw new IllegalArgumentException("secondUserId must not be null");
        }
    }
}
