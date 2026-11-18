package com.app.socialservice.user.application.dto;

public record UserStats(long followersCount, long followingCount, long postCount) {

    public static final UserStats EMPTY = new UserStats(0, 0, 0);
}
