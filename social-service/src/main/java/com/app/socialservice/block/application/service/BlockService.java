package com.app.socialservice.block.application.service;

import com.app.socialservice.block.application.commands.BlockUserCommand;
import com.app.socialservice.block.application.dto.BlockResponse;
import com.app.socialservice.block.domain.exception.SelfBlockNotAllowedException;
import com.app.socialservice.block.domain.exception.UserNotFoundException;
import com.app.socialservice.user.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BlockService {

    private final UserRepository userRepository;
    private final BlockPersistenceService blockPersistenceService;
    private final BlockGraphService blockGraphService;
    private final BlockEventService blockEventService;

    public BlockResponse blockUser(BlockUserCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        validateCommandInput(command);

        var result = blockPersistenceService.createBlockAndUpdateFollows(
                command.blockerUserId(),
                command.blockedUserId()
        );

        blockGraphService.deleteBidirectionalFollowRelationship(
                command.blockerUserId(),
                command.blockedUserId()
        );

        if (result.outboxEventId() != null) {
            blockEventService.enqueueAndPublishPendingBlockEvent(result.outboxEventId(), result.block());
        }

        return new BlockResponse(
                result.block().getBlockerId().value(),
                result.block().getBlockedId().value(),
                result.block().getCreatedAt()
        );
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
}
