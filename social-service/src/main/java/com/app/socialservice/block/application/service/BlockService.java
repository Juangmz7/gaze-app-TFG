package com.app.socialservice.block.application.service;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.dto.BlockResponse;
import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.UserNotFoundException;
import com.app.socialservice.block.domain.events.UserBlockedDomainEvent;
import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import com.app.socialservice.block.infrastructure.mapper.BlockEventMapper;
import com.app.socialservice.block.application.repository.BlockRepository;
import com.app.socialservice.follow.application.repository.FollowRepository;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockService {

    private final BlockRepository blockRepository;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final BlockEventMapper blockEventMapper;
    private final JsonMapper jsonMapper;

    @Transactional
    public BlockResponse blockUser(BlockUserCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        validateCommandInput(command);

        var existingBlock = blockRepository.findByUsers(command.blockerUserId(), command.blockedUserId());
        if (existingBlock.isPresent()) {
            log.info("Block already exists for blocker {} and blocked {}",
                    command.blockerUserId(), command.blockedUserId());
            return toResponse(existingBlock.get());
        }

        var savedBlock = createAndSaveBlock(command);
        followRepository.markBidirectionalRelationshipsAsBlocked(command.blockerUserId(), command.blockedUserId());

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(savedBlock, occurredOn);

        log.info("Block created for blocker {} and blocked {} with outbox id {}",
                command.blockerUserId(), command.blockedUserId(), outboxEvent.getId());

        eventPublisher.publishEvent(new UserBlockedDomainEvent(
                outboxEvent.getId(),
                savedBlock.getBlockerId().value(),
                savedBlock.getBlockedId().value(),
                occurredOn
        ));

        return toResponse(savedBlock);
    }

    private void validateCommandInput(BlockUserCommand command) {
        if (command.blockerUserId().equals(command.blockedUserId())) {
            throw new SelfBlockNotAllowedException(
                    "A user cannot block themselves"
            );
        }

        var blockedUser = userRepository.findById(command.blockedUserId());
        if (blockedUser.isEmpty()) {
            throw new UserNotFoundException(
                    "User not found: " + command.blockedUserId()
            );
        }
    }

    private Block createAndSaveBlock(BlockUserCommand command) {
        var block = new Block(
                new UserId(command.blockerUserId()),
                new UserId(command.blockedUserId()),
                Instant.now()
        );
        return blockRepository.save(block);
    }

    private OutboxEvent createAndSaveOutboxEvent(Block block, Instant occurredOn) {
        var correlationId = UUID.randomUUID();
        var blockedEvent = blockEventMapper.toUserBlockedEvent(
                UUID.randomUUID(),
                correlationId,
                block,
                occurredOn
        );
        var payload = jsonMapper.toJson(blockedEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(correlationId)
                        .payload(payload)
                        .eventType(UserBlockedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }

    private BlockResponse toResponse(Block block) {
        return new BlockResponse(
                block.getBlockerId().value(),
                block.getBlockedId().value(),
                block.getCreatedAt()
        );
    }
}
