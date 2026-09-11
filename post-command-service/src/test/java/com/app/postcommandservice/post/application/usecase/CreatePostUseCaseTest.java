package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostCreatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.events.PostCreatedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePostUseCaseTest {

    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostRequestIdempotencyRepository postRequestIdempotencyRepository;

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
    private CreatePostUseCase createPostUseCase;

    @Test
    void shouldCreatePostSuccessfullyWhenDescriptionIsBlankAndNoUsersAreTagged() {
        var command = command("", Set.of(), Set.of("java"));
        var persistedPost = persistedPost("", Set.of(), Set.of("java"));
        var createdEvent = createdEvent(persistedPost);

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);
        when(postEventMapper.toPostCreatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(createdEvent);
        when(jsonMapper.toJson(createdEvent)).thenReturn("{\"event\":\"payload\"}");

        var response = createPostUseCase.createPost(command);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.collabId()).isNull();
        assertThat(response.postType()).isEqualTo(PostType.BASIC);
        assertThat(response.description()).isEmpty();
        assertThat(response.taggedUsers()).isEmpty();
        assertThat(response.postTags()).containsExactly("java");

        verify(postRepository).save(postCaptor.capture());
        assertThat(postCaptor.getValue().getStatus()).isEqualTo(PostStatus.ACTIVE);

        verify(postRequestIdempotencyRepository).save(CORRELATION_ID, persistedPost.getId().value());
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostCreatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostCreatedDomainEvent.class));
    }

    @Test
    void shouldAcquireCorrelationLockBeforeCheckingExistingIdempotencyRecord() {
        var command = command("", Set.of(), Set.of("java"));
        var persistedPost = persistedPost("", Set.of(), Set.of("java"));
        var createdEvent = createdEvent(persistedPost);

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);
        when(postEventMapper.toPostCreatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(createdEvent);
        when(jsonMapper.toJson(createdEvent)).thenReturn("{\"event\":\"payload\"}");

        createPostUseCase.createPost(command);

        InOrder inOrder = inOrder(postRequestIdempotencyRepository);
        inOrder.verify(postRequestIdempotencyRepository).acquireCorrelationLock(CORRELATION_ID);
        inOrder.verify(postRequestIdempotencyRepository).findPostIdByCorrelationId(CORRELATION_ID);
    }

    @Test
    void shouldCreatePostSuccessfullyWhenTaggedUsersExistAndAreNotBlocked() {
        var command = command("hello", new LinkedHashSet<>(Set.of("alice", "bob")), Set.of("spring", "rabbit"));
        var persistedPost = persistedPost("hello", command.taggedUsers(), command.postTags());
        var createdEvent = createdEvent(persistedPost);
        var usersByUsername = Map.of("alice", UUID.randomUUID(), "bob", UUID.randomUUID());

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(command.taggedUsers())).thenReturn(usersByUsername);
        when(taggedUserValidationRepository.findBlockedUserIds(USER_ID, Set.copyOf(usersByUsername.values())))
                .thenReturn(Set.of());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);
        when(postEventMapper.toPostCreatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(createdEvent);
        when(jsonMapper.toJson(createdEvent)).thenReturn("{\"event\":\"payload\"}");

        var response = createPostUseCase.createPost(command);

        assertThat(response.taggedUsers()).containsExactlyInAnyOrder("alice", "bob");
        verify(taggedUserValidationRepository).findUserIdsByUsernames(command.taggedUsers());
        verify(taggedUserValidationRepository).findBlockedUserIds(USER_ID, Set.copyOf(usersByUsername.values()));
    }

    @Test
    void shouldReturnPreviouslyCreatedPostWithoutSideEffectsWhenCorrelationIdAlreadyExists() {
        var existingPost = persistedPost("existing", Set.of("alice"), Set.of("java"));

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(existingPost.getId().value()));
        when(postRepository.findById(existingPost.getId().value())).thenReturn(Optional.of(existingPost));

        var response = createPostUseCase.createPost(command("new value", Set.of("bob"), Set.of("spring")));

        assertThat(response.postId()).isEqualTo(existingPost.getId().value());
        assertThat(response.description()).isEqualTo("existing");
        verify(postRepository, never()).save(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
    }

    @Test
    void shouldThrowTaggedUserNotFoundExceptionWhenTaggedUserDoesNotExist() {
        var command = command("description", Set.of("missing"), Set.of());

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(command.taggedUsers())).thenReturn(Map.of());

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(TaggedUserNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void shouldThrowTaggedUserBlockedExceptionWhenTaggedUserHasBlockedThePostOwnerOrViceVersa() {
        var blockedUserId = UUID.randomUUID();
        var command = command("description", Set.of("alice"), Set.of());

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(command.taggedUsers()))
                .thenReturn(Map.of("alice", blockedUserId));
        when(taggedUserValidationRepository.findBlockedUserIds(USER_ID, Set.of(blockedUserId)))
                .thenReturn(Set.of(blockedUserId));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(TaggedUserBlockedException.class)
                .hasMessageContaining("alice");
    }

    @Test
    void shouldCreateCollabPostWithoutPublishingPostCreatedEventWhenFlowRequestsSuppression() {
        var collabId = UUID.randomUUID();
        var command = new CreatePostCommand(
                CORRELATION_ID,
                USER_ID,
                collabId,
                PostType.COLAB,
                "hello",
                Set.of(),
                Set.of("spring"),
                null,
                media()
        );
        var persistedPost = new Post(
                new PostId(UUID.randomUUID()),
                new UserId(USER_ID),
                collabId,
                new PostInfo(null, new PostDescription("hello"), new PostTaggedUsers(Set.of()),
                        new PostTags(Set.of("spring")), PostType.COLAB),
                media(),
                PostStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);

        var response = createPostUseCase.createPost(command, false);

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.postType()).isEqualTo(PostType.COLAB);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
    }

    @Test
    void shouldCreateCollabPostWithoutPersistingPostRequestIdempotencyWhenFlowDisablesIt() {
        var collabId = UUID.randomUUID();
        var command = new CreatePostCommand(
                CORRELATION_ID,
                USER_ID,
                collabId,
                PostType.COLAB,
                "hello",
                Set.of(),
                Set.of("spring"),
                null,
                media()
        );
        var persistedPost = new Post(
                new PostId(UUID.randomUUID()),
                new UserId(USER_ID),
                collabId,
                new PostInfo(null, new PostDescription("hello"), new PostTaggedUsers(Set.of()),
                        new PostTags(Set.of("spring")), PostType.COLAB),
                media(),
                PostStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );

        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);

        var response = createPostUseCase.createPost(command, false, false);

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.postType()).isEqualTo(PostType.COLAB);
        verify(postRequestIdempotencyRepository, never()).acquireCorrelationLock(any(UUID.class));
        verify(postRequestIdempotencyRepository, never()).findPostIdByCorrelationId(any(UUID.class));
        verify(postRequestIdempotencyRepository, never()).save(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
    }

    private Post persistedPost(String description, Set<String> taggedUsers, Set<String> postTags) {
        var now = Instant.now();
        return new Post(
                new PostId(UUID.randomUUID()),
                new UserId(USER_ID),
                null,
                new PostInfo(null, new PostDescription(description), new PostTaggedUsers(new LinkedHashSet<>(taggedUsers)),
                        new PostTags(new LinkedHashSet<>(postTags)), PostType.BASIC),
                media(),
                PostStatus.ACTIVE,
                now,
                now
        );
    }

    private CreatePostCommand command(String description, Set<String> taggedUsers, Set<String> postTags) {
        return new CreatePostCommand(CORRELATION_ID, USER_ID, null, PostType.BASIC, description,
                taggedUsers, postTags, null, media());
    }

    private List<PostMedia> media() {
        return List.of(new PostMedia(UUID.randomUUID(), "https://cdn.test/post.jpg", null, MediaType.IMAGE, null, 1));
    }

    private PostCreatedEvent createdEvent(Post post) {
        return PostCreatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
