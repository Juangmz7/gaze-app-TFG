package com.app.socialservice.follow.application.service;

import java.util.UUID;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.follow.application.commands.FollowUserCommand;
import com.app.socialservice.follow.application.commands.UnfollowUserCommand;
import com.app.socialservice.follow.application.dto.FollowResponse;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.follow.domain.events.UserFollowedDomainEvent;
import com.app.socialservice.follow.domain.events.UserUnfollowedDomainEvent;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.follow.infrastructure.mapper.FollowEventMapper;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final FollowEventMapper followEventMapper;
    private final JsonMapper jsonMapper;

    @Transactional
    public FollowResponse followUser(FollowUserCommand command) {

        validateCommandInput(command);

        if (isBlocked(command.followerUserId(), command.followedUserId())) {
            throw new FollowBlockedException(String.format(
                    "Follow relationship is blocked between %s and %s",
                    command.followerUserId(),
                    command.followedUserId()
            ));
        }

        var attemptedFollow = newFollow(command);
        var inserted = followRepository.insertIfAbsent(attemptedFollow);
        if (!inserted) {
            return handleExistingFollow(command, attemptedFollow);
        }

        var savedFollow = followRepository.findActiveByUsers(command.followerUserId(), command.followedUserId())
                .orElse(attemptedFollow);

        return publishCreatedFollow(command, savedFollow);
    }

    @Transactional
    public void unfollowUser(UnfollowUserCommand command) {

        validateUnfollowCommandInput(command);

        var activeFollow = followRepository.findActiveByUsers(command.followerUserId(), command.followedUserId());
        if (activeFollow.isEmpty()) {
            log.info("No active follow found for follower {} and followed {}. Returning idempotent success.",
                    command.followerUserId(), command.followedUserId());
            return;
        }

        var removed = followRepository.markAsRemoved(command.followerUserId(), command.followedUserId());
        if (!removed) {
            log.warn("Mark as removed had no effect for follower {} and followed {}. Possible concurrent status change.",
                    command.followerUserId(), command.followedUserId());
            return;
        }

        var occurredOn = java.time.Instant.now();
        var outboxEvent = createAndSaveUnfollowOutboxEvent(activeFollow.get(), occurredOn);

        log.info("Follow removed for follower {} and followed {} with outbox id {}",
                command.followerUserId(), command.followedUserId(), outboxEvent.getId());

        eventPublisher.publishEvent(new UserUnfollowedDomainEvent(
                outboxEvent.getId(),
                activeFollow.get().getFollowerId().value(),
                activeFollow.get().getFollowedId().value(),
                occurredOn
        ));
    }

    private void validateCommandInput(FollowUserCommand command) {
        if (command.followerUserId().equals(command.followedUserId())) {
            throw new SelfFollowNotAllowedException("A user cannot follow themselves");
        }

        if (userRepository.findById(command.followerUserId()).isEmpty()) {
            throw new UserNotFoundException("User not found: " + command.followerUserId());
        }
        if (userRepository.findById(command.followedUserId()).isEmpty()) {
            throw new UserNotFoundException("User not found: " + command.followedUserId());
        }
    }

    static Follow newFollow(FollowUserCommand command) {
        return new Follow(
                new UserId(command.followerUserId()),
                new UserId(command.followedUserId()),
                java.time.Instant.now()
        );
    }

    static Follow newFollow(UUID followerUserId, UUID followedUserId) {
        return new Follow(
                new UserId(followerUserId),
                new UserId(followedUserId),
                java.time.Instant.now()
        );
    }

    private FollowResponse handleExistingFollow(FollowUserCommand command, Follow attemptedFollow) {
        if (followRepository.existsBlockedByUsers(command.followerUserId(), command.followedUserId())) {
            throw new FollowBlockedException(String.format(
                    "Follow relationship is blocked between %s and %s",
                    command.followerUserId(),
                    command.followedUserId()
            ));
        }

        var removedFollow = followRepository.findRemovedByUsers(command.followerUserId(), command.followedUserId());
        if (removedFollow.isPresent()) {
            var reactivated = followRepository.reactivate(command.followerUserId(), command.followedUserId());
            if (reactivated) {
                return publishCreatedFollow(command, removedFollow.get());
            }
            log.warn("Reactivation had no effect for follower {} and followed {}. " +
                            "Possible concurrent status change.",
                    command.followerUserId(), command.followedUserId());
            return resolveConcurrentReactivation(command);
        }

        var activeFollow = followRepository.findActiveByUsers(command.followerUserId(), command.followedUserId());
        if (activeFollow.isPresent()) {
            log.info("Follow already exists for follower {} and followed {}",
                    command.followerUserId(), command.followedUserId());
            return toResponse(activeFollow.get());
        }

        log.info("Follow insert was ignored for follower {} and followed {} without a resolvable persisted state",
                command.followerUserId(), command.followedUserId());
        return toResponse(attemptedFollow);
    }

    private FollowResponse resolveConcurrentReactivation(FollowUserCommand command) {
        var followerId = command.followerUserId();
        var followedId = command.followedUserId();

        var activeFollow = followRepository.findActiveByUsers(followerId, followedId);
        if (activeFollow.isPresent()) {
            return toResponse(activeFollow.get());
        }

        if (followRepository.existsBlockedByUsers(followerId, followedId)) {
            throw new FollowBlockedException(String.format(
                    "Follow relationship is blocked between %s and %s",
                    followerId,
                    followedId
            ));
        }

        throw new ObjectOptimisticLockingFailureException(Follow.class, followerId + ":" + followedId);
    }

    private FollowResponse publishCreatedFollow(FollowUserCommand command, Follow savedFollow) {
        var occurredOn = java.time.Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(savedFollow, occurredOn);

        log.info("Follow created for follower {} and followed {} with outbox id {}",
                command.followerUserId(), command.followedUserId(), outboxEvent.getId());

        eventPublisher.publishEvent(new UserFollowedDomainEvent(
                outboxEvent.getId(),
                savedFollow.getFollowerId().value(),
                savedFollow.getFollowedId().value(),
                occurredOn
        ));

        return toResponse(savedFollow);
    }

    private void validateUnfollowCommandInput(UnfollowUserCommand command) {
        if (command.followerUserId().equals(command.followedUserId())) {
            throw new SelfUnfollowNotAllowedException("A user cannot unfollow themselves");
        }

        if (userRepository.findById(command.followerUserId()).isEmpty()) {
            throw new UserNotFoundException("User not found: " + command.followerUserId());
        }
        if (userRepository.findById(command.followedUserId()).isEmpty()) {
            throw new UserNotFoundException("User not found: " + command.followedUserId());
        }
    }

    private boolean isBlocked(UUID followerUserId, UUID followedUserId) {
        return blockRepository.existsByUsers(followerUserId, followedUserId)
                || blockRepository.existsByUsers(followedUserId, followerUserId);
    }

    private OutboxEvent createAndSaveOutboxEvent(Follow follow, java.time.Instant occurredOn) {
        var correlationId = UUID.randomUUID();
        var followedEvent = followEventMapper.toUserFollowedEvent(
                UUID.randomUUID(),
                correlationId,
                follow,
                occurredOn
        );
        var payload = jsonMapper.toJson(followedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(correlationId)
                        .payload(payload)
                        .eventType(UserFollowedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    private OutboxEvent createAndSaveUnfollowOutboxEvent(Follow follow, java.time.Instant occurredOn) {
        var correlationId = UUID.randomUUID();
        var unfollowedEvent = followEventMapper.toUserUnfollowedEvent(
                UUID.randomUUID(),
                correlationId,
                follow,
                occurredOn
        );
        var payload = jsonMapper.toJson(unfollowedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(correlationId)
                        .payload(payload)
                        .eventType(UserUnfollowedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    private FollowResponse toResponse(Follow follow) {
        return new FollowResponse(
                follow.getFollowerId().value(),
                follow.getFollowedId().value(),
                follow.getCreatedAt()
        );
    }
}
