package com.app.postcommandservice.view.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.repository.BlockReadModelJpaRepository;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.view.application.repository.PostViewValidationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostViewValidationRepositoryImplTest {

    @Mock
    private PostJpaRepository postJpaRepository;

    @Mock
    private BlockReadModelJpaRepository blockReadModelJpaRepository;

    private PostViewValidationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PostViewValidationRepositoryImpl(postJpaRepository, blockReadModelJpaRepository);
    }

    @Test
    void shouldReturnActivePostOwnerWhenPostIsActive() {
        var postId = UUID.randomUUID();
        var ownerId = UUID.randomUUID();
        when(postJpaRepository.findOwnerIdByIdAndStatus(postId, PostStatus.ACTIVE)).thenReturn(Optional.of(ownerId));

        var activePost = repository.findActivePost(postId);

        assertThat(activePost).contains(new PostViewValidationRepository.ActivePost(postId, ownerId));
    }

    @Test
    void shouldReturnEmptyWhenPostIsMissingOrInactive() {
        var postId = UUID.randomUUID();
        when(postJpaRepository.findOwnerIdByIdAndStatus(postId, PostStatus.ACTIVE)).thenReturn(Optional.empty());

        var activePost = repository.findActivePost(postId);

        assertThat(activePost).isEmpty();
    }

    @Test
    void shouldReturnTrueWhenUsersAreBlockedInEitherDirection() {
        var viewerId = UUID.randomUUID();
        var ownerId = UUID.randomUUID();
        when(blockReadModelJpaRepository.findBlockedUserIdsBetween(viewerId, Set.of(ownerId)))
                .thenReturn(List.of(ownerId));

        var blocked = repository.existsBlockRelationship(viewerId, ownerId);

        assertThat(blocked).isTrue();
        verify(blockReadModelJpaRepository).findBlockedUserIdsBetween(viewerId, Set.of(ownerId));
    }

    @Test
    void shouldReturnFalseWhenNoBlockRelationshipExists() {
        var viewerId = UUID.randomUUID();
        var ownerId = UUID.randomUUID();
        when(blockReadModelJpaRepository.findBlockedUserIdsBetween(viewerId, Set.of(ownerId)))
                .thenReturn(List.of());

        var blocked = repository.existsBlockRelationship(viewerId, ownerId);

        assertThat(blocked).isFalse();
    }
}
