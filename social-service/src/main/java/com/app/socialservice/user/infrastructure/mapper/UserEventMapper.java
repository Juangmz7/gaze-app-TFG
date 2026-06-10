package com.app.socialservice.user.infrastructure.mapper;

import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.infrastructure.events.UserRegisteredEvent;
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
}