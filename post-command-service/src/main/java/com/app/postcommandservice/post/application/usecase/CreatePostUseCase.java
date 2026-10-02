package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.application.dto.CreatePostResult;
import com.app.postcommandservice.post.application.dto.PostMediaResponse;
import com.app.postcommandservice.post.application.dto.PostResponse;
import com.app.postcommandservice.post.application.mapper.PostApplicationMapper;
import com.app.postcommandservice.post.application.port.MediaUploadUrlSigner;
import com.app.postcommandservice.post.application.port.MediaUrlGenerator;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.application.repository.PostRequestIdempotencyRepository;
import com.app.postcommandservice.post.application.repository.TaggedUserValidationRepository;
import com.app.postcommandservice.post.domain.events.PostCreatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.IdempotencyKeyReuseException;
import com.app.postcommandservice.post.domain.exception.InvalidPostMediaException;
import com.app.postcommandservice.post.domain.exception.TaggedUserBlockedException;
import com.app.postcommandservice.post.domain.exception.TaggedUserNotFoundException;
import com.app.postcommandservice.post.domain.exception.TooManyTaggedUsersException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
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

@Service
public class CreatePostUseCase {

    private final PostRepository postRepository;
    private final PostRequestIdempotencyRepository postRequestIdempotencyRepository;
    private final TaggedUserValidationRepository taggedUserValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostEventMapper postEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MediaUrlGenerator mediaUrlGenerator;
    private final MediaUploadUrlSigner mediaUploadUrlSigner;
    private final PostMediaProperties postMediaProperties;
    private final TransactionTemplate transactionTemplate;

