package com.app.postcommandservice.post.application.usecase;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.app.postcommandservice.collab.application.dto.CollabResponse;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.post.application.commands.CheckPostCollabLinkStatusCommand;
import com.app.postcommandservice.post.application.dto.CheckPostCollabLinkStatusResult;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;

@Service
@RequiredArgsConstructor
public class CheckPostCollabLinkStatusUseCase {

    private final PostRepository postRepository;
    private final CollabRepository collabRepository;

    public CheckPostCollabLinkStatusResult check(CheckPostCollabLinkStatusCommand command) {
        var post = postRepository.findById(command.postId())
                .orElseThrow(() -> new PostNotFoundException(command.postId()));

        assertOwnership(post, command.currentUserId());

        if (post.getCollabId() == null) {
            return CheckPostCollabLinkStatusResult.unlinked();
        }

        return collabRepository.findById(post.getCollabId())
                .map(this::toCollabResponse)
                .map(CheckPostCollabLinkStatusResult::linked)
                .orElseGet(CheckPostCollabLinkStatusResult::unlinked);
    }

    private void assertOwnership(Post post, UUID currentUserId) {
        if (!post.getUserId().value().equals(currentUserId)) {
            throw new PostOwnershipException(post.getId().value(), currentUserId);
        }
    }

    private CollabResponse toCollabResponse(Collab collab) {
        return new CollabResponse(
                collab.getId(),
                collab.getTitle().value(),
                collab.getCreatedBy().value(),
                collab.getCollabStatus(),
                collab.getCreatedAt()
        );
    }
}
