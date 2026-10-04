package com.app.postcommandservice.post.application.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.app.postcommandservice.post.application.commands.RefreshUploadUrlsCommand;
import com.app.postcommandservice.post.application.commands.RefreshUploadUrlsCommand.ClientMediaUpload;
import com.app.postcommandservice.post.application.dto.RefreshUploadUrlsResponse;
import com.app.postcommandservice.post.application.dto.RefreshUploadUrlsResponse.RefreshedMediaUpload;
import com.app.postcommandservice.post.application.port.MediaUploadUrlSigner;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository.MediaUploadState;
import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository.RefreshableUpload;
import com.app.postcommandservice.post.domain.exception.MediaUploadWindowExpiredException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostNotPendingException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.infrastructure.config.PostMediaProperties;

/**
 * Refreshes (or reuses) the upload SAS urls of a {@code PENDING} post's media (task 38), for
 * the case where the client has not finished uploading before the SAS urls issued on creation
 * (task 33) or a previous call to this endpoint have expired. Blob urls never change; only the
 * SAS is renewed.
 *
 * <p>Each issued SAS is persisted hashed (BCrypt) with its expiry, keyed by its owning
 * {@code post_media} row. Since BCrypt hashes cannot be reversed, recognising a still-valid,
 * previously issued SAS requires the client to resend the SAS url(s) it currently holds; this
 * use case then calls {@link PasswordEncoder#matches} once per media item against the row it
 * already knows applies (never a scan over many rows).</p>
 *
 * <p>{@link #refresh(RefreshUploadUrlsCommand)} is deliberately NOT {@code @Transactional}: it
 * reads a snapshot in its own short read-only transaction via {@code self}, calls the
 * potentially slow, network-bound {@link MediaUploadUrlSigner} with no transaction open, then
 * persists any freshly issued SAS hash/expiry in a separate, fresh {@code REQUIRES_NEW}
 * transaction — mirroring {@code PostMediaVerificationService} (task 37). Both transactional
 * steps are invoked through {@code self}, an injected {@code @Lazy} proxy of this same bean,
 * since calling them directly would bypass the Spring AOP proxy and silently run with no
 * transaction at all.</p>
 */
@Slf4j
@Service
public class RefreshUploadUrlsUseCase {

    private final RefreshUploadUrlsRepository refreshUploadUrlsRepository;
    private final PostMediaProperties postMediaProperties;
    private final MediaUploadUrlSigner mediaUploadUrlSigner;
    private final PasswordEncoder passwordEncoder;
    private final RefreshUploadUrlsUseCase self;

    public RefreshUploadUrlsUseCase(
            RefreshUploadUrlsRepository refreshUploadUrlsRepository,
            PostMediaProperties postMediaProperties,
            MediaUploadUrlSigner mediaUploadUrlSigner,
            PasswordEncoder passwordEncoder,
            @Lazy RefreshUploadUrlsUseCase self) {
        this.refreshUploadUrlsRepository = refreshUploadUrlsRepository;
        this.postMediaProperties = postMediaProperties;
        this.mediaUploadUrlSigner = mediaUploadUrlSigner;
        this.passwordEncoder = passwordEncoder;
        this.self = self;
    }

