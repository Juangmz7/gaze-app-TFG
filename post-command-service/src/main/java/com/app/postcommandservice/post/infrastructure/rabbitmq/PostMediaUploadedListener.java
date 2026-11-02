package com.app.postcommandservice.post.infrastructure.rabbitmq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.application.usecase.PostMediaVerificationService;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedEvent;
import com.app.postcommandservice.post.infrastructure.events.PostMediaUploadedMediaPayload;

/**
 * Consumes {@link PostMediaUploadedEvent} (task 35) and triggers the async media-upload
 * verification flow (task 37). Deliberately thin: only the payload shape is validated here,
 * never business rules — those belong to {@link PostMediaVerificationService}.
 *
 * <p>This method must never be {@code @Transactional}: {@code PostMediaVerificationService
 * .process(...)} manages its own transaction boundaries (a short read-only snapshot, then the
 * {@code MediaVerifier} call with no transaction open, then a separate {@code REQUIRES_NEW}
 * transaction) and wrapping it in an outer transaction here would defeat that by keeping a
 * connection open across the verifier call.</p>
 *
 * <p>An invalid payload throws {@link IllegalArgumentException}, which the broker's retry
 * policy ({@code RabbitMQConfig}) excludes from retries, so it is rejected without requeue and
 * routed straight to {@code q.post-command-service.post.media.dlq}. Any other exception (an
 * infrastructure failure propagated out of {@code process}) is retried a bounded number of
 * times with exponential backoff before also landing in the DLQ.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = "${rabbitmq.queue.post-media}")
public class PostMediaUploadedListener {

    private final PostMediaVerificationService postMediaVerificationService;

    @RabbitHandler
    public void onPostMediaUploaded(PostMediaUploadedEvent event) {
        validate(event);
        postMediaVerificationService.process(event);
    }

    @RabbitHandler(isDefault = true)
    public void onUnsupportedPayload(Object ignored) {
        throw new IllegalArgumentException("Unsupported post media uploaded payload");
    }

    private void validate(PostMediaUploadedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.postId() == null) {
            throw new IllegalArgumentException("event.postId must not be null");
        }
        if (event.media() == null || event.media().isEmpty()) {
            throw new IllegalArgumentException("event.media must not be empty");
        }
        for (PostMediaUploadedMediaPayload media : event.media()) {
            validateMedia(media);
        }
    }

    private void validateMedia(PostMediaUploadedMediaPayload media) {
        if (media == null) {
            throw new IllegalArgumentException("event.media item must not be null");
        }
        if (media.id() == null) {
            throw new IllegalArgumentException("event.media.id must not be null");
        }
        if (!StringUtils.hasText(media.url())) {
            throw new IllegalArgumentException("event.media.url must not be blank");
        }
        if (!StringUtils.hasText(media.thumbnailUrl())) {
            throw new IllegalArgumentException("event.media.thumbnailUrl must not be blank");
        }
        if (media.mediaType() == null) {
            throw new IllegalArgumentException("event.media.mediaType must not be null");
        }
        if (media.order() < 1) {
            throw new IllegalArgumentException("event.media.order must be a positive 1-based index");
        }
    }
}
