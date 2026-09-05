package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.post.application.commands.CheckPostCollabLinkStatusCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckPostCollabLinkStatusUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private CollabRepository collabRepository;

    @InjectMocks
    private CheckPostCollabLinkStatusUseCase checkPostCollabLinkStatusUseCase;

    @Test
    void shouldReturnFalseWhenPostBelongsToRequesterAndHasNoCollabId() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(OWNER_ID, null)));

        var result = checkPostCollabLinkStatusUseCase.check(new CheckPostCollabLinkStatusCommand(POST_ID, OWNER_ID));

        assertThat(result.linked()).isFalse();
        assertThat(result.collab()).isNull();
        verifyNoInteractions(collabRepository);
    }

    @Test
    void shouldReturnCollabBodyWhenPostBelongsToRequesterAndIsLinkedToACollab() {
        var collabId = UUID.randomUUID();
        var collab = persistedCollab(collabId);
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(OWNER_ID, collabId)));
        when(collabRepository.findById(collabId)).thenReturn(Optional.of(collab));

        var result = checkPostCollabLinkStatusUseCase.check(new CheckPostCollabLinkStatusCommand(POST_ID, OWNER_ID));

        assertThat(result.linked()).isTrue();
        assertThat(result.collab()).isNotNull();
        assertThat(result.collab().collabId()).isEqualTo(collabId);
        assertThat(result.collab().title()).isEqualTo("Collab");
        assertThat(result.collab().createdBy()).isEqualTo(collab.getCreatedBy().value());
        assertThat(result.collab().collabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(result.collab().createdAt()).isEqualTo(collab.getCreatedAt());
    }

    @Test
    void shouldReturnFalseWhenPostReferencesMissingCollab() {
        var collabId = UUID.randomUUID();
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(OWNER_ID, collabId)));
        when(collabRepository.findById(collabId)).thenReturn(Optional.empty());

        var result = checkPostCollabLinkStatusUseCase.check(new CheckPostCollabLinkStatusCommand(POST_ID, OWNER_ID));

        assertThat(result.linked()).isFalse();
        assertThat(result.collab()).isNull();
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenRequesterIsNotThePostCreator() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(UUID.randomUUID(), null)));

        assertThatThrownBy(() -> checkPostCollabLinkStatusUseCase.check(
                new CheckPostCollabLinkStatusCommand(POST_ID, OWNER_ID)))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenTargetPostDoesNotExist() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkPostCollabLinkStatusUseCase.check(
                new CheckPostCollabLinkStatusCommand(POST_ID, OWNER_ID)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    private Post persistedPost(UUID ownerId, UUID collabId) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                collabId,
                collabId == null ? PostType.BASIC : PostType.COLAB,
                new PostDescription("description"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice"))),
                new PostTags(new LinkedHashSet<>(Set.of("java"))),
                PostStatus.ACTIVE,
                now,
                now
        );
    }

    private Collab persistedCollab(UUID collabId) {
        return new Collab(
                collabId,
                new CollabTitle("Collab"),
                new UserId(OWNER_ID),
                ColabStatus.OPEN,
                Instant.now()
        );
    }
}
