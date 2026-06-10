package com.app.socialservice.follow.domain.model;

import com.app.socialservice.user.domain.model.valueobj.UserId;

import java.time.Instant;

public class Follow {

    private UserId followerId;
    private UserId followedId;
    private Instant createdAt;

    public Follow(UserId followerId, UserId followedId) {
        this.followerId = followerId;
        this.followedId = followedId;
    }
}
