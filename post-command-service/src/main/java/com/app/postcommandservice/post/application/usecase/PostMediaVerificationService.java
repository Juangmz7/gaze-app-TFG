package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.post.application.port.MediaToVerify;
import com.app.postcommandservice.post.application.port.MediaVerificationResult;
import com.app.postcommandservice.post.application.port.MediaVerifier;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository.MediaSnapshot;
import com.app.postcommandservice.post.application.repository.PostMediaVerificationRepository.PostSnapshot;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.events.PostCreatedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationFailedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadValidationSucceededEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
import com.app.postcommandservice.shared.domain.events.OutboxEventCreatedDomainEvent;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.events.EventMessage;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

/**
 * Resolves a {@code PENDING} post's media upload (task 37) by calling {@link MediaVerifier}
 * and applying the outcome: {@code ACCEPTED} with persisted VIDEO durations and {@code
 * PostCreatedEvent}/{@code PostMediaUploadValidationSucceededEvent} on success, or {@code
 * MEDIA_UPLOAD_FAILED} with {@code PostMediaUploadValidationFailedEvent} on a business failure.
 *
 * <p>{@link #process(PostMediaUploadedEvent)} itself is deliberately NOT {@code @Transactional}:
 * it reads a snapshot in its own short read-only transaction, calls the (potentially slow,
 * network-bound) {@code MediaVerifier} with no transaction open and therefore no DB connection
 * held, then applies the outcome in a separate, fresh {@code REQUIRES_NEW} transaction. Both
 * transactional steps are invoked through {@code self} — an injected {@code @Lazy} proxy of
 * this same bean — because calling {@code this.findPostSnapshot(...)} or {@code
 * this.applyVerificationOutcome(...)} directly would bypass the Spring AOP proxy and silently
 * run with no transaction at all.</p>
 *
 * <p>Only infrastructure failures (the {@code MediaVerifier} call failing because the storage
 * backend is unavailable) propagate out of {@code process}; every business inconsistency is
 * handled internally and never thrown, so the RabbitMQ listener container never retries a
 * non-recoverable outcome.</p>
 */
@Slf4j
@Service
public class PostMediaVerificationService {

    private final PostMediaVerificationRepository postMediaVerificationRepository;
    private final PostRepository postRepository;
    private final MediaVerifier mediaVerifier;
    private final PostEventMapper postEventMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PostMediaVerificationService self;

    public PostMediaVerificationService(
            PostMediaVerificationRepository postMediaVerificationRepository,
            PostRepository postRepository,
            MediaVerifier mediaVerifier,
            PostEventMapper postEventMapper,
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            ApplicationEventPublisher applicationEventPublisher,
            @Lazy PostMediaVerificationService self) {
        this.postMediaVerificationRepository = postMediaVerificationRepository;
        this.postRepository = postRepository;
        this.mediaVerifier = mediaVerifier;
        this.postEventMapper = postEventMapper;
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.self = self;
    }

    public void process(PostMediaUploadedEvent event) {
        UUID postId = event.postId();

        Optional<PostSnapshot> snapshot = self.findPostSnapshot(postId);
        if (snapshot.isEmpty() || snapshot.get().status() != PostStatus.PENDING) {
            log.info(
                    "Ignoring media-uploaded event for post {}: post is absent or not PENDING (status={})",
                    postId, snapshot.map(PostSnapshot::status).orElse(null));
            return;
        }

        if (!mediaIdsMatch(snapshot.get(), event)) {
            log.warn("Ignoring media-uploaded event for post {}: payload media ids do not match persisted media",
                    postId);
            return;
        }

        MediaVerificationResult result = mediaVerifier.verify(toMediaToVerify(event));

        self.applyVerificationOutcome(postId, result);
    }

