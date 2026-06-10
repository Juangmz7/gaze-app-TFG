package com.app.socialservice.user.service;

import com.app.socialservice.shared.infrastructure.entity.OutboxEvents;
import com.app.socialservice.shared.infrastructure.entity.ProcessedEvents;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventsRepository;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.domain.events.UserRegisteredDomainEvent;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;


@Slf4j
@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProcessedEventsRepository processedEventsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventsRepository outboxEventsRepository;

    @Transactional()
    public void registerUser(UserRegisterCommand command) {
        if (processedEventsRepository.existsById(command.correlationId())) {
            log.warn("Detected event {} duplication, discarding message...", command.correlationId());
            return;
        }
        if (userRepository.existsById(command.userId())) {
            log.warn("Detected user {} already exists, discarding message...", command.correlationId());
            return;
        }

        log.debug("Starting user {} registration", command.userId());
        var user = new User(
                new UserId(command.userId()),
                new Username(command.username()),
                new Email(command.email())
        );

        var savedUser = userRepository.save(user);

        processedEventsRepository.save(
                new ProcessedEvents(command.correlationId())
        );

        var occurredOn = Instant.now();

        outboxEventsRepository.save(
                OutboxEvents.builder()
                        // ID generated in record
                        .correlationId(command.correlationId)
                        .payload(jsonParser.toJson(savedUser)) // TODO
                        .status(EventStatus.PENDING)
                        .eventType(EventType.USER_REGISTERED)
                        .createdAt(occurredOn)
                        .build()
        );

        log.info("User {} registration completed successfully", command.userId());
        eventPublisher.publishEvent(new UserRegisteredDomainEvent(
                // ID generated in record
                savedUser.getId(),
                occurredOn
        ));
    }
}
