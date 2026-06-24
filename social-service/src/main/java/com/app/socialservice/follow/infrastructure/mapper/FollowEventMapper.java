package com.app.socialservice.follow.infrastructure.mapper;

import java.time.Instant;
import java.util.UUID;

import com.app.socialservice.follow.domain.model.Follow;
import com.app.socialservice.follow.infrastructure.events.UserFollowedEvent;
import com.app.socialservice.follow.infrastructure.events.UserUnfollowedEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FollowEventMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "followerUserId", source = "follow.followerId.value")
    @Mapping(target = "followedUserId", source = "follow.followedId.value")
    UserFollowedEvent toUserFollowedEvent(UUID id, UUID correlationId, Follow follow, Instant occurredOn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "correlationId", source = "correlationId")
    @Mapping(target = "occurredAt", source = "occurredOn")
    @Mapping(target = "followerUserId", source = "follow.followerId.value")
    @Mapping(target = "followedUserId", source = "follow.followedId.value")
    UserUnfollowedEvent toUserUnfollowedEvent(UUID id, UUID correlationId, Follow follow, Instant occurredOn);
}