    @Transactional(readOnly = true)
    public Optional<PostSnapshot> findPostSnapshot(UUID postId) {
        return postMediaVerificationRepository.findSnapshot(postId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyVerificationOutcome(UUID postId, MediaVerificationResult result) {
        if (result instanceof MediaVerificationResult.Success success) {
            applySuccess(postId, success);
        } else if (result instanceof MediaVerificationResult.Failure failure) {
            applyFailure(postId, failure);
        }
    }

    private void applySuccess(UUID postId, MediaVerificationResult.Success success) {
        int updatedRows = postMediaVerificationRepository.updateStatusIfPending(postId, PostStatus.ACCEPTED);
        if (updatedRows == 0) {
            log.info("Skipping media verification success for post {}: no longer PENDING", postId);
            return;
        }

        Map<UUID, Integer> durationsMillis = success.videoDurationsSeconds().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() * 1000));
        postMediaVerificationRepository.persistVideoDurationsMillis(durationsMillis);

        var post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalStateException(
                        "Post " + postId + " not found right after accepting its media upload"));

        var succeededEvent = PostMediaUploadValidationSucceededEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(postId)
                .build();
        publishThroughOutbox(succeededEvent, PostMediaUploadValidationSucceededEvent.class.getSimpleName());

        var createdEvent = postEventMapper.toPostCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), post, Instant.now());
        publishThroughOutbox(createdEvent, PostCreatedEvent.class.getSimpleName());

        log.info("Post {} accepted after media verification", postId);
    }

    private void applyFailure(UUID postId, MediaVerificationResult.Failure failure) {
        int updatedRows = postMediaVerificationRepository.updateStatusIfPending(postId, PostStatus.MEDIA_UPLOAD_FAILED);
        if (updatedRows == 0) {
            log.info("Skipping media verification failure for post {}: no longer PENDING", postId);
            return;
        }

        String reason = buildFailureReason(postId, failure);
        log.warn(
                "Post {} media upload failed verification: reasonCode={}, mediaId={}, mediaUrl={}",
                postId, failure.reason(), failure.mediaId(), failure.mediaUrl());

        var failedEvent = PostMediaUploadValidationFailedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(postId)
                .reasonCode(failure.reason())
                .reason(reason)
                .build();
        publishThroughOutbox(failedEvent, PostMediaUploadValidationFailedEvent.class.getSimpleName());
    }

    private String buildFailureReason(UUID postId, MediaVerificationResult.Failure failure) {
        String what = switch (failure.reason()) {
            case BLOB_NOT_FOUND -> "media was not found";
            case EMPTY_BLOB -> "media is empty";
            case FILE_TOO_LARGE -> "media exceeds the allowed size";
            case UNSUPPORTED_FORMAT -> "media format is not supported";
            case TYPE_MISMATCH -> "media content does not match its declared type";
            case CORRUPT_FILE -> "media file is corrupt";
            case DURATION_UNREADABLE -> "video duration could not be read";
            case DURATION_TOO_LONG -> "video exceeds the allowed duration";
            // Never produced by MediaVerifier: UPLOAD_EXPIRED is built directly by the
            // scheduled cleanup job (task 39), which never calls this method.
            case UPLOAD_EXPIRED -> throw new IllegalStateException(
                    "UPLOAD_EXPIRED is never produced by MediaVerifier");
        };
        return "Upload of post %s failed: %s at %s".formatted(postId, what, failure.mediaUrl());
    }

    private void publishThroughOutbox(EventMessage event, String eventType) {
        var outboxId = event.id();
        outboxEventRepository.save(OutboxEvent.builder()
                .id(outboxId)
                .correlationId(event.correlationId())
                .payload(jsonMapper.toJson(event))
                .eventType(eventType)
                .status(EventStatus.PENDING)
                .build());

        applicationEventPublisher.publishEvent(new OutboxEventCreatedDomainEvent(outboxId));
    }

    private boolean mediaIdsMatch(PostSnapshot snapshot, PostMediaUploadedEvent event) {
        Set<UUID> persistedIds = new HashSet<>();
        for (MediaSnapshot mediaSnapshot : snapshot.media()) {
            persistedIds.add(mediaSnapshot.id());
        }

        Set<UUID> payloadIds = new HashSet<>();
        for (PostMediaUploadedMediaPayload media : event.media()) {
            payloadIds.add(media.id());
        }

        return persistedIds.equals(payloadIds);
    }

    private List<MediaToVerify> toMediaToVerify(PostMediaUploadedEvent event) {
        return event.media().stream()
                .map(media -> new MediaToVerify(media.id(), media.url(), media.thumbnailUrl(), media.mediaType()))
                .toList();
    }
}
