package com.app.postcommandservice.post.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.app.postcommandservice.post.application.repository.ConfirmMediaUploadRepository;

@Repository
@RequiredArgsConstructor
public class ConfirmMediaUploadRepositoryImpl implements ConfirmMediaUploadRepository {

    private final PostJpaRepository postJpaRepository;
    private final PostMediaJpaRepository postMediaJpaRepository;

    @Override
    public Optional<PendingMediaUpload> findById(UUID postId) {
        return postJpaRepository.findConfirmMediaUploadViewById(postId)
                .map(postView -> new PendingMediaUpload(
                        postView.getId(),
                        postView.getAuthorId(),
                        postView.getStatus(),
                        postView.getCreatedAt(),
                        postMediaJpaRepository.findConfirmMediaUploadViewsByPostId(postId).stream()
                                .map(mediaView -> new MediaItem(
                                        mediaView.getId(),
                                        mediaView.getUrl(),
                                        mediaView.getThumbnailUrl(),
                                        mediaView.getMediaType(),
                                        mediaView.getMediaOrder()
                                ))
                                .toList()
                ));
    }
}