    public RefreshUploadUrlsResponse refresh(RefreshUploadUrlsCommand command) {
        RefreshableUpload upload = self.findUpload(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertOwnership(upload, command.userId());
        assertPending(upload);
        assertWithinUploadWindow(upload);

        Map<UUID, ClientMediaUpload> clientMediaById = indexByMediaId(command.clientMedia());

        List<RefreshedMediaUpload> refreshedMedia = new ArrayList<>();
        List<SasUpdate> sasUpdates = new ArrayList<>();
        Instant uploadExpiresAt = null;

        for (MediaUploadState media : upload.media()) {
            ClientMediaUpload clientMedia = clientMediaById.get(media.id());

            ResolvedUrl resolvedUpload = resolveUploadUrl(
                    media.id(), media.url(), upload.createdAt(),
                    clientMedia == null ? null : clientMedia.uploadUrl(),
                    media.uploadSasHash(), media.uploadSasExpiresAt(), false);
            if (resolvedUpload.update() != null) {
                sasUpdates.add(resolvedUpload.update());
            }
            uploadExpiresAt = earliest(uploadExpiresAt, resolvedUpload.expiresAt());

            String thumbnailUploadUrl = null;
            if (media.mediaType() == MediaType.VIDEO) {
                ResolvedUrl resolvedThumbnail = resolveUploadUrl(
                        media.id(), media.thumbnailUrl(), upload.createdAt(),
                        clientMedia == null ? null : clientMedia.thumbnailUploadUrl(),
                        media.thumbnailSasHash(), media.thumbnailSasExpiresAt(), true);
                if (resolvedThumbnail.update() != null) {
                    sasUpdates.add(resolvedThumbnail.update());
                }
                uploadExpiresAt = earliest(uploadExpiresAt, resolvedThumbnail.expiresAt());
                thumbnailUploadUrl = resolvedThumbnail.url();
            }

            refreshedMedia.add(new RefreshedMediaUpload(media.id(), resolvedUpload.url(), thumbnailUploadUrl));
        }

        if (!sasUpdates.isEmpty()) {
            self.persistSasUpdates(sasUpdates);
        }

        log.info("Refreshed upload SAS urls for post {}: {} media item(s), {} newly signed",
                upload.postId(), upload.media().size(), sasUpdates.size());

        return new RefreshUploadUrlsResponse(upload.postId(), uploadExpiresAt, refreshedMedia);
    }

    @Transactional(readOnly = true)
    public Optional<RefreshableUpload> findUpload(UUID postId) {
        return refreshUploadUrlsRepository.findById(postId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistSasUpdates(List<SasUpdate> updates) {
        for (SasUpdate update : updates) {
            if (update.thumbnail()) {
                refreshUploadUrlsRepository.updateThumbnailSas(update.mediaId(), update.hash(), update.expiresAt());
            } else {
                refreshUploadUrlsRepository.updateUploadSas(update.mediaId(), update.hash(), update.expiresAt());
            }
        }
    }

    /**
     * Decides, for a single blob url (content or thumbnail), whether the client-supplied SAS is
     * still the legitimately issued one (reused verbatim, no re-sign, no DB write) or whether a
     * fresh SAS must be signed and its new hash/expiry persisted.
     */
    private ResolvedUrl resolveUploadUrl(
            UUID mediaId,
            String blobUrl,
            Instant postCreatedAt,
            String clientSuppliedUrl,
            String storedHash,
            Instant storedExpiresAt,
            boolean thumbnail) {

        if (isStillValid(clientSuppliedUrl, storedHash, storedExpiresAt)) {
            return new ResolvedUrl(clientSuppliedUrl, storedExpiresAt, null);
        }

        var signed = mediaUploadUrlSigner.sign(blobUrl, postCreatedAt);
        String hash = passwordEncoder.encode(sha256Hex(signed.url()));
        return new ResolvedUrl(signed.url(), signed.expiresAt(), new SasUpdate(mediaId, thumbnail, hash, signed.expiresAt()));
    }

    private boolean isStillValid(String clientSuppliedUrl, String storedHash, Instant storedExpiresAt) {
        if (!StringUtils.hasText(clientSuppliedUrl) || !StringUtils.hasText(storedHash) || storedExpiresAt == null) {
            return false;
        }
        if (!storedExpiresAt.isAfter(Instant.now())) {
            return false;
        }
        return passwordEncoder.matches(sha256Hex(clientSuppliedUrl), storedHash);
    }

    /**
     * BCrypt silently truncates (and {@code BCryptPasswordEncoder} rejects outright) inputs
     * longer than 72 bytes, while a signed SAS url is routinely far longer than that. Hashing a
     * fixed-length SHA-256 digest of the url instead of the url itself keeps BCrypt's salted,
     * slow verification property (the actual security requirement here) while accepting a url
     * of any length.
     */
    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }

    private Instant earliest(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null || current.isBefore(candidate)) {
            return current;
        }
        return candidate;
    }

    private Map<UUID, ClientMediaUpload> indexByMediaId(List<ClientMediaUpload> clientMedia) {
        Map<UUID, ClientMediaUpload> result = new HashMap<>();
        if (clientMedia == null) {
            return result;
        }
        for (ClientMediaUpload media : clientMedia) {
            if (media != null && media.mediaId() != null) {
                result.put(media.mediaId(), media);
            }
        }
        return result;
    }

    private void assertOwnership(RefreshableUpload upload, UUID userId) {
        if (!upload.authorId().equals(userId)) {
            throw new PostOwnershipException(upload.postId(), userId);
        }
    }

    private void assertPending(RefreshableUpload upload) {
        if (upload.status() != PostStatus.PENDING) {
            throw new PostNotPendingException(upload.postId(), upload.status(), "refresh upload urls");
        }
    }

    private void assertWithinUploadWindow(RefreshableUpload upload) {
        var windowExpiresAt = upload.createdAt().plus(postMediaProperties.getUploadWindow());
        if (Instant.now().isAfter(windowExpiresAt)) {
            throw new MediaUploadWindowExpiredException(upload.createdAt(), windowExpiresAt);
        }
    }

    private record ResolvedUrl(String url, Instant expiresAt, SasUpdate update) {
    }

    private record SasUpdate(UUID mediaId, boolean thumbnail, String hash, Instant expiresAt) {
    }
}
