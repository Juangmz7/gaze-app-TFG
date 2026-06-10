package com.app.socialservice.user.domain.model;

import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;

import java.time.Instant;
import java.util.UUID;

public class User {

    private UserId userId;
    private Username username;
    private Email email;
    private ProfilePictureUrl pictureUrl;
    private UserAccountStatus accountStatus;
    private Instant createdAt;
    private Instant updatedAt;

    public User(UserId userId, Username username, Email email) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.accountStatus = UserAccountStatus.ACCEPTED;
    }

    public void updateUser(User user) {}

    public UserId getId() {
        return this.userId;
    }
}
