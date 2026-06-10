package com.app.socialservice.user.application.repository;

import com.app.socialservice.user.domain.model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    User save(User user);
    Optional<User> findById(UUID id);
    boolean existsById(UUID id);
}
