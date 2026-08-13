package com.app.postcommandservice.post.infrastructure.repository;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;

@Repository
@RequiredArgsConstructor
public class TaggedUserValidationRepositoryImpl implements TaggedUserValidationRepository {

    private final UserReadModelJpaRepository userReadModelJpaRepository;
    private final BlockReadModelJpaRepository blockReadModelJpaRepository;

    @Override
    public java.util.Map<String, UUID> findUserIdsByUsernames(Set<String> usernames) {
        return userReadModelJpaRepository.findByUsernameIn(usernames).stream()
                .collect(Collectors.toMap(
                        user -> user.getUsername(),
                        user -> user.getId(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    @Override
    public Set<UUID> findBlockedUserIds(UUID userId, Set<UUID> taggedUserIds) {
        if (taggedUserIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(blockReadModelJpaRepository.findBlockedUserIdsBetween(userId, taggedUserIds));
    }
}
