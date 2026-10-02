package com.app.postcommandservice.collab.application.usecase;

import java.time.Instant;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.app.postcommandservice.collab.application.commands.OpenCollabAndCreatePostCommand;
import com.app.postcommandservice.collab.application.dto.OpenCollabAndCreatePostResponse;
import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.application.repository.CollabRequestIdempotencyRepository;
import com.app.postcommandservice.collab.domain.events.CollabOpenedDomainEvent;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabOpenedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.usecase.CreatePostUseCase;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenCollabAndCreatePostUseCaseTest {

    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private CollabRepository collabRepository;

    @Mock
    private CollabMemberRepository collabMemberRepository;

    @Mock
    private CollabRequestIdempotencyRepository collabRequestIdempotencyRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private CreatePostUseCase createPostUseCase;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private CollabEventMapper collabEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<CreatePostCommand> createPostCommandCaptor;

    @InjectMocks
    private OpenCollabAndCreatePostUseCase openCollabAndCreatePostUseCase;

    @Test
    void shouldCreateCollabCollabMemberPostAndPublishCollabOpenedEventForNewValidRequest() {
        var collabId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var savedCollab = new Collab(
                collabId,
                new CollabTitle("New collab"),
                new UserId(USER_ID),
                ColabStatus.OPEN,
                Instant.now()
        );
        var savedMember = new CollabMember(
                collabId,
                new UserId(USER_ID),
                CollabMemberStatus.ACCEPTED,
                CollabMemberRole.ADMIN,
                Instant.now()
        );
        var savedPost = persistedCollabPost(postId, collabId);
        var signedPostResponse = new PostResponse(
                postId,
                USER_ID,
                collabId,
                PostType.COLLAB,
                "hello",
                Set.of("spring"),
                List.of(),
                PostStatus.PENDING,
                Instant.now().plusSeconds(600),
                savedPost.getCreatedAt(),
                savedPost.getUpdatedAt()
        );
        var event = CollabOpenedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .collabId(collabId)
                .title("New collab")
                .createdBy(USER_ID)
                .collabStatus(ColabStatus.OPEN)
                .collabCreatedAt(savedCollab.getCreatedAt())
                .creatorMemberStatus(CollabMemberStatus.ACCEPTED)
                .creatorRole(CollabMemberRole.ADMIN)
                .creatorMemberCreatedAt(savedMember.getCreatedAt())
                .postId(postId)
                .userId(USER_ID)
                .postCollabId(collabId)
                .postType(PostType.COLLAB)
                .description("hello")
                .postTags(Set.of("spring"))
                .postCreatedAt(savedPost.getCreatedAt())
                .postUpdatedAt(savedPost.getUpdatedAt())
                .build();

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(collabRepository.save(any(Collab.class))).thenReturn(savedCollab);
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(savedMember);
        when(createPostUseCase.createPendingPost(any(CreatePostCommand.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabOpenedEvent(any(UUID.class), eq(CORRELATION_ID), eq(savedCollab), eq(savedMember),
                eq(savedPost), eq(true), any(Instant.class))).thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");
        when(createPostUseCase.signUploadUrls(savedPost)).thenReturn(signedPostResponse);

        var response = openCollabAndCreatePostUseCase.open(new OpenCollabAndCreatePostCommand(
                CORRELATION_ID,
                USER_ID,
                "New collab",
                "hello",
                Set.of("spring"),
                defaultMedia()
        ));

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.collabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(response.post().postId()).isEqualTo(postId);
        assertThat(response.post().postType()).isEqualTo(PostType.COLLAB);
        assertThat(response.post().status()).isEqualTo(PostStatus.PENDING);
        assertThat(response.post().uploadExpiresAt()).isNotNull();

        verify(createPostUseCase).createPendingPost(createPostCommandCaptor.capture());
        assertThat(createPostCommandCaptor.getValue().collabId()).isEqualTo(collabId);
        assertThat(createPostCommandCaptor.getValue().postType()).isEqualTo(PostType.COLLAB);

        verify(createPostUseCase).signUploadUrls(savedPost);
        verify(collabRequestIdempotencyRepository).save(CORRELATION_ID, postId);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabOpenedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabOpenedDomainEvent.class));
    }

    @Test
    void shouldDelegatePostCreationFromCollabFlowToTheSharedPostCreationUseCase() {
        var collabId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var savedCollab = new Collab(collabId, new CollabTitle("New collab"), new UserId(USER_ID), ColabStatus.OPEN, Instant.now());
        var savedMember = new CollabMember(collabId, new UserId(USER_ID), CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN,
                Instant.now());
        var savedPost = persistedCollabPost(postId, collabId);
        var signedPostResponse = new PostResponse(postId, USER_ID, collabId, PostType.COLLAB, "hello", Set.of("spring"),
                List.of(), PostStatus.PENDING, Instant.now().plusSeconds(600), savedPost.getCreatedAt(), savedPost.getUpdatedAt());

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(collabRepository.save(any(Collab.class))).thenReturn(savedCollab);
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(savedMember);
        when(createPostUseCase.createPendingPost(any(CreatePostCommand.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabOpenedEvent(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(CollabOpenedEvent.builder().id(UUID.randomUUID()).correlationId(CORRELATION_ID).build());
        when(jsonMapper.toJson(any(CollabOpenedEvent.class))).thenReturn("{}");
        when(createPostUseCase.signUploadUrls(savedPost)).thenReturn(signedPostResponse);

        openCollabAndCreatePostUseCase.open(new OpenCollabAndCreatePostCommand(
                CORRELATION_ID, USER_ID, "New collab", "hello", Set.of("spring"), defaultMedia()));

        // Delegation to the shared task-33 entry point: no bespoke validation, url-generation,
        // or SAS-signing logic lives in the collab use case itself.
        verify(createPostUseCase).createPendingPost(any(CreatePostCommand.class));
        verify(createPostUseCase).signUploadUrls(savedPost);
        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void shouldCreateCollabPostWithCollabIdPostTypeColabAndPendingStatus() {
        var collabId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var savedCollab = new Collab(collabId, new CollabTitle("New collab"), new UserId(USER_ID), ColabStatus.OPEN, Instant.now());
        var savedMember = new CollabMember(collabId, new UserId(USER_ID), CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN,
                Instant.now());
        var savedPost = persistedCollabPost(postId, collabId);
        var signedPostResponse = new PostResponse(postId, USER_ID, collabId, PostType.COLLAB, "hello", Set.of("spring"),
                List.of(), PostStatus.PENDING, Instant.now().plusSeconds(600), savedPost.getCreatedAt(), savedPost.getUpdatedAt());

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(collabRepository.save(any(Collab.class))).thenReturn(savedCollab);
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(savedMember);
        when(createPostUseCase.createPendingPost(any(CreatePostCommand.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabOpenedEvent(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(CollabOpenedEvent.builder().id(UUID.randomUUID()).correlationId(CORRELATION_ID).build());
        when(jsonMapper.toJson(any(CollabOpenedEvent.class))).thenReturn("{}");
        when(createPostUseCase.signUploadUrls(savedPost)).thenReturn(signedPostResponse);

        var response = openCollabAndCreatePostUseCase.open(new OpenCollabAndCreatePostCommand(
                CORRELATION_ID, USER_ID, "New collab", "hello", Set.of("spring"), defaultMedia()));

        verify(createPostUseCase).createPendingPost(createPostCommandCaptor.capture());
        var forwardedCommand = createPostCommandCaptor.getValue();
        assertThat(forwardedCommand.collabId()).isEqualTo(collabId);
        assertThat(forwardedCommand.postType()).isEqualTo(PostType.COLLAB);

        assertThat(response.post().postType()).isEqualTo(PostType.COLLAB);
        assertThat(response.post().collabId()).isEqualTo(collabId);
        assertThat(response.post().status()).isEqualTo(PostStatus.PENDING);
    }

    @Test
    void shouldReturnExistingDataWithoutSideEffectsWhenCorrelationIdAlreadyExists() {
        var collabId = UUID.randomUUID();
        var postId = UUID.randomUUID();
        var existingPost = persistedCollabPost(postId, collabId);
        var existingCollab = new Collab(
                collabId,
                new CollabTitle("Existing"),
                new UserId(USER_ID),
                ColabStatus.OPEN,
                Instant.now()
        );
        var signedPostResponse = new PostResponse(
                postId,
                USER_ID,
                collabId,
                PostType.COLLAB,
                "hello",
                Set.of("spring"),
                List.of(),
                PostStatus.PENDING,
                Instant.now().plusSeconds(600),
                existingPost.getCreatedAt(),
                existingPost.getUpdatedAt()
        );

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.of(postId));
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(collabId)).thenReturn(Optional.of(existingCollab));
        when(createPostUseCase.signUploadUrls(existingPost)).thenReturn(signedPostResponse);

        OpenCollabAndCreatePostResponse response = openCollabAndCreatePostUseCase.open(new OpenCollabAndCreatePostCommand(
                CORRELATION_ID,
                USER_ID,
                "ignored",
                "ignored",
                Set.of(),
                defaultMedia()
        ));

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.post().postId()).isEqualTo(postId);
        verify(collabRepository, never()).save(any(Collab.class));
        verify(collabMemberRepository, never()).save(any(CollabMember.class));
        verify(createPostUseCase, never()).createPendingPost(any(CreatePostCommand.class));
        verify(createPostUseCase).signUploadUrls(existingPost);
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private List<PostMediaCommand> defaultMedia() {
        return List.of(new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1));
    }

    private Post persistedCollabPost(UUID postId, UUID collabId) {
        var now = Instant.now();
        return new Post(
                new PostId(postId),
                new UserId(USER_ID),
                collabId,
                new PostInfo(
                        new PostDescription("hello"),
                        new PostTags(Set.of("spring")),
                        PostType.COLLAB
                ),
                List.of(PostMedia.create(postId, "https://cdn/image.jpg", "https://cdn/image.jpg", MediaType.IMAGE, null,
                        Set.of("alice"), 1)),
                PostStatus.PENDING,
                now,
                now
        );
    }
}
