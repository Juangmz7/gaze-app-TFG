package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.infrastructure.entity.UserReadModelEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaggedUserValidationRepositoryImplTest {

    @Mock
    private UserReadModelJpaRepository userReadModelJpaRepository;

    @Mock
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    private TaggedUserValidationRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new TaggedUserValidationRepositoryImpl(userReadModelJpaRepository, blockReadModelJpaRepository);
    }

    @Test
    void shouldReturnUserIdsByUsernameAndBlockedUsersInBothDirections() {
        var creatorId = UUID.randomUUID();
        var aliceId = UUID.randomUUID();
        var bobId = UUID.randomUUID();
        var charlieId = UUID.randomUUID();
        var now = Instant.now();
        var requestedUsernames = new LinkedHashSet<>(Set.of("alice", "bob", "missing"));

        when(userReadModelJpaRepository.findByUsernameIn(requestedUsernames)).thenReturn(List.of(
                new UserReadModelEntity(aliceId, "alice", now, now),
                new UserReadModelEntity(bobId, "bob", now, now)
        ));
        when(blockReadModelJpaRepository.findBlockedUserIdsBetween(creatorId, Set.of(aliceId, bobId, charlieId)))
                .thenReturn(List.of(aliceId, bobId));

        var userIdsByUsername = repository.findUserIdsByUsernames(requestedUsernames);
        var blockedUserIds = repository.findBlockedUserIds(creatorId, Set.of(aliceId, bobId, charlieId));

        assertThat(userIdsByUsername).containsEntry("alice", aliceId);
        assertThat(userIdsByUsername).containsEntry("bob", bobId);
        assertThat(userIdsByUsername).doesNotContainKey("missing");
        assertThat(blockedUserIds).containsExactlyInAnyOrder(aliceId, bobId);
    }

    @Test
    void shouldReturnEmptyBlockedUsersWithoutQueryingDatabaseWhenTaggedSetIsEmpty() {
        var blockedUserIds = repository.findBlockedUserIds(UUID.randomUUID(), Set.of());

        assertThat(blockedUserIds).isEmpty();
        verifyNoInteractions(blockReadModelJpaRepository);
    }
}
