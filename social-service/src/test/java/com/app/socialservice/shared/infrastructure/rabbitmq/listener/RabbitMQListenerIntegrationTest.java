package com.app.socialservice.shared.infrastructure.rabbitmq.listener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.app.socialservice.TestcontainersConfiguration;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.shared.infrastructure.repository.ProcessedEventsRepository;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import com.app.socialservice.user.infrastructure.events.UserDeletedFromAuthEvent;
import com.app.socialservice.user.infrastructure.events.UserInfoFromAuthUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import com.app.socialservice.user.infrastructure.repository.JpaUserRepository;
import com.app.socialservice.user.infrastructure.repository.UserNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RabbitMQListenerIntegrationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @jakarta.annotation.Resource
    private RabbitMQListener rabbitMQListener;

    @jakarta.annotation.Resource
    private JpaUserRepository jpaUserRepository;

    @jakarta.annotation.Resource
    private UserNodeRepository userNodeRepository;

    @jakarta.annotation.Resource
    private ProcessedEventsRepository processedEventsRepository;

    @jakarta.annotation.Resource
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        processedEventsRepository.deleteAll();
        outboxEventRepository.deleteAll();
        userNodeRepository.deleteAll();
        jpaUserRepository.deleteAll();
    }

    @Test
    void rabbitMqListenerProcessesUserEventsAndSavesThemToProcessedEventsRepository() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserRegisteredFromAuth(event);

        assertThat(jpaUserRepository.findById(UUID.fromString(event.userId()))).isPresent();
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateEventsUsingProcessedEventsRepository() {
        var event = userRegisteredFromAuthEvent();
        var expectedEventId = deterministicUuid(
                "auth-register-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserRegisteredFromAuth(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserRegisteredFromAuth(event);

        assertThat(jpaUserRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    @Test
    void rabbitMqListenerProcessesUserAuthUpdateEventsAndSavesThemToProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-update", "before-update@example.com");
        var event = userInfoFromAuthUpdatedEvent(userId, "after-update", "after-update@example.com");
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getUsername, UserEntity::getEmail)
                .containsExactly("after-update", "after-update@example.com");
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateUserAuthUpdateEventsUsingProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-duplicate-update", "before-duplicate-update@example.com");
        var event = userInfoFromAuthUpdatedEvent(userId, "after-duplicate-update", "after-duplicate-update@example.com");
        var expectedEventId = deterministicUuid(
                "auth-update-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time()),
                event.details().username(),
                event.details().email()
        );

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserInfoFromAuthUpdated(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getUsername, UserEntity::getEmail)
                .containsExactly("after-duplicate-update", "after-duplicate-update@example.com");
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    @Test
    void rabbitMqListenerProcessesUserDeleteEventsAndSavesThemToProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-delete", "before-delete@example.com");
        var event = userDeletedFromAuthEvent(userId);
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        rabbitMQListener.onUserDeletedFromAuth(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getAccountStatus)
                .isEqualTo(UserAccountStatus.DELETED);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void rabbitMqListenerCorrectlyIgnoresDuplicateUserDeleteEventsUsingProcessedEventsRepository() {
        var userId = UUID.randomUUID();
        seedAcceptedUser(userId, "before-duplicate-delete", "before-duplicate-delete@example.com");
        var event = userDeletedFromAuthEvent(userId);
        var expectedEventId = deterministicUuid(
                "auth-delete-event",
                event.type(),
                event.userId(),
                String.valueOf(event.time())
        );

        rabbitMQListener.onUserDeletedFromAuth(event);

        var outboxCountAfterFirstMessage = outboxEventRepository.count();

        rabbitMQListener.onUserDeletedFromAuth(event);

        assertThat(jpaUserRepository.findById(userId)).get()
                .extracting(UserEntity::getAccountStatus)
                .isEqualTo(UserAccountStatus.DELETED);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirstMessage);
        assertThat(processedEventsRepository.findById(expectedEventId)).isPresent();
    }

    private UserRegisteredFromAuthEvent userRegisteredFromAuthEvent() {
        return new UserRegisteredFromAuthEvent(
                1_717_171_717_000L,
                "REGISTER",
                "social",
                "auth-service",
                UUID.randomUUID().toString(),
                "session",
                "127.0.0.1",
                null,
                new UserRegisteredFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Test",
                        "User",
                        "listener-it@example.com",
                        "listener-it"
                )
        );
    }

    private UserInfoFromAuthUpdatedEvent userInfoFromAuthUpdatedEvent(UUID userId, String username, String email) {
        return new UserInfoFromAuthUpdatedEvent(
                1_717_171_818_000L,
                "UPDATE",
                "social",
                "auth-service",
                userId.toString(),
                "session",
                "127.0.0.1",
                null,
                new UserInfoFromAuthUpdatedEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Updated",
                        "User",
                        email,
                        username
                )
        );
    }

    private UserDeletedFromAuthEvent userDeletedFromAuthEvent(UUID userId) {
        return new UserDeletedFromAuthEvent(
                1_717_171_919_000L,
                "DELETE",
                "social",
                "auth-service",
                userId.toString(),
                "session",
                "127.0.0.1",
                null,
                new UserDeletedFromAuthEvent.Details(
                        "pwd",
                        "code",
                        "self",
                        "http://localhost",
                        "Deleted",
                        "User",
                        "deleted@example.com",
                        "deleted-user"
                )
        );
    }

    private void seedAcceptedUser(UUID userId, String username, String email) {
        jpaUserRepository.save(UserEntity.builder()
                .id(userId)
                .username(username)
                .email(email)
                .accountStatus(UserAccountStatus.ACCEPTED)
                .build());
    }

    private UUID deterministicUuid(String namespace, String... components) {
        var seed = namespace + "|" + String.join("|", components);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
