package com.app.socialservice.block.application.service;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.dto.BlockPersistenceResult;
import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.UserNotFoundException;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BlockPersistenceService blockPersistenceService;

    @Mock
    private BlockGraphService blockGraphService;

    @Mock
    private BlockEventService blockEventService;

    @InjectMocks
    private BlockService blockService;

    @Test
    void shouldCreateBlockRelationshipWhenBothUsersExistAndAreDifferent() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenReturn(new BlockPersistenceResult(block, true, outboxEventId));

        var response = blockService.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        assertThat(response.createdAt()).isEqualTo(block.getCreatedAt());

        InOrder inOrder = inOrder(blockPersistenceService, blockGraphService, blockEventService);
        inOrder.verify(blockPersistenceService).createBlockAndUpdateFollows(blockerId, blockedId);
        inOrder.verify(blockGraphService).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        inOrder.verify(blockEventService).enqueueAndPublishPendingBlockEvent(outboxEventId, block);
    }

    @Test
    void shouldReturnWithoutErrorWhenBlockAlreadyExists() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenReturn(new BlockPersistenceResult(block, false, null));

        var response = blockService.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        verify(blockGraphService).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        verify(blockEventService, never()).enqueueAndPublishPendingBlockEvent(any(), any());
    }

    @Test
    void shouldPromotePreviouslyStoredWaitingEventWhenRetrySucceedsAfterNeo4jFailure() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenReturn(new BlockPersistenceResult(block, true, outboxEventId))
                .thenReturn(new BlockPersistenceResult(block, false, outboxEventId));
        org.mockito.Mockito.doThrow(new RuntimeException("neo4j cleanup failed"))
                .doNothing()
                .when(blockGraphService)
                .deleteBidirectionalFollowRelationship(blockerId, blockedId);

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        var response = blockService.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        verify(blockEventService).enqueueAndPublishPendingBlockEvent(outboxEventId, block);
    }

    @Test
    void shouldPromoteOnlyCorrectDirectionWaitingEventWhenOppositeDirectionAlsoExists() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var oppositeDirectionOutboxEventId = UUID.randomUUID();
        var createdAt = Instant.now();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), createdAt);
        var command = new BlockUserCommand(blockerId, blockedId);
        var oppositeDirectionPayload = "{\"direction\":\"ba\"}";
        var correctDirectionPayload = "{\"direction\":\"ab\"}";
        var oppositeDirectionOutboxEvent = OutboxEvent.builder()
                .id(oppositeDirectionOutboxEventId)
                .correlationId(UUID.randomUUID())
                .payload(oppositeDirectionPayload)
                .eventType(UserBlockedEvent.class.getSimpleName())
                .status(EventStatus.WAITING)
                .createdAt(createdAt.minusSeconds(10))
                .build();
        var oppositeDirectionEvent = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(createdAt.minusSeconds(10))
                .blockerUserId(blockedId)
                .blockedUserId(blockerId)
                .build();
        var correctDirectionEvent = UserBlockedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(createdAt)
                .blockerUserId(blockerId)
                .blockedUserId(blockedId)
                .build();
        var storedCorrectOutboxEvent = new AtomicReference<OutboxEvent>();

        var localBlockRepository = mock(BlockRepository.class);
        var localFollowRepository = mock(FollowRepository.class);
        var localOutboxEventRepository = mock(OutboxEventRepository.class);
        var localBlockEventMapper = mock(BlockEventMapper.class);
        var localJsonMapper = mock(JsonMapper.class);
        var localEventPublisher = mock(ApplicationEventPublisher.class);
        var realBlockEventService = new BlockEventService(
                localEventPublisher,
                localOutboxEventRepository,
                localBlockEventMapper,
                localJsonMapper
        );
        var realBlockPersistenceService = new BlockPersistenceService(
                localBlockRepository,
                localFollowRepository,
                realBlockEventService
        );
        var blockServiceUnderTest = new BlockService(
                userRepository,
                realBlockPersistenceService,
                blockGraphService,
                realBlockEventService
        );

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(localBlockRepository.findByUsers(blockerId, blockedId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(block));
        when(localBlockRepository.save(any(Block.class))).thenReturn(block);
        when(localBlockEventMapper.toUserBlockedEvent(any(), any(), eq(block), any())).thenReturn(correctDirectionEvent);
        when(localJsonMapper.toJson(correctDirectionEvent)).thenReturn(correctDirectionPayload);
        when(localOutboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> {
            var outboxEvent = invocation.getArgument(0, OutboxEvent.class);
            if (outboxEvent.getStatus() == EventStatus.WAITING) {
                storedCorrectOutboxEvent.set(outboxEvent);
            }
            return outboxEvent;
        });
        when(localOutboxEventRepository.findByEventTypeAndStatusOrderByCreatedAtAsc(
                UserBlockedEvent.class.getSimpleName(),
                EventStatus.WAITING
        )).thenAnswer(invocation -> List.of(oppositeDirectionOutboxEvent, storedCorrectOutboxEvent.get()));
        when(localJsonMapper.fromJson(oppositeDirectionPayload, UserBlockedEvent.class)).thenReturn(oppositeDirectionEvent);
        when(localJsonMapper.fromJson(correctDirectionPayload, UserBlockedEvent.class)).thenReturn(correctDirectionEvent);
        when(localOutboxEventRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            var outboxEventId = invocation.getArgument(0, UUID.class);
            if (storedCorrectOutboxEvent.get() != null && storedCorrectOutboxEvent.get().getId().equals(outboxEventId)) {
                return Optional.of(storedCorrectOutboxEvent.get());
            }
            if (oppositeDirectionOutboxEventId.equals(outboxEventId)) {
                return Optional.of(oppositeDirectionOutboxEvent);
            }
            return Optional.empty();
        });
        org.mockito.Mockito.doThrow(new RuntimeException("neo4j cleanup failed"))
                .doNothing()
                .when(blockGraphService)
                .deleteBidirectionalFollowRelationship(blockerId, blockedId);

        assertThatThrownBy(() -> blockServiceUnderTest.blockUser(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        var response = blockServiceUnderTest.blockUser(command);

        assertThat(response.blockerId()).isEqualTo(blockerId);
        assertThat(response.blockedId()).isEqualTo(blockedId);
        assertThat(storedCorrectOutboxEvent.get()).isNotNull();
        verify(localOutboxEventRepository).findById(storedCorrectOutboxEvent.get().getId());
        verify(localOutboxEventRepository, never()).findById(oppositeDirectionOutboxEventId);
        var eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(localEventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOf(com.app.socialservice.block.domain.events.UserBlockedDomainEvent.class);
        var publishedEvent = (com.app.socialservice.block.domain.events.UserBlockedDomainEvent) eventCaptor.getValue();
        assertThat(publishedEvent.id()).isEqualTo(storedCorrectOutboxEvent.get().getId());
        assertThat(publishedEvent.blockerUserId()).isEqualTo(blockerId);
        assertThat(publishedEvent.blockedUserId()).isEqualTo(blockedId);
    }

    @Test
    void shouldThrowSelfBlockNotAllowedExceptionWhenBlockerEqualsBlocked() {
        var userId = UUID.randomUUID();
        var command = new BlockUserCommand(userId, userId);

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(SelfBlockNotAllowedException.class)
                .hasMessage("A user cannot block themselves");

        verifyNoInteractions(userRepository, blockPersistenceService, blockGraphService, blockEventService);
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

        verifyNoInteractions(blockPersistenceService, blockGraphService, blockEventService);
    }

    @Test
    void shouldPublishBlockEventToRabbitMqOutboxAfterSuccessfulBlock() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenReturn(new BlockPersistenceResult(block, true, outboxEventId));

        blockService.blockUser(command);

        verify(blockEventService).enqueueAndPublishPendingBlockEvent(outboxEventId, block);
    }

    @Test
    void shouldNotPublishEventIfPostgreSqlWriteFails() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenThrow(new RuntimeException("postgres write failed"));

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("postgres write failed");

        verify(blockGraphService, never()).deleteBidirectionalFollowRelationship(blockerId, blockedId);
        verify(blockEventService, never()).enqueueAndPublishPendingBlockEvent(any(), any());
    }

    @Test
    void shouldNotEnqueueOrPublishEventIfNeo4jCleanupFails() {
        var blockerId = UUID.randomUUID();
        var blockedId = UUID.randomUUID();
        var outboxEventId = UUID.randomUUID();
        var block = new Block(new UserId(blockerId), new UserId(blockedId), Instant.now());
        var command = new BlockUserCommand(blockerId, blockedId);

        when(userRepository.findById(blockedId)).thenReturn(Optional.of(buildUser(blockedId)));
        when(blockPersistenceService.createBlockAndUpdateFollows(blockerId, blockedId))
                .thenReturn(new BlockPersistenceResult(block, true, outboxEventId));
        org.mockito.Mockito.doThrow(new RuntimeException("neo4j cleanup failed"))
                .when(blockGraphService)
                .deleteBidirectionalFollowRelationship(blockerId, blockedId);

        assertThatThrownBy(() -> blockService.blockUser(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("neo4j cleanup failed");

        verify(blockEventService, never()).enqueueAndPublishPendingBlockEvent(any(), any());
    }

    private User buildUser(UUID userId) {
        return new User(
                new UserId(userId),
                new Username("target_user"),
                new Email("target@example.com")
        );
    }
}
