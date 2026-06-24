package com.app.socialservice.user.infrastructure.mapper;

import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
import com.app.socialservice.user.infrastructure.events.UserUpdatedEvent;
import com.app.socialservice.user.infrastructure.events.UserDeletedEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;


@Mapper(componentModel = "spring")
public interface UserEventMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "userId", source = "user.id.value")
    @Mapping(target = "username", source = "user.username.value")
    @Mapping(target = "email", source = "user.email.value")
    UserRegisteredEvent toUserRegisteredEvent(UUID id, UUID correlationId, User user, Instant occurredOn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "userId", source = "user.id.value")
    @Mapping(target = "username", source = "user.username.value")
    @Mapping(target = "email", source = "user.email.value")
    @Mapping(target = "pictureUrl", expression = "java(user.getPictureUrl() != null ? user.getPictureUrl().value() : null)")
    @Mapping(target = "accountStatus", expression = "java(user.getAccountStatus() != null ? user.getAccountStatus().name() : null)")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "updatedAt", source = "user.updatedAt")
    UserUpdatedEvent toUserUpdated(UUID id, UUID correlationId, User user, Instant occurredOn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "userId", source = "userId")
    UserDeletedEvent toUserDeleted(UUID id, UUID correlationId, UUID userId, Instant occurredOn);
}