    public CreatePostUseCase(
            PostRepository postRepository,
            PostRequestIdempotencyRepository postRequestIdempotencyRepository,
            TaggedUserValidationRepository taggedUserValidationRepository,
            OutboxEventRepository outboxEventRepository,
            PostEventMapper postEventMapper,
            JsonMapper jsonMapper,
            ApplicationEventPublisher applicationEventPublisher,
            MediaUrlGenerator mediaUrlGenerator,
            MediaUploadUrlSigner mediaUploadUrlSigner,
            PostMediaProperties postMediaProperties,
            PlatformTransactionManager transactionManager) {
        this.postRepository = postRepository;
        this.postRequestIdempotencyRepository = postRequestIdempotencyRepository;
        this.taggedUserValidationRepository = taggedUserValidationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.postEventMapper = postEventMapper;
        this.jsonMapper = jsonMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.mediaUrlGenerator = mediaUrlGenerator;
        this.mediaUploadUrlSigner = mediaUploadUrlSigner;
        this.postMediaProperties = postMediaProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Metadata-only single-post creation (task 33): the client sends only mediaType/order/
     * taggedUsers per media. The server validates the request, generates blob urls per media,
     * persists the post as {@code PENDING}, and signs fresh upload SAS urls once the
     * transaction has committed (signing may be a network call and must never run inside the
     * transaction). No {@code PostCreatedEvent} is published here; that happens once the post
     * is confirmed {@code ACCEPTED} (task 37), so downstream consumers never observe a
     * {@code PENDING} post. An idempotent retry never regenerates blob urls, but always signs
     * fresh SAS urls since the previous ones may have expired.
     */
    public CreatePostResult createPost(CreatePostCommand command) {
        validateMediaRequest(command.media());

        PendingPostPersistence persistence = transactionTemplate.execute(status -> persistPendingPost(command));
        Objects.requireNonNull(persistence, "persistence outcome must not be null");
        PostResponse response = signUploadUrls(persistence.post());
        return new CreatePostResult(response, persistence.created());
    }

    /**
     * Metadata-only pending-post persistence (task 33's media validation / server-generated
     * url / {@code PENDING} behaviour), shared with callers that own their own surrounding
     * transaction and request-idempotency semantics instead of this class's own
     * {@code PostRequestIdempotencyRepository}-backed one (task 34: the collab-open-and-
     * create-post flow, whose replay detection runs against {@code CollabRequestIdempotencyRepository}
     * before this method is ever invoked). Must be called from within an already-active
     * transaction; this method does not manage a transaction boundary itself and does not
     * sign upload SAS urls (call {@link #signUploadUrls(Post)} after the caller's transaction
     * commits).
     */
    public Post createPendingPost(CreatePostCommand command) {
        validateMediaRequest(command.media());
        return buildAndSavePendingPost(command);
    }

    /**
     * Legacy create-post path used by the collab-open-and-create-post flow (feature 16), which
     * still supplies client-provided url/thumbnailUrl/duration directly and expects an
     * immediately {@code ACCEPTED}, synchronously usable post. Left intentionally unchanged by
     * task 33 so that flow's contract keeps holding.
     */
    @Transactional
    public PostResponse createPost(CreatePostCommand command, boolean publishCreatedEvent) {
        return createPost(command, publishCreatedEvent, true);
    }

    @Transactional
    public PostResponse createPost(
            CreatePostCommand command,
            boolean publishCreatedEvent,
            boolean persistRequestIdempotency) {
        String requestHash = CreatePostRequestHasher.hash(command);

        if (persistRequestIdempotency) {
            postRequestIdempotencyRepository.acquireCorrelationLock(command.currentUserId(), command.correlationId());
        }

        if (persistRequestIdempotency) {
            var existing = postRequestIdempotencyRepository.find(command.currentUserId(), command.correlationId());
            if (existing.isPresent()) {
                var existingPost = postRepository.findById(existing.get().postId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency record exists but post was not found: " + existing.get().postId()));
                requireSameOwnerAndPayload(existingPost, command.currentUserId(), existing.get().requestHash(), requestHash);
                return toResponse(existingPost);
            }
        }

        var postTags = new PostTags(normalizeSet(command.postTags()));
        var description = new PostDescription(command.description() == null ? "" : command.description());

        var postId = new PostId(UUID.randomUUID());
        var postInfo = new PostInfo(description, postTags, resolvePostType(command.postType()));
        var media = PostApplicationMapper.toDomainMedia(postId.value(), command.media());

        Set<String> allTaggedUsernames = new LinkedHashSet<>();
        for (var postMedia : media) {
            allTaggedUsernames.addAll(postMedia.getTaggedUsers());
        }
        validateTaggedUsers(command.currentUserId(), allTaggedUsernames);

        var post = Post.create(
                postId,
                new UserId(command.currentUserId()),
                command.collabId(),
                postInfo,
                media
        );

        var savedPost = postRepository.save(post);
        if (persistRequestIdempotency) {
            postRequestIdempotencyRepository.save(
                    command.currentUserId(), command.correlationId(), savedPost.getId().value(), requestHash);
        }

        if (publishCreatedEvent) {
            var outboxId = UUID.randomUUID();
            var eventCorrelationId = UUID.randomUUID();
            var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var event = postEventMapper.toPostCreatedEvent(outboxId, eventCorrelationId, savedPost, occurredAt);
            saveOutboxEvent(command.correlationId(), outboxId, event);

            applicationEventPublisher.publishEvent(new PostCreatedDomainEvent(outboxId));
        }

        return toResponse(savedPost);
    }

    /**
     * Result of the transactional persist step: {@code created} is {@code false} when this
     * call simply replayed an existing post for an already-seen correlation id, so the HTTP
     * layer can report {@code 200 OK} instead of {@code 201 Created} for that case.
     */
    private record PendingPostPersistence(Post post, boolean created) {
    }

    private PendingPostPersistence persistPendingPost(CreatePostCommand command) {
        String requestHash = CreatePostRequestHasher.hash(command);

        postRequestIdempotencyRepository.acquireCorrelationLock(command.currentUserId(), command.correlationId());

        var existing = postRequestIdempotencyRepository.find(command.currentUserId(), command.correlationId());
        if (existing.isPresent()) {
            var existingPost = postRepository.findById(existing.get().postId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record exists but post was not found: " + existing.get().postId()));
            requireSameOwnerAndPayload(existingPost, command.currentUserId(), existing.get().requestHash(), requestHash);
            return new PendingPostPersistence(existingPost, false);
        }

        var savedPost = buildAndSavePendingPost(command);
        postRequestIdempotencyRepository.save(
                command.currentUserId(), command.correlationId(), savedPost.getId().value(), requestHash);
        return new PendingPostPersistence(savedPost, true);
    }

    /**
     * Builds the {@code PENDING} {@link Post} aggregate (server-generated media urls, per-media
     * tagged-user validation) and persists it. Shared by both {@link #persistPendingPost} (plain
     * single-post flow, request-idempotency-checked by the caller above) and
     * {@link #createPendingPost(CreatePostCommand)} (collab flow, idempotency-checked by its own
     * caller). Callers are responsible for {@link #validateMediaRequest(List)} beforehand.
     */
    private Post buildAndSavePendingPost(CreatePostCommand command) {
        var postTags = new PostTags(normalizeSet(command.postTags()));
        var description = new PostDescription(command.description() == null ? "" : command.description());
        var postId = new PostId(UUID.randomUUID());
        var postInfo = new PostInfo(description, postTags, resolvePostType(command.postType()));

        var media = buildGeneratedMedia(postId.value(), command.media());

        Set<String> allTaggedUsernames = new LinkedHashSet<>();
        for (var postMedia : media) {
            allTaggedUsernames.addAll(postMedia.getTaggedUsers());
        }
        validateTaggedUsers(command.currentUserId(), allTaggedUsernames);

        var post = Post.createPending(
                postId,
                new UserId(command.currentUserId()),
                command.collabId(),
                postInfo,
                media
        );

        return postRepository.save(post);
    }

    /**
     * Defensive ownership/payload invariant for an idempotent replay. Ownership mismatches
     * should be structurally impossible once the idempotency lookup itself is scoped by
     * userId, but this is kept as a belt-and-suspenders check. A payload mismatch means the
     * same (userId, correlationId) pair was reused for a different request; a {@code null}
     * stored hash means the record predates request hashing and is treated as a match. Both
     * failure cases throw the same generic exception so callers cannot distinguish the cause.
     */
    private void requireSameOwnerAndPayload(
            Post existingPost,
            UUID currentUserId,
            String storedRequestHash,
            String freshRequestHash) {
        if (!existingPost.getUserId().value().equals(currentUserId)) {
            throw new IdempotencyKeyReuseException();
        }
        if (storedRequestHash != null && !storedRequestHash.equals(freshRequestHash)) {
            throw new IdempotencyKeyReuseException();
        }
    }

    /**
     * Signs fresh upload SAS urls for every media item of an already-persisted {@code PENDING}
     * post and maps it to the full task-33 response shape. Public so callers that build their
     * own transaction boundary around {@link #createPendingPost(CreatePostCommand)} (the collab
     * flow) can invoke this strictly after their transaction commits, exactly like
     * {@link #createPost(CreatePostCommand)} does for the plain single-post flow. Always re-signs
     * on every call (fresh urls on replay too), since a previously issued SAS may have expired.
     */
    public PostResponse signUploadUrls(Post post) {
        List<PostMediaResponse> mediaResponses = new ArrayList<>();
        Instant uploadExpiresAt = null;

        for (PostMedia media : post.getMedia()) {
            var signedUpload = mediaUploadUrlSigner.sign(media.getUrl(), post.getCreatedAt());
            String thumbnailUploadUrl = null;
            if (media.getMediaType() == MediaType.VIDEO) {
                thumbnailUploadUrl = mediaUploadUrlSigner.sign(media.getThumbnailUrl(), post.getCreatedAt()).url();
            }
            mediaResponses.add(PostApplicationMapper.toMediaResponse(media, signedUpload.url(), thumbnailUploadUrl));
            uploadExpiresAt = signedUpload.expiresAt();
        }

        return PostApplicationMapper.toResponse(post, mediaResponses, uploadExpiresAt);
    }

    private void validateMediaRequest(List<PostMediaCommand> media) {
        if (media == null || media.isEmpty()) {
            throw new InvalidPostMediaException("Post must contain at least one media item");
        }

        Set<Integer> orders = new HashSet<>();
        for (var mediaCommand : media) {
            if (mediaCommand.mediaType() == null) {
                throw new InvalidPostMediaException("Post media mediaType is required");
            }
            if (!orders.add(mediaCommand.order())) {
                throw new InvalidPostMediaException("Post media order values must be distinct");
            }
        }

        int size = media.size();
        for (int order = 1; order <= size; order++) {
            if (!orders.contains(order)) {
                throw new InvalidPostMediaException(
                        "Post media order values must be contiguous starting at 1, without gaps");
            }
        }
    }

    private List<PostMedia> buildGeneratedMedia(UUID postId, List<PostMediaCommand> mediaCommands) {
        return mediaCommands.stream()
                .map(mediaCommand -> {
                    var generated = mediaUrlGenerator.generate(mediaCommand.mediaType());
                    var postMedia = PostMedia.create(
                            postId,
                            generated.url(),
                            generated.thumbnailUrl(),
                            mediaCommand.mediaType(),
                            null,
                            mediaCommand.taggedUsers(),
                            mediaCommand.order()
                    );
                    validateTaggedUsersCount(postMedia.getTaggedUsers());
                    return postMedia;
                })
                .toList();
    }

    private void validateTaggedUsersCount(Set<String> taggedUsers) {
        int max = postMediaProperties.getMaxTaggedUsers();
        if (taggedUsers.size() > max) {
            throw new TooManyTaggedUsersException(max);
        }
    }

    private void validateTaggedUsers(UUID creatorUserId, Set<String> taggedUsers) {
        if (taggedUsers.isEmpty()) {
            return;
        }

        Map<String, UUID> userIdsByUsername = taggedUserValidationRepository.findUserIdsByUsernames(taggedUsers);
        for (String taggedUsername : taggedUsers) {
            if (!userIdsByUsername.containsKey(taggedUsername)) {
                throw new TaggedUserNotFoundException(taggedUsername);
            }
        }

        var blockedUserIds = taggedUserValidationRepository.findBlockedUserIds(
                creatorUserId,
                Set.copyOf(userIdsByUsername.values())
        );

        for (Map.Entry<String, UUID> entry : userIdsByUsername.entrySet()) {
            if (blockedUserIds.contains(entry.getValue())) {
                throw new TaggedUserBlockedException(entry.getKey());
            }
        }
    }

    private void saveOutboxEvent(UUID correlationId, UUID outboxId, PostCreatedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostCreatedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }

    private PostResponse toResponse(Post post) {
        return PostApplicationMapper.toResponse(post);
    }

    private Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private PostType resolvePostType(PostType postType) {
        return postType == null ? PostType.BASIC : postType;
    }
}
