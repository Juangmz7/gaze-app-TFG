package com.app.socialservice.block.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.commands.UnblockUserCommand;
import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.events.UserBlockedDomainEvent;
import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.SelfUnblockNotAllowedException;
import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.block.infrastructure.events.UserUnblockedEvent;
import com.app.socialservice.block.infrastructure.mapper.BlockEventMapper;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock
    private BlockRepository blockRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private BlockEventMapper blockEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private BlockService blockService;

    @Test
    void shouldCreateBlockRelationshipWhenBothUsersExistAndAreDifferent() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var savedBlock = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);
        var mappedEvent = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockRepository.insertIfAbsent(any(Block.class))).thenReturn(true);
        when(blockRepository.findByUsers(blockerId, blockedId)).thenReturn(Optional.of(savedBlock));
        when(blockEventMapper.toUserBlockedEvent(any(), any(), any(Block.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"blocked\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = blockService.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        assertThat(response.createdAt()).isEqualTo(savedBlock.getCreatedAt());

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserBlockedEvent.class.getSimpleName());

        var domainEventCaptor = ArgumentCaptor.forClass(UserBlockedDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxCaptor.getValue().getId());
        assertThat(domainEventCaptor.getValue().blockerUserId()).isEqualTo(blockerId);
        assertThat(domainEventCaptor.getValue().blockedUserId()).isEqualTo(blockedId);

        InOrder inOrder = inOrder(blockRepository, followRepository, outboxEventRepository, eventPublisher);
        inOrder.verify(blockRepository).insertIfAbsent(any(Block.class));
        inOrder.verify(followRepository).markBidirectionalRelationshipsAsBlocked(blockerId, blockedId);
        inOrder.verify(outboxEventRepository).save(any(OutboxEvent.class));
        inOrder.verify(eventPublisher).publishEvent(any(UserBlockedDomainEvent.class));
    }

    @Test
    void shouldReturnWithoutErrorWhenBlockAlreadyExists() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var existingBlock = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockRepository.insertIfAbsent(any(Block.class))).thenReturn(false);
        when(blockRepository.findByUsers(blockerId, blockedId)).thenReturn(Optional.of(existingBlock));

        var response = blockService.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        verify(followRepository, never()).markBidirectionalRelationshipsAsBlocked(any(), any());
        verify(blockRepository, never()).existsByUsers(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowSelfBlockNotAllowedExceptionWhenBlockerEqualsBlocked() {
        var userId = UUID.randomUUID();
        var command = new BlockUserCommand(userId, userId);

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(SelfBlockNotAllowedException.class)
                .hasMessage("A user cannot block themselves");

        verifyNoInteractions(blockRepository, userRepository, followRepository, outboxEventRepository,
                blockEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenTargetUserDoesNotExist() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + blockedId);

        verify(blockRepository, never()).insertIfAbsent(any(Block.class));
        verifyNoInteractions(followRepository, outboxEventRepository, blockEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldDeleteBlockRelationshipAndPublishUnblockEventWhenUsersExistAndAreDifferent() {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();
        var existingBlock = new Block(new UserId(unblockerId), new UserId(unblockedId), Instant.now().minusSeconds(60));
        var command = new UnblockUserCommand(unblockerId, unblockedId);
        var mappedEvent = UserUnblockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .blockerUserId(unblockerId)
                .blockedUserId(unblockedId)
                .build();

        when(userRepository.findById(unblockedId)).thenReturn(Optional.of(buildUser(unblockedId)));
        when(blockRepository.findByUsers(unblockerId, unblockedId)).thenReturn(Optional.of(existingBlock));
        when(blockRepository.deleteByUsers(unblockerId, unblockedId)).thenReturn(true);
        when(blockEventMapper.toUserUnblockedEvent(any(), any(), any(Block.class), any())).thenReturn(mappedEvent);
        when(jsonMapper.toJson(mappedEvent)).thenReturn("{\"type\":\"unblocked\"}");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        blockService.unblockUser(command);

        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(UserUnblockedEvent.class.getSimpleName());

        var domainEventCaptor = ArgumentCaptor.forClass(
                com.app.socialservice.block.domain.events.UserUnblockedDomainEvent.class);
        verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxCaptor.getValue().getId());
        assertThat(domainEventCaptor.getValue().blockerUserId()).isEqualTo(unblockerId);
        assertThat(domainEventCaptor.getValue().blockedUserId()).isEqualTo(unblockedId);

        InOrder inOrder = inOrder(blockRepository, followRepository, outboxEventRepository, eventPublisher);
        inOrder.verify(blockRepository).findByUsers(unblockerId, unblockedId);
        inOrder.verify(blockRepository).deleteByUsers(unblockerId, unblockedId);
        inOrder.verify(followRepository).markBidirectionalRelationshipsAsRemoved(unblockerId, unblockedId);
        inOrder.verify(outboxEventRepository).save(any(OutboxEvent.class));
        inOrder.verify(eventPublisher)
                .publishEvent(any(com.app.socialservice.block.domain.events.UserUnblockedDomainEvent.class));
    }

    @Test
    void shouldReturnWithoutErrorWhenBlockDoesNotExistDuringUnblock() {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();
        var command = new UnblockUserCommand(unblockerId, unblockedId);

        when(userRepository.findById(unblockedId)).thenReturn(Optional.of(buildUser(unblockedId)));
        when(blockRepository.findByUsers(unblockerId, unblockedId)).thenReturn(Optional.empty());
        when(blockRepository.deleteByUsers(unblockerId, unblockedId)).thenReturn(false);

        blockService.unblockUser(command);

        verify(followRepository, never()).markBidirectionalRelationshipsAsRemoved(any(), any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldThrowSelfUnblockNotAllowedExceptionWhenUnblockerEqualsUnblocked() {
        var userId = UUID.randomUUID();
        var command = new UnblockUserCommand(userId, userId);

        assertThatThrownBy(() -> blockService.unblockUser(command))
                .isInstanceOf(SelfUnblockNotAllowedException.class)
                .hasMessage("A user cannot unblock themselves");

        verifyNoInteractions(blockRepository, userRepository, followRepository, outboxEventRepository,
                blockEventMapper, jsonMapper, eventPublisher);
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenUnblockTargetUserDoesNotExist() {
        var unblockerId = UUID.randomUUID();
        var unblockedId = UUID.randomUUID();
        var command = new UnblockUserCommand(unblockerId, unblockedId);

        when(userRepository.findById(unblockedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blockService.unblockUser(command))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found: " + unblockedId);

        verify(blockRepository, never()).deleteByUsers(any(), any());
        verifyNoInteractions(followRepository, outboxEventRepository, blockEventMapper, jsonMapper, eventPublisher);
    }

    private User buildUser(UUID userId) {
        return new User(
                new UserId(userId),
                new Username("blocked-user"),
                new Email("blocked@example.com")
        );
    }
}
