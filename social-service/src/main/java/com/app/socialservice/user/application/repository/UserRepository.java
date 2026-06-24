package com.app.socialservice.user.application.repository;

import com.app.socialservice.user.domain.model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    boolean existsById(UUID id);
    boolean insertIfAbsent(User user);
    boolean updateAuthInfo(UUID id, String username, String email);
    boolean deleteAndObfuscate(UUID id);
}
