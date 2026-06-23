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
    public Follow save(Follow follow) {
        if (follow == null) {
            throw new IllegalArgumentException("follow must not be null");
        }

        var entity = new FollowEntity(
                new FollowEntityId(follow.getFollowerId().value(), follow.getFollowedId().value()),
                FollowStatus.ACTIVE,
                follow.getCreatedAt(),
                null
        );

        return toDomain(jpaFollowRepository.save(entity));
    }

    @Override
    public boolean existsBlockedByUsers(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        return jpaFollowRepository.findById(new FollowEntityId(followerUserId, followedUserId))
                .map(entity -> entity.getStatus() == FollowStatus.BLOCKED)
                .orElse(false);
    }

    @Override
    public Follow reactivate(UUID followerUserId, UUID followedUserId) {
        validateUserIds(followerUserId, followedUserId);

        var followEntity = jpaFollowRepository.findById(new FollowEntityId(followerUserId, followedUserId))
                .orElseThrow(() -> new IllegalArgumentException("follow relationship must exist to reactivate"));

        followEntity.setStatus(FollowStatus.ACTIVE);
        return toDomain(jpaFollowRepository.save(followEntity));
    }

    @Override
    public void markBidirectionalRelationshipsAsBlocked(UUID firstUserId, UUID secondUserId) {
        validateUserIds(firstUserId, secondUserId);

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
