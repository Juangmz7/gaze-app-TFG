package com.app.socialservice.user.application.service;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;


@Slf4j
@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProcessedEventsRepository processedEventsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final UserEventMapper userEventMapper;
    private final JsonMapper jsonMapper;

    @Transactional
    public void registerUser(UserRegisterCommand command) {
        if (userRepository.existsById(command.userId())) {
            log.warn("Detected event {} duplication, discarding message...", command.correlationId());
            return;
        }
        if (processedEventsRepository.existsById(command.correlationId())) {
            log.warn("Detected user {} already exists, discarding message...", command.correlationId());
            return;
        }

        var savedUser = createAndSaveUser(command);

        processedEventsRepository.save(new ProcessedEvent(command.correlationId()));

        var occurredOn = Instant.now();
        var outboxEvent = createAndSaveOutboxEvent(command, savedUser, occurredOn);

        log.info("User {} registration completed successfully for event: {} with correlationId: {}",
                command.userId(), outboxEvent.getId(), command.correlationId());

        eventPublisher.publishEvent(new UserRegisteredDomainEvent(
                outboxEvent.getId(),
                savedUser.getId(),
                occurredOn
        ));
    }

    private User createAndSaveUser(UserRegisterCommand command) {
        log.debug("Starting user {} registration", command.userId());
        var user = new User(
                new UserId(command.userId()),
                new Username(command.username()),
                new Email(command.email())
        );
        return userRepository.save(user);
    }

    private OutboxEvent createAndSaveOutboxEvent(UserRegisterCommand command, User savedUser, Instant occurredOn) {
        var userRegisteredEvent = userEventMapper.toUserRegisteredEvent(
                UUID.randomUUID(),
                command.correlationId(),
                savedUser,
                occurredOn
        );
        var payload = jsonMapper.toJson(userRegisteredEvent);
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .correlationId(command.correlationId())
                        .payload(payload)
                        .eventType(UserRegisteredEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .createdAt(occurredOn)
                        .build()
        );
    }
}