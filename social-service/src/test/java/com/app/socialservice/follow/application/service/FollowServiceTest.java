package com.app.socialservice.follow.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.follow.application.commands.FollowUserCommand;
import com.app.socialservice.follow.application.commands.UnfollowUserCommand;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.follow.domain.events.UserFollowedDomainEvent;
import com.app.socialservice.follow.domain.exception.FollowBlockedException;
import com.app.socialservice.follow.domain.exception.SelfFollowNotAllowedException;
import com.app.socialservice.follow.domain.exception.SelfUnfollowNotAllowedException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.follow.testutil.FollowMother;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import com.app.socialservice.follow.infrastructure.mapper.FollowEventMapper;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.testutil.UserMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private BlockRepository blockRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private FollowEventMapper followEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private FollowService followService;

    @Test
    void shouldCreateFollowRelationshipWhenBothUsersExistAndAreDifferent() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var savedFollow = FollowMother.active(followerId, followedId, Instant.now());
        var command = new FollowUserCommand(followerId, followedId);
        var mappedEvent = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        when(userRepository.findById(followerId)).thenReturn(Optional.of(UserMother.accepted(followerId, "follower")));
        when(userRepository.findById(followedId)).thenReturn(Optional.of(UserMother.accepted(followedId, "followed")));
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(false);
        when(blockRepository.existsByUsers(followedId, followerId)).thenReturn(false);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(true);
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(savedFollow));
        when(followEventMapper.toUserFollowedEvent(any(), any(), any(Follow.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"followed\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = followService.followUser(command);

        assertThat(response.followerId()).isEqualTo(followerId);
        assertThat(response.followedId()).isEqualTo(followedId);

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserFollowedEvent.class.getSimpleName());

        var domainEventCaptor = ArgumentCaptor.forClass(UserFollowedDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxCaptor.getValue().getId());
        assertThat(domainEventCaptor.getValue().followerUserId()).isEqualTo(followerId);
        assertThat(domainEventCaptor.getValue().followedUserId()).isEqualTo(followedId);

        InOrder inOrder = inOrder(followRepository, outboxEventRepository, eventPublisher);
        inOrder.verify(followRepository).insertIfAbsent(any(Follow.class));
        inOrder.verify(outboxEventRepository).save(any(OutboxEvent.class));
        inOrder.verify(eventPublisher).publishEvent(any(UserFollowedDomainEvent.class));
    }

    @Test
    void shouldReturnWithoutErrorWhenFollowAlreadyExistsAsActive() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var existingFollow = FollowMother.active(followerId, followedId, Instant.now());
        var command = new FollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "follower-repeat")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "followed-repeat")));
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(false);
        when(blockRepository.existsByUsers(followedId, followerId)).thenReturn(false);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(existingFollow));

        var response = followService.followUser(command);

        assertThat(response.followerId()).isEqualTo(followerId);
        assertThat(response.followedId()).isEqualTo(followedId);
        verify(followRepository, never()).reactivate(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldReactivateRemovedFollowRelationship() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var existingRemovedFollow = FollowMother.removed(followerId, followedId, Instant.now().minusSeconds(10));
        var command = new FollowUserCommand(followerId, followedId);
        var mappedEvent = UserFollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "follower-removed")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "followed-removed")));
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(false);
        when(blockRepository.existsByUsers(followedId, followerId)).thenReturn(false);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.findRemovedByUsers(followerId, followedId))
                .thenReturn(Optional.of(existingRemovedFollow));
        when(followRepository.reactivate(followerId, followedId)).thenReturn(true);
        when(followEventMapper.toUserFollowedEvent(any(), any(), any(Follow.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"followed\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = followService.followUser(command);

        assertThat(response.createdAt()).isEqualTo(existingRemovedFollow.getCreatedAt());
        verify(followRepository).reactivate(followerId, followedId);
        verify(eventPublisher).publishEvent(any(UserFollowedDomainEvent.class));
    }

    @Test
    void shouldReturnActiveFollowWhenRemovedFollowWasConcurrentlyReactivated() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var removedFollow = FollowMother.removed(followerId, followedId, Instant.now().minusSeconds(20));
        var activeFollow = FollowMother.active(followerId, followedId, removedFollow.getCreatedAt());
        var command = new FollowUserCommand(followerId, followedId);

        givenUsersExist(followerId, followedId, "reactivated");
        givenUsersAreNotBlocked(followerId, followedId);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.existsBlockedByUsers(followerId, followedId)).thenReturn(false);
        when(followRepository.findRemovedByUsers(followerId, followedId)).thenReturn(Optional.of(removedFollow));
        when(followRepository.reactivate(followerId, followedId)).thenReturn(false);
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(activeFollow));

        var response = followService.followUser(command);

        assertThat(response.followerId()).isEqualTo(followerId);
        assertThat(response.followedId()).isEqualTo(followedId);
        assertThat(response.createdAt()).isEqualTo(activeFollow.getCreatedAt());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldRejectFollowWhenRemovedFollowWasConcurrentlyBlocked() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var removedFollow = FollowMother.removed(followerId, followedId, Instant.now().minusSeconds(20));
        var command = new FollowUserCommand(followerId, followedId);

        givenUsersExist(followerId, followedId, "blocked");
        givenUsersAreNotBlocked(followerId, followedId);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.existsBlockedByUsers(followerId, followedId)).thenReturn(false, true);
        when(followRepository.findRemovedByUsers(followerId, followedId)).thenReturn(Optional.of(removedFollow));
        when(followRepository.reactivate(followerId, followedId)).thenReturn(false);
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.followUser(command))
                .isInstanceOf(FollowBlockedException.class)
                .hasMessage("Follow relationship is blocked between " + followerId + " and " + followedId);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowConflictWhenRemovedFollowConcurrentStateCannotBeResolved() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var removedFollow = FollowMother.removed(followerId, followedId, Instant.now().minusSeconds(20));
        var command = new FollowUserCommand(followerId, followedId);

        givenUsersExist(followerId, followedId, "conflict");
        givenUsersAreNotBlocked(followerId, followedId);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.existsBlockedByUsers(followerId, followedId)).thenReturn(false, false);
        when(followRepository.findRemovedByUsers(followerId, followedId)).thenReturn(Optional.of(removedFollow));
        when(followRepository.reactivate(followerId, followedId)).thenReturn(false);
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.followUser(command))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldDeleteFollowsRelationshipWhenBothUsersExistAndAreDifferent() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var existingFollow = FollowMother.active(followerId, followedId, Instant.now().minusSeconds(30));
        var command = new UnfollowUserCommand(followerId, followedId);
        var mappedEvent = UserUnfollowedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .followerUserId(followerId)
                .followedUserId(followedId)
                .build();

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "unfollow-follower")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "unfollow-followed")));
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(existingFollow));
        when(followRepository.markAsRemoved(followerId, followedId)).thenReturn(true);
        when(followEventMapper.toUserUnfollowedEvent(any(), any(), any(Follow.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"unfollowed\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        followService.unfollowUser(command);

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserUnfollowedEvent.class.getSimpleName());

        verify(eventPublisher)
                .publishEvent(any(com.app.socialservice.follow.domain.events.UserUnfollowedDomainEvent.class));
    }

    @Test
    void shouldReturnWithoutErrorWhenFollowsRelationshipDoesNotExist() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new UnfollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "missing-unfollow-follower")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "missing-unfollow-followed")));
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.empty());

        followService.unfollowUser(command);

        verify(followRepository, never()).markAsRemoved(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowSelfUnfollowNotAllowedExceptionWhenUnfollowerEqualsUnfollowed() {
        var userId = UUID.randomUUID();
        var command = new UnfollowUserCommand(userId, userId);

        assertThatThrownBy(() -> followService.unfollowUser(command))
                .isInstanceOf(SelfUnfollowNotAllowedException.class)
                .hasMessage("A user cannot unfollow themselves");

        verifyNoInteractions(followRepository, userRepository, outboxEventRepository,
                followEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenUnfollowTargetUserDoesNotExist() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new UnfollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "unfollow-follower-missing")));
        when(userRepository.findById(followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.unfollowUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + followedId);

        verifyNoInteractions(followRepository, outboxEventRepository, followEventMapper,
                jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowSelfFollowNotAllowedExceptionWhenFollowerEqualsFollowed() {
        var userId = UUID.randomUUID();
        var command = new FollowUserCommand(userId, userId);

        assertThatThrownBy(() -> followService.followUser(command))
                .isInstanceOf(SelfFollowNotAllowedException.class)
                .hasMessage("A user cannot follow themselves");

        verifyNoInteractions(followRepository, userRepository, outboxEventRepository,
                followEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenTargetUserDoesNotExist() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new FollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "follower-missing")));
        when(userRepository.findById(followedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.followUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + followedId);

        verifyNoInteractions(followRepository, outboxEventRepository, followEventMapper,
                jsonMapper, eventPublisher);
    }

    @Test
    void shouldRejectFollowWhenBlockExistsBetweenUsers() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new FollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "follower-blocked")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "followed-blocked")));
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(true);

        assertThatThrownBy(() -> followService.followUser(command))
                .isInstanceOf(FollowBlockedException.class)
                .hasMessage("Follow relationship is blocked between " + followerId + " and " + followedId);

        verify(followRepository, never()).insertIfAbsent(any(Follow.class));
        verify(followRepository, never()).findRemovedByUsers(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishEventWhenInsertIsIgnoredAndFollowAlreadyExists() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var existingFollow = FollowMother.active(followerId, followedId, Instant.now().minusSeconds(30));
        var command = new FollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "follower-duplicate")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "followed-duplicate")));
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(false);
        when(blockRepository.existsByUsers(followedId, followerId)).thenReturn(false);
        when(followRepository.insertIfAbsent(any(Follow.class))).thenReturn(false);
        when(followRepository.findRemovedByUsers(followerId, followedId)).thenReturn(Optional.empty());
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(existingFollow));

        var response = followService.followUser(command);

        assertThat(response.createdAt()).isEqualTo(existingFollow.getCreatedAt());
        verify(followRepository).insertIfAbsent(any(Follow.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }



    @Test
    void shouldReturnIdempotentSuccessWhenUnfollowingBlockedUser() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new UnfollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "blocked-unfollower")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "blocked-unfollowed")));
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.empty());

        followService.unfollowUser(command);

        verify(followRepository, never()).markAsRemoved(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldReturnWithoutErrorWhenMarkAsRemovedFailsDueToConcurrency() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var existingFollow = FollowMother.active(followerId, followedId, Instant.now().minusSeconds(30));
        var command = new UnfollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, "concurrent-follower")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, "concurrent-followed")));
        when(followRepository.findActiveByUsers(followerId, followedId)).thenReturn(Optional.of(existingFollow));
        when(followRepository.markAsRemoved(followerId, followedId)).thenReturn(false);

        followService.unfollowUser(command);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowUserNotFoundWhenUnfollowFollowerUserDoesNotExist() {
        var followerId = UUID.randomUUID();
        var followedId = UUID.randomUUID();
        var command = new UnfollowUserCommand(followerId, followedId);

        when(userRepository.findById(followerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.unfollowUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + followerId);

        verifyNoInteractions(followRepository, outboxEventRepository, followEventMapper,
                jsonMapper, eventPublisher);
    }

    private void givenUsersExist(UUID followerId, UUID followedId, String usernamePrefix) {
        when(userRepository.findById(followerId))
                .thenReturn(Optional.of(UserMother.accepted(followerId, usernamePrefix + "-follower")));
        when(userRepository.findById(followedId))
                .thenReturn(Optional.of(UserMother.accepted(followedId, usernamePrefix + "-followed")));
    }

    private void givenUsersAreNotBlocked(UUID followerId, UUID followedId) {
        when(blockRepository.existsByUsers(followerId, followedId)).thenReturn(false);
        when(blockRepository.existsByUsers(followedId, followerId)).thenReturn(false);
    }
}
