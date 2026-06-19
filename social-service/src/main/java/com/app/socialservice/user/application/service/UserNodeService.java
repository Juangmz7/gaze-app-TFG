package com.app.socialservice.user.application.service;

import com.app.socialservice.user.application.commands.SynchroniseSecondaryDatabaseCommand;
import com.app.socialservice.user.infrastructure.entity.UserNode;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserNodeService {

    private final UserNodeRepository userNodeRepository;

    @Transactional("neo4jTransactionManager")
    public void registerUserNode(SynchroniseSecondaryDatabaseCommand command) {
        if (userNodeRepository.existsById(command.userId())) {
            log.warn("Detected event duplication correlationId: {} eventId: {}, discarding message...",
                    command.correlationId(), command.eventId());
            return;
        }
        log.debug("Registering node for event: {} with correlationId: {}",
                command.eventId(), command.correlationId());

        var node = UserNode.builder()
                .id(command.userId())
                .build();

        var savedNode = userNodeRepository.save(node);

        log.debug("Registration completed for userNode {} for event: {} with correlationId: {}",
                savedNode.getId(), command.eventId(), command.correlationId());
    }

    @Transactional("neo4jTransactionManager")
    public void deleteUserNode(SynchroniseSecondaryDatabaseCommand command) {
        if (!userNodeRepository.existsById(command.userId())) {
            log.warn("UserNode {} does not exist for event: {} with correlationId: {}, discarding message...",
                    command.userId(), command.eventId(), command.correlationId());
            return;
        }
        log.debug("Deleting node for event: {} with correlationId: {}",
                command.eventId(), command.correlationId());

        userNodeRepository.deleteById(command.userId());

        log.debug("Deletion completed for userNode {} for event: {} with correlationId: {}",
                command.userId(), command.eventId(), command.correlationId());
    }
}
