package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.application.port.GeneratedMediaUrls;
import com.app.postcommandservice.post.application.port.MediaUploadUrlSigner;
import com.app.postcommandservice.post.application.port.MediaUrlGenerator;
import com.app.postcommandservice.post.application.port.SignedUploadUrl;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostCreatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.exception.InvalidTaggedUsernameException;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.exception.TooManyTaggedUsersException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private MediaUrlGenerator mediaUrlGenerator;

    @Mock
    private MediaUploadUrlSigner mediaUploadUrlSigner;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final PostMediaProperties postMediaProperties = new PostMediaProperties();

    @Captor
    private ArgumentCaptor<Post> postCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private CreatePostUseCase createPostUseCase;

    private void initUseCase() {
        createPostUseCase = new CreatePostUseCase(
                postRepository,
                postRequestIdempotencyRepository,
                taggedUserValidationRepository,
                outboxEventRepository,
                postEventMapper,
                jsonMapper,
                applicationEventPublisher,
                mediaUrlGenerator,
                mediaUploadUrlSigner,
                postMediaProperties,
                transactionManager
        );
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        initUseCase();
    }

    // -----------------------------------------------------------------
    // New metadata-only single-post creation flow (task 33), 1-arg createPost(command)
    // -----------------------------------------------------------------

    @Test
    void shouldRejectRequestWithEmptyMediaList() {
        var command = metadataCommand(List.of());

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(InvalidPostMediaException.class);

        verifyNoInteractions(postRepository, mediaUrlGenerator, mediaUploadUrlSigner);
    }

    @Test
    void shouldRejectRequestWithDuplicateMediaOrders() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1),
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(InvalidPostMediaException.class);

        verifyNoInteractions(postRepository);
    }

    @Test
    void shouldRejectRequestWithNonContiguousMediaOrders() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1),
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 3)
        ));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(InvalidPostMediaException.class);

        verifyNoInteractions(postRepository);
    }

    @Test
    void shouldRejectRequestWithNullMediaType() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, null, null, Set.of(), 1)
        ));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(InvalidPostMediaException.class);

        verifyNoInteractions(postRepository);
    }

    @Test
    void shouldRejectInvalidTaggedUsername() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of("   "), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(InvalidTaggedUsernameException.class);

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void shouldRejectTooManyTaggedUsersOnASingleMedia() {
        postMediaProperties.setMaxTaggedUsers(1);
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of("alice", "bob"), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(TooManyTaggedUsersException.class);

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void shouldCreatePostInPendingStatusWithNullDurations() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenReturn(new SignedUploadUrl("https://blob/a?sas", Instant.now().plusSeconds(900)));

        var response = createPostUseCase.createPost(command).response();

        assertThat(response.status()).isEqualTo(PostStatus.PENDING);
        assertThat(response.media()).hasSize(1);
        assertThat(response.media().get(0).duration()).isNull();

        verify(postRepository).save(postCaptor.capture());
        assertThat(postCaptor.getValue().getStatus()).isEqualTo(PostStatus.PENDING);
        assertThat(postCaptor.getValue().getMedia().get(0).getDuration()).isNull();
    }

    @Test
    void shouldAssignGeneratedUrlAndThumbnailUrlPerMediaType() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1),
                new PostMediaCommand(null, null, MediaType.VIDEO, null, Set.of(), 2)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/image", "https://blob/image"));
        when(mediaUrlGenerator.generate(MediaType.VIDEO)).thenReturn(new GeneratedMediaUrls("https://blob/video", "https://blob/video-thumb"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenAnswer(invocation -> new SignedUploadUrl(invocation.getArgument(0) + "?sas", Instant.now().plusSeconds(900)));

        var response = createPostUseCase.createPost(command).response();

        var imageMedia = response.media().stream().filter(m -> m.order() == 1).findFirst().orElseThrow();
        var videoMedia = response.media().stream().filter(m -> m.order() == 2).findFirst().orElseThrow();

        assertThat(imageMedia.url()).isEqualTo("https://blob/image");
        assertThat(imageMedia.thumbnailUrl()).isEqualTo("https://blob/image");
        assertThat(imageMedia.uploadUrl()).isEqualTo("https://blob/image?sas");
        assertThat(imageMedia.thumbnailUploadUrl()).isNull();

        assertThat(videoMedia.url()).isEqualTo("https://blob/video");
        assertThat(videoMedia.thumbnailUrl()).isEqualTo("https://blob/video-thumb");
        assertThat(videoMedia.uploadUrl()).isEqualTo("https://blob/video?sas");
        assertThat(videoMedia.thumbnailUploadUrl()).isEqualTo("https://blob/video-thumb?sas");
    }

    @Test
    void shouldMapCreatedPostAndMediaWithIdsToResponseWithoutDuration() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of("alice"), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("alice")))
                .thenReturn(Map.of("alice", UUID.randomUUID()));
        when(taggedUserValidationRepository.findBlockedUserIds(eq(USER_ID), any())).thenReturn(Set.of());
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenReturn(new SignedUploadUrl("https://blob/a?sas", Instant.now().plusSeconds(900)));

        var response = createPostUseCase.createPost(command).response();

        assertThat(response.media().get(0).id()).isNotNull();
        assertThat(response.media().get(0).taggedUsers()).containsExactly("alice");
        assertThat(response.media().get(0).duration()).isNull();
    }

    @Test
    void shouldNotPublishPostCreatedEventOnCreation() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenReturn(new SignedUploadUrl("https://blob/a?sas", Instant.now().plusSeconds(900)));

        createPostUseCase.createPost(command);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
        verifyNoInteractions(postEventMapper);
    }

    @Test
    void shouldReturnSameUrlAndThumbnailUrlOnIdempotentRetryWithFreshSasUrls() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        var existingPost = pendingPost("https://blob/existing", "https://blob/existing", MediaType.IMAGE);
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(existingPost.getId().value()));
        when(postRepository.findById(existingPost.getId().value())).thenReturn(Optional.of(existingPost));
        when(mediaUploadUrlSigner.sign(eq("https://blob/existing"), any(Instant.class)))
                .thenReturn(new SignedUploadUrl("https://blob/existing?sas-fresh", Instant.now().plusSeconds(900)));

        var response = createPostUseCase.createPost(command).response();

        assertThat(response.media().get(0).url()).isEqualTo("https://blob/existing");
        assertThat(response.media().get(0).thumbnailUrl()).isEqualTo("https://blob/existing");
        assertThat(response.media().get(0).uploadUrl()).isEqualTo("https://blob/existing?sas-fresh");

        verify(postRepository, never()).save(any(Post.class));
        verify(mediaUrlGenerator, never()).generate(any(MediaType.class));
        verify(mediaUploadUrlSigner).sign(eq("https://blob/existing"), any(Instant.class));
    }

    @Test
    void shouldRefreshSasUrlsOnIdempotentRetryEvenIfOriginalSasHadExpired() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        var existingPost = pendingPost("https://blob/existing", "https://blob/existing", MediaType.IMAGE);
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(existingPost.getId().value()));
        when(postRepository.findById(existingPost.getId().value())).thenReturn(Optional.of(existingPost));
        // Simulate that the signer always issues a brand-new (never previously cached) SAS,
        // proving the use case never reuses a stale SAS on replay.
        when(mediaUploadUrlSigner.sign(eq("https://blob/existing"), any(Instant.class)))
                .thenAnswer(invocation -> new SignedUploadUrl(
                        "https://blob/existing?sas-" + UUID.randomUUID(), Instant.now().plusSeconds(900)));

        var firstResponse = createPostUseCase.createPost(command).response();
        var secondResponse = createPostUseCase.createPost(command).response();

        assertThat(firstResponse.media().get(0).uploadUrl()).isNotEqualTo(secondResponse.media().get(0).uploadUrl());
        verify(mediaUploadUrlSigner, org.mockito.Mockito.times(2)).sign(eq("https://blob/existing"), any(Instant.class));
    }

    @Test
    void shouldSignSasUrlsAfterTheTransactionCommitsNotInsideTheTransaction() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenReturn(new SignedUploadUrl("https://blob/a?sas", Instant.now().plusSeconds(900)));

        stubTransactionTemplateToRunCallback();

        createPostUseCase.createPost(command);

        InOrder inOrder = inOrder(transactionManager, postRepository, mediaUploadUrlSigner);
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(mediaUploadUrlSigner).sign(anyString(), any(Instant.class));
    }

    @Test
    void shouldPropagateAzureStorageExceptionsWithoutCorruptingTheAlreadyPersistedPost() {
        var command = metadataCommand(List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1)
        ));
        stubTransactionTemplateToRunCallback();
        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(mediaUrlGenerator.generate(MediaType.IMAGE)).thenReturn(new GeneratedMediaUrls("https://blob/a", "https://blob/a"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        when(mediaUploadUrlSigner.sign(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("Azure Storage is unavailable"));

        assertThatThrownBy(() -> createPostUseCase.createPost(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure Storage is unavailable");

        verify(postRepository).save(any(Post.class));
    }

    // -----------------------------------------------------------------
    // Legacy 2-arg / 3-arg createPost, unchanged behavior (used by the
    // collab-open-and-create-post flow, feature 16)
    // -----------------------------------------------------------------

    @Test
    void shouldAcquireCorrelationLockBeforeCheckingExistingIdempotencyRecordOnLegacyFlow() {
        var command = legacyCommand(Set.of());
        var persistedPost = persistedPost("", Set.of(), Set.of("java"));
        var createdEvent = createdEvent(persistedPost);

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);
        when(postEventMapper.toPostCreatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(createdEvent);
        when(jsonMapper.toJson(createdEvent)).thenReturn("{\"event\":\"payload\"}");

        createPostUseCase.createPost(command, true, true);

        InOrder inOrder = inOrder(postRequestIdempotencyRepository);
        inOrder.verify(postRequestIdempotencyRepository).acquireCorrelationLock(CORRELATION_ID);
        inOrder.verify(postRequestIdempotencyRepository).findPostIdByCorrelationId(CORRELATION_ID);
    }

    @Test
    void shouldCreatePostSuccessfullyWhenTaggedUsersExistAndAreNotBlockedOnLegacyFlow() {
        var taggedUsers = new LinkedHashSet<>(Set.of("alice", "bob"));
        var command = legacyCommand(taggedUsers);
        var persistedPost = persistedPost("hello", taggedUsers, command.postTags());
        var createdEvent = createdEvent(persistedPost);
        var usersByUsername = Map.of("alice", UUID.randomUUID(), "bob", UUID.randomUUID());

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(taggedUsers)).thenReturn(usersByUsername);
        when(taggedUserValidationRepository.findBlockedUserIds(USER_ID, Set.copyOf(usersByUsername.values())))
                .thenReturn(Set.of());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);
        when(postEventMapper.toPostCreatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(createdEvent);
        when(jsonMapper.toJson(createdEvent)).thenReturn("{\"event\":\"payload\"}");

        var response = createPostUseCase.createPost(command, true, true);

        assertThat(response.media().get(0).taggedUsers()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(response.status()).isEqualTo(PostStatus.ACCEPTED);
        verify(taggedUserValidationRepository).findUserIdsByUsernames(taggedUsers);
        verify(taggedUserValidationRepository).findBlockedUserIds(USER_ID, Set.copyOf(usersByUsername.values()));
    }

    @Test
    void shouldReturnPreviouslyCreatedPostWithoutSideEffectsWhenCorrelationIdAlreadyExistsOnLegacyFlow() {
        var existingPost = persistedPost("existing", Set.of("alice"), Set.of("java"));

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(existingPost.getId().value()));
        when(postRepository.findById(existingPost.getId().value())).thenReturn(Optional.of(existingPost));

        var response = createPostUseCase.createPost(new CreatePostCommand(
                CORRELATION_ID,
                USER_ID,
                null,
                PostType.BASIC,
                "new value",
                Set.of("spring"),
                List.of(new PostMediaCommand("https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of("bob"), 1))
        ), true, true);

        assertThat(response.postId()).isEqualTo(existingPost.getId().value());
        assertThat(response.description()).isEqualTo("existing");
        verify(postRepository, never()).save(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
    }

    @Test
    void shouldThrowTaggedUserNotFoundExceptionWhenTaggedUserDoesNotExistOnLegacyFlow() {
        var command = legacyCommand(Set.of("missing"));

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("missing"))).thenReturn(Map.of());

        assertThatThrownBy(() -> createPostUseCase.createPost(command, true, true))
                .isInstanceOf(TaggedUserNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void shouldThrowTaggedUserBlockedExceptionWhenTaggedUserHasBlockedThePostOwnerOrViceVersaOnLegacyFlow() {
        var blockedUserId = UUID.randomUUID();
        var command = legacyCommand(Set.of("alice"));

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(taggedUserValidationRepository.findUserIdsByUsernames(Set.of("alice")))
                .thenReturn(Map.of("alice", blockedUserId));
        when(taggedUserValidationRepository.findBlockedUserIds(USER_ID, Set.of(blockedUserId)))
                .thenReturn(Set.of(blockedUserId));

        assertThatThrownBy(() -> createPostUseCase.createPost(command, true, true))
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
                PostType.COLLAB,
                "hello",
                Set.of("spring"),
                defaultMedia(Set.of())
        );
        var postId = UUID.randomUUID();
        var persistedPost = new Post(
                new PostId(postId),
                new UserId(USER_ID),
                collabId,
                new PostInfo(
                        new PostDescription("hello"),
                        new PostTags(Set.of("spring")),
                        PostType.COLLAB
                ),
                List.of(PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of(), 1)),
                PostStatus.ACCEPTED,
                Instant.now(),
                Instant.now()
        );

        when(postRequestIdempotencyRepository.findPostIdByCorrelationId(CORRELATION_ID)).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);

        var response = createPostUseCase.createPost(command, false);

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.postType()).isEqualTo(PostType.COLLAB);
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
                PostType.COLLAB,
                "hello",
                Set.of("spring"),
                defaultMedia(Set.of())
        );
        var postId = UUID.randomUUID();
        var persistedPost = new Post(
                new PostId(postId),
                new UserId(USER_ID),
                collabId,
                new PostInfo(
                        new PostDescription("hello"),
                        new PostTags(Set.of("spring")),
                        PostType.COLLAB
                ),
                List.of(PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of(), 1)),
                PostStatus.ACCEPTED,
                Instant.now(),
                Instant.now()
        );

        when(postRepository.save(any(Post.class))).thenReturn(persistedPost);

        var response = createPostUseCase.createPost(command, false, false);

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.postType()).isEqualTo(PostType.COLLAB);
        verify(postRequestIdempotencyRepository, never()).acquireCorrelationLock(any(UUID.class));
        verify(postRequestIdempotencyRepository, never()).findPostIdByCorrelationId(any(UUID.class));
        verify(postRequestIdempotencyRepository, never()).save(any(UUID.class), any(UUID.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostCreatedDomainEvent.class));
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /**
     * Stubs the mocked {@link PlatformTransactionManager} so {@code TransactionTemplate}
     * executes the real callback synchronously without a real transaction, exactly as a
     * genuine transaction manager would from the caller's perspective.
     */
    private void stubTransactionTemplateToRunCallback() {
        // No stubbing is actually required: a bare Mockito mock already returns null from
        // getTransaction(...)/commit(...)/rollback(...), which TransactionTemplate tolerates
        // fine since this test never inspects the TransactionStatus. Kept as a named step for
        // readability at call sites.
    }

    /**
     * Mirrors what a real {@code PostRepository.save} does: persistence assigns real
     * createdAt/updatedAt timestamps (see {@code Post.createPending}, which leaves them
     * {@code null} in memory). Tests stub {@code postRepository.save} with this so
     * {@code post.getCreatedAt()} is never null when the use case signs SAS urls.
     */
    private Post withTimestamps(Post post) {
        var now = Instant.now();
        return new Post(
                post.getId(),
                post.getUserId(),
                post.getCollabId(),
                post.getPostInfo(),
                post.getMedia(),
                post.getStatus(),
                now,
                now
        );
    }

    private CreatePostCommand metadataCommand(List<PostMediaCommand> media) {
        return new CreatePostCommand(CORRELATION_ID, USER_ID, null, PostType.BASIC, "hello", Set.of("java"), media);
    }

    private CreatePostCommand legacyCommand(Set<String> taggedUsers) {
        return new CreatePostCommand(CORRELATION_ID, USER_ID, null, PostType.BASIC, "hello", Set.of("spring", "rabbit"), defaultMedia(taggedUsers));
    }

    private List<PostMediaCommand> defaultMedia(Set<String> taggedUsers) {
        return List.of(new PostMediaCommand("https://cdn/image.jpg", null, MediaType.IMAGE, null, taggedUsers, 1));
    }

    private Post persistedPost(String description, Set<String> taggedUsers, Set<String> postTags) {
        var now = Instant.now();
        var postId = UUID.randomUUID();
        return new Post(
                new PostId(postId),
                new UserId(USER_ID),
                null,
                new PostInfo(
                        new PostDescription(description),
                        new PostTags(new LinkedHashSet<>(postTags)),
                        PostType.BASIC
                ),
                List.of(PostMedia.create(postId, "https://cdn/image.jpg", null, MediaType.IMAGE, null, taggedUsers, 1)),
                PostStatus.ACCEPTED,
                now,
                now
        );
    }

    private Post pendingPost(String url, String thumbnailUrl, MediaType mediaType) {
        var now = Instant.now();
        var postId = UUID.randomUUID();
        return new Post(
                new PostId(postId),
                new UserId(USER_ID),
                null,
                new PostInfo(
                        new PostDescription("hello"),
                        new PostTags(Set.of("java")),
                        PostType.BASIC
                ),
                List.of(PostMedia.create(postId, url, thumbnailUrl, mediaType, null, Set.of(), 1)),
                PostStatus.PENDING,
                now,
                now
        );
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
                .postTags(post.getTags().value())
                .media(PostEventMapper.toMediaPayload(post.getMedia()))
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
