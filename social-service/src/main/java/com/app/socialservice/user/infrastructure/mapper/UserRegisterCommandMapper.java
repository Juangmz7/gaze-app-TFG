package com.app.socialservice.user.infrastructure.mapper;

import com.app.socialservice.user.application.commands.UserRegisterCommand;
import com.app.socialservice.user.infrastructure.events.UserRegisteredFromAuthEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UserRegisterCommandMapper {

    @Mapping(target = "id", source = "commandId")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "userId", expression = "java(UUID.fromString(event.userId()))")
    @Mapping(target = "username", source = "event.details.username")
    @Mapping(target = "email", source = "event.details.email")
    @Mapping(target = "occurredOn", source = "event.time")
    UserRegisterCommand toCommand(
            UUID commandId,
            UUID correlationId,
            UserRegisteredFromAuthEvent event
    );

    default Instant map(Long epochMillis) {
        return epochMillis == null
                ? null
                : Instant.ofEpochMilli(epochMillis);
    }
}