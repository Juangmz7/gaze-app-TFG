package com.app.socialservice.block.infrastructure.mapper;

import com.app.socialservice.block.domain.model.Block;
import com.app.socialservice.block.infrastructure.events.UserBlockedEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface BlockEventMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "blockerUserId", source = "block.blockerId.value")
    @Mapping(target = "blockedUserId", source = "block.blockedId.value")
    UserBlockedEvent toUserBlockedEvent(UUID id, UUID correlationId, Block block, Instant occurredOn);
}
