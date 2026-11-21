package com.app.postcommandservice.post.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.RefreshUploadUrlsRepository;

@Repository
@RequiredArgsConstructor
public class RefreshUploadUrlsRepositoryImpl implements RefreshUploadUrlsRepository {

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;

    @Override
    public Optional<RefreshableUpload> findById(UUID postId) {
        return postJpaRepository.findConfirmMediaUploadViewById(postId)
                .map(postView -> new RefreshableUpload(
                        postView.getId(),
                        postView.getAuthorId(),
                        postView.getStatus(),
                        postView.getCreatedAt(),
                        postMediaJpaRepository.findRefreshUploadUrlsViewsByPostId(postId).stream()
                                .map(mediaView -> new MediaUploadState(
                                        mediaView.getId(),
                                        mediaView.getUrl(),
                                        mediaView.getThumbnailUrl(),
                                        mediaView.getMediaType(),
                                        mediaView.getMediaOrder(),
                                        mediaView.getUploadSasHash(),
                                        mediaView.getUploadSasExpiresAt(),
                                        mediaView.getThumbnailSasHash(),
                                        mediaView.getThumbnailSasExpiresAt()
                                ))
                                .toList()
                ));
    }

    @Override
    public void updateUploadSas(UUID mediaId, String uploadSasHash, Instant uploadSasExpiresAt) {
        postMediaJpaRepository.updateUploadSas(mediaId, uploadSasHash, uploadSasExpiresAt);
    }

    @Override
    public void updateThumbnailSas(UUID mediaId, String thumbnailSasHash, Instant thumbnailSasExpiresAt) {
        postMediaJpaRepository.updateThumbnailSas(mediaId, thumbnailSasHash, thumbnailSasExpiresAt);
    }
}
