package com.app.socialservice.user.application.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.app.socialservice.shared.domain.exception.UserNotFoundException;
import com.app.socialservice.shared.infrastructure.entity.OutboxEvent;
import com.app.socialservice.shared.infrastructure.enums.EventStatus;
import com.app.socialservice.shared.infrastructure.mapper.JsonMapper;
import com.app.socialservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.socialservice.user.application.repository.UserRepository;
import com.app.socialservice.user.domain.events.UserProfileUpdatedDomainEvent;
import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.infrastructure.events.UserBioEventPayload;
import com.app.socialservice.user.infrastructure.events.UserUpdatedEvent;
import com.app.socialservice.user.infrastructure.mapper.UserEventMapper;
import com.app.socialservice.user.testutil.UserMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnUserProfileWriterTest {

    private static final String OLD_PICTURE = "https://account.blob.core.windows.net/post-media/avatars/old.jpg";
    private static final String NEW_PICTURE = "https://account.blob.core.windows.net/post-media/avatars/new.jpg";
    private static final String PAYLOAD = "{\"type\":\"updated\"}";
    private static final Instant CREATED_AT = Instant.parse("2026-01-10T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-24T10:15:30Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final UserEventMapper userEventMapper = Mappers.getMapper(UserEventMapper.class);

    private OwnUserProfileWriter ownUserProfileWriter;

    @BeforeEach
    void setUp() {
        ownUserProfileWriter = new OwnUserProfileWriter(
                userRepository,
                outboxEventRepository,
                userEventMapper,
                jsonMapper,
                eventPublisher
        );
    }

    @Test
    void shouldWriteTheProfileAndTheOutboxRowInOneTransaction() throws NoSuchMethodException {
        var method = OwnUserProfileWriter.class.getMethod("updateProfile", User.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isFalse();
    }

    @Test
    void shouldSaveAUserUpdatedEventOutboxRowWithTheNewPictureUrlWhenThePictureChanges() {
        var userId = UUID.randomUUID();
        var requested = profile(userId, NEW_PICTURE, new UserBio("Same bio", Map.of()), null);
        var updated = profile(userId, NEW_PICTURE, new UserBio("Same bio", Map.of()), UPDATED_AT);
        givenTheRepositoryReturns(requested, updated);

        var result = ownUserProfileWriter.updateProfile(requested);

        var event = capturedUserUpdatedEvent();
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.username()).isEqualTo("profile-user");
        assertThat(event.email()).isEqualTo("profile-user@example.com");
        assertThat(event.pictureUrl()).isEqualTo(NEW_PICTURE);
        assertThat(event.accountStatus()).isEqualTo(UserAccountStatus.ACCEPTED.name());
        assertThat(event.createdAt()).isEqualTo(CREATED_AT);
        // Built from the user after the write, not from the one handed in
        assertThat(event.updatedAt()).isEqualTo(UPDATED_AT);

        var outboxEvent = capturedOutboxEvent();
        assertThat(outboxEvent.getEventType()).isEqualTo(UserUpdatedEvent.class.getSimpleName());
        assertThat(outboxEvent.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(outboxEvent.getPayload()).isEqualTo(PAYLOAD);
        assertThat(outboxEvent.getCorrelationId()).isNotNull().isEqualTo(event.correlationId());
        assertThat(result).isSameAs(updated);
    }

    @Test
    void shouldSaveAUserUpdatedEventWithPictureUrlNullWhenThePictureIsRemoved() {
        var userId = UUID.randomUUID();
        var requested = profile(userId, null, new UserBio("Same bio", Map.of()), null);
        givenTheRepositoryReturns(requested, profile(userId, null, new UserBio("Same bio", Map.of()), UPDATED_AT));

        ownUserProfileWriter.updateProfile(requested);

        assertThat(capturedUserUpdatedEvent().pictureUrl()).isNull();
    }

    @Test
    void shouldSaveAUserUpdatedEventWithTheNewBioWhenOnlyTheDescriptionChanges() {
        var userId = UUID.randomUUID();
        var newBio = new UserBio("Brand new bio", Map.of("github", "profile-user"));
        var requested = profile(userId, OLD_PICTURE, newBio, null);
        givenTheRepositoryReturns(requested, profile(userId, OLD_PICTURE, newBio, UPDATED_AT));

        ownUserProfileWriter.updateProfile(requested);

        var event = capturedUserUpdatedEvent();
        assertThat(event.bio()).isEqualTo(UserBioEventPayload.builder()
                .description("Brand new bio")
                .socialMedia(Map.of("github", "profile-user"))
                .build());
        assertThat(event.pictureUrl()).isEqualTo(OLD_PICTURE);
    }

    @Test
    void shouldPublishTheDomainEventThatTriggersTheOutboxRelayAfterSaving() {
        var userId = UUID.randomUUID();
        var requested = profile(userId, NEW_PICTURE, null, null);
        givenTheRepositoryReturns(requested, profile(userId, NEW_PICTURE, null, UPDATED_AT));

        ownUserProfileWriter.updateProfile(requested);

        var inOrder = inOrder(userRepository, outboxEventRepository, eventPublisher);
        inOrder.verify(userRepository).updateProfile(requested);
        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        inOrder.verify(outboxEventRepository).save(outboxCaptor.capture());
        var domainEventCaptor = ArgumentCaptor.forClass(UserProfileUpdatedDomainEvent.class);
        inOrder.verify(eventPublisher).publishEvent(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().id()).isEqualTo(outboxCaptor.getValue().getId());
        assertThat(domainEventCaptor.getValue().userId().value()).isEqualTo(userId);
        assertThat(domainEventCaptor.getValue().occurredOn()).isEqualTo(outboxCaptor.getValue().getCreatedAt());
    }

    @Test
    void shouldNotSaveAnyOutboxRowWhenTheProfileWriteFails() {
        var userId = UUID.randomUUID();
        var requested = profile(userId, NEW_PICTURE, null, null);
        when(userRepository.updateProfile(requested)).thenThrow(new UserNotFoundException(userId));

        assertThatThrownBy(() -> ownUserProfileWriter.updateProfile(requested))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(outboxEventRepository, jsonMapper, eventPublisher);
    }

    private void givenTheRepositoryReturns(User requested, User updated) {
        when(userRepository.updateProfile(requested)).thenReturn(updated);
        when(jsonMapper.toJson(any(UserUpdatedEvent.class))).thenReturn(PAYLOAD);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserUpdatedEvent capturedUserUpdatedEvent() {
        var eventCaptor = ArgumentCaptor.forClass(UserUpdatedEvent.class);
        verify(jsonMapper).toJson(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private OutboxEvent capturedOutboxEvent() {
        var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    private User profile(UUID userId, String pictureUrl, UserBio bio, Instant updatedAt) {
        var user = UserMother.accepted(userId, "profile-user");
        user.updateProfile(pictureUrl == null ? null : new ProfilePictureUrl(pictureUrl), bio);
        user.setCreatedAt(CREATED_AT);
        user.setUpdatedAt(updatedAt);
        return user;
    }
}
