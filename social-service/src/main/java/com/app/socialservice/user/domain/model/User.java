package com.app.socialservice.user.domain.model;

import com.app.socialservice.user.domain.enums.UserAccountStatus;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;

import java.time.Instant;

public class User {

    private UserId id;
    private Username username;
    private Email email;
    private ProfilePictureUrl pictureUrl;
    private UserAccountStatus accountStatus;
    private Instant createdAt;
    private Instant updatedAt;

    public User(UserId id, Username username, Email email) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.accountStatus = UserAccountStatus.ACCEPTED;
    }

    public void updateUser(User user) {}

    public UserId getId() {
        return this.id;
    }

    public Username getUsername() {
        return this.username;
    }

    public Email getEmail() {
        return this.email;
    }

    public ProfilePictureUrl getPictureUrl() {
        return pictureUrl;
    }

    public UserAccountStatus getAccountStatus() {
        return accountStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setPictureUrl(ProfilePictureUrl pictureUrl) {
        this.pictureUrl = pictureUrl;
    }

    public void setAccountStatus(UserAccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
