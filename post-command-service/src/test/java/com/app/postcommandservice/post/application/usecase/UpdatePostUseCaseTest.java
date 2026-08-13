package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.post.application.commands.UpdatePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostUpdatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.events.PostUpdatedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdatePostUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private TaggedUserValidationRepository taggedUserValidationRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostEventMapper postEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<Post> postCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private UpdatePostUseCase updatePostUseCase;

    @Test
    void shouldUpdatePostSuccessfullyAndPublishEventWhenValuesHaveChangedAndTaggedUsersAreValid() {
        var existingPost = persistedPost(OWNER_ID, "old", Set.of("alice"), Set.of("java"));
        var updatedPost = persistedPost(OWNER_ID, "new", Set.of("alice", "bob"), Set.of("spring"));
        var command = new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "new",
                new LinkedHashSet<>(Set.of("alice", "bob")),
                Set.of("spring")
        );
        var bobId = UUID.randomUUID();
        var updatedEvent = updatedEvent(updatedPost);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("bob"))).thenReturn(Map.of("bob", bobId));
        when(taggedUserValidationRepository.findBlockedUserIds(OWNER_ID, Set.of(bobId))).thenReturn(Set.of());
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(updatedPost);
        when(postEventMapper.toPostUpdatedEvent(any(UUID.class), any(UUID.class), eq(updatedPost), any(Instant.class)))
                .thenReturn(updatedEvent);
        when(jsonMapper.toJson(updatedEvent)).thenReturn("{\"event\":\"payload\"}");

        var response = updatePostUseCase.updatePost(command);

        assertThat(response.description()).isEqualTo("new");
        assertThat(response.taggedUsers()).containsExactlyInAnyOrder("alice", "bob");
        verify(taggedUserValidationRepository).findUserIdsByUsernames(Set.of("bob"));
        verify(taggedUserValidationRepository).findBlockedUserIds(OWNER_ID, Set.of(bobId));
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getDescription().value()).isEqualTo("new");
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostUpdatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostUpdatedDomainEvent.class));
    }

    @Test
    void shouldReturnExistingPostWithoutDbUpdatesOrEventsWhenNoFieldsAreActuallyChanged() {
        var existingPost = persistedPost(OWNER_ID, "same", Set.of("alice"), Set.of("java"));

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));

        var response = updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "same",
                Set.of("alice"),
                Set.of("java")
        ));

        assertThat(response.postId()).isEqualTo(existingPost.getId().value());
        assertThat(response.updatedAt()).isEqualTo(existingPost.getUpdatedAt());
        verify(postRepository, never()).saveAndFlush(any(Post.class));
        verify(taggedUserValidationRepository, never()).findUserIdsByUsernames(any(Set.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostUpdatedDomainEvent.class));
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenUpdaterIsNotThePostOwner() {
        var existingPost = persistedPost(UUID.randomUUID(), "same", Set.of(), Set.of());

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));

        assertThatThrownBy(() -> updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "new",
                Set.of(),
                Set.of()
        )))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(existingPost.getId().value().toString());
    }

    @Test
    void shouldThrowTaggedUserNotFoundExceptionWhenANewlyTaggedUserDoesNotExist() {
        var existingPost = persistedPost(OWNER_ID, "same", Set.of("alice"), Set.of());

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("bob"))).thenReturn(Map.of());

        assertThatThrownBy(() -> updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "same",
                new LinkedHashSet<>(Set.of("alice", "bob")),
                Set.of()
        )))
                .isInstanceOf(TaggedUserNotFoundException.class)
                .hasMessageContaining("bob");
    }

    @Test
    void shouldThrowTaggedUserBlockedExceptionWhenANewlyTaggedUserIsBlockedByOrHasBlockedTheOwner() {
        var existingPost = persistedPost(OWNER_ID, "same", Set.of("alice"), Set.of());
        var blockedUserId = UUID.randomUUID();

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("bob")))
                .thenReturn(Map.of("bob", blockedUserId));
        when(taggedUserValidationRepository.findBlockedUserIds(OWNER_ID, Set.of(blockedUserId)))
                .thenReturn(Set.of(blockedUserId));

        assertThatThrownBy(() -> updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "same",
                new LinkedHashSet<>(Set.of("alice", "bob")),
                Set.of()
        )))
                .isInstanceOf(TaggedUserBlockedException.class)
                .hasMessageContaining("bob");
    }

    private Post persistedPost(UUID ownerId, String description, Set<String> taggedUsers, Set<String> postTags) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                new PostDescription(description),
                new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                new PostTags(new LinkedHashSet<>(postTags)),
                PostStatus.ACTIVE,
                now,
                now
        );
    }

    private PostUpdatedEvent updatedEvent(Post post) {
        return PostUpdatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
