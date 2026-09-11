package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
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

import com.app.postcommandservice.collab.application.repository.CollabMemberRepository;
import com.app.postcommandservice.collab.application.repository.CollabRepository;
import com.app.postcommandservice.collab.domain.events.CollabLinkedDomainEvent;
import com.app.postcommandservice.collab.domain.exception.CollabAdminAccessDeniedException;
import com.app.postcommandservice.collab.domain.exception.CollabNotFoundException;
import com.app.postcommandservice.collab.domain.exception.CollabNotOpenException;
import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.collab.infrastructure.events.CollabLinkedEvent;
import com.app.postcommandservice.collab.infrastructure.mapper.CollabEventMapper;
import com.app.postcommandservice.post.application.commands.LinkExistingPostToCollabCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkExistingPostToCollabUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private CollabRepository collabRepository;

    @Mock
    private CollabMemberRepository collabMemberRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private CollabEventMapper collabEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<Post> postCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private LinkExistingPostToCollabUseCase useCase;

    @Test
    void shouldSuccessfullyUpdatePostsCollabIdAndPublishEventWhenRequesterIsBothPostOwnerAndCollabAdmin() {
        var existingPost = persistedPost(OWNER_ID, null, PostType.BASIC);
        var savedPost = persistedPost(OWNER_ID, COLLAB_ID, PostType.COLAB);
        var collab = openCollab(COLLAB_ID);
        var membership = acceptedAdminMember(COLLAB_ID, OWNER_ID);
        var event = linkedEvent(savedPost);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, OWNER_ID)).thenReturn(Optional.of(membership));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabLinkedEvent(any(UUID.class), any(UUID.class), eq(savedPost), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID));

        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.collabId()).isEqualTo(COLLAB_ID);
        assertThat(response.postType()).isEqualTo(PostType.COLAB);
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getCollabId()).isEqualTo(COLLAB_ID);
        assertThat(postCaptor.getValue().getPostType()).isEqualTo(PostType.COLAB);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabLinkedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabLinkedDomainEvent.class));
    }

    @Test
    void shouldOverwriteExistingCollabIdWithoutErrorsIfThePostWasAlreadyLinked() {
        var oldCollabId = UUID.randomUUID();
        var existingPost = persistedPost(OWNER_ID, oldCollabId, PostType.COLAB);
        var savedPost = persistedPost(OWNER_ID, COLLAB_ID, PostType.COLAB);
        var collab = openCollab(COLLAB_ID);
        var membership = acceptedAdminMember(COLLAB_ID, OWNER_ID);
        var event = linkedEvent(savedPost);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, OWNER_ID)).thenReturn(Optional.of(membership));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabLinkedEvent(any(UUID.class), any(UUID.class), eq(savedPost), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID));

        assertThat(response.collabId()).isEqualTo(COLLAB_ID);
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getCollabId()).isEqualTo(COLLAB_ID);
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenPostDoesNotExist() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowCollabNotFoundExceptionWhenCollabDoesNotExist() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(OWNER_ID, null, PostType.BASIC)));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(CollabNotFoundException.class)
                .hasMessageContaining(COLLAB_ID.toString());
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenRequesterIsCollabAdminButNotPostOwner() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(UUID.randomUUID(), null, PostType.BASIC)));

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(collabRepository, never()).findById(any(UUID.class));
    }

    @Test
    void shouldThrowCollabAdminAccessDeniedExceptionWhenRequesterIsPostOwnerButNotCollabAdmin() {
        var existingPost = persistedPost(OWNER_ID, null, PostType.BASIC);
        var collab = openCollab(COLLAB_ID);
        var membership = new CollabMember(
                COLLAB_ID,
                new UserId(OWNER_ID),
                CollabMemberStatus.ACCEPTED,
                CollabMemberRole.MEMBER,
                Instant.now()
        );

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, OWNER_ID)).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(CollabAdminAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());
    }

    @Test
    void shouldThrowCollabAdminAccessDeniedExceptionWhenMembershipIsMissing() {
        var existingPost = persistedPost(OWNER_ID, null, PostType.BASIC);
        var collab = openCollab(COLLAB_ID);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collab));
        when(collabMemberRepository.findByCollabIdAndUserId(COLLAB_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(CollabAdminAccessDeniedException.class)
                .hasMessageContaining(COLLAB_ID.toString());
    }

    @Test
    void shouldThrowCollabNotOpenExceptionWhenTargetCollabIsClosed() {
        var existingPost = persistedPost(OWNER_ID, null, PostType.BASIC);
        var closedCollab = new Collab(
                COLLAB_ID,
                new CollabTitle("Closed collab"),
                new UserId(OWNER_ID),
                ColabStatus.CLOSED,
                Instant.now()
        );

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.findById(COLLAB_ID)).thenReturn(Optional.of(closedCollab));

        assertThatThrownBy(() -> useCase.link(new LinkExistingPostToCollabCommand(POST_ID, COLLAB_ID, OWNER_ID)))
                .isInstanceOf(CollabNotOpenException.class)
                .hasMessageContaining("OPEN");

        verify(collabMemberRepository, never()).findByCollabIdAndUserId(any(UUID.class), any(UUID.class));
    }

    private Post persistedPost(UUID ownerId, UUID collabId, PostType postType) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                collabId,
                new com.app.postcommandservice.post.domain.model.PostInfo(null, new PostDescription("hello"),
                        new PostTaggedUsers(Set.of("alice")), new PostTags(Set.of("spring")), postType),
                java.util.List.of(new com.app.postcommandservice.post.domain.model.PostMedia(UUID.randomUUID(),
                        "https://cdn.test/post.jpg", null,
                        com.app.postcommandservice.post.domain.model.valueobj.MediaType.IMAGE, null, 1)),
                PostStatus.ACTIVE,
                now,
                now
        );
    }

    private Collab openCollab(UUID collabId) {
        return new Collab(
                collabId,
                new CollabTitle("Open collab"),
                new UserId(OWNER_ID),
                ColabStatus.OPEN,
                Instant.now()
        );
    }

    private CollabMember acceptedAdminMember(UUID collabId, UUID userId) {
        return new CollabMember(
                collabId,
                new UserId(userId),
                CollabMemberStatus.ACCEPTED,
                CollabMemberRole.ADMIN,
                Instant.now()
        );
    }

    private CollabLinkedEvent linkedEvent(Post post) {
        return CollabLinkedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
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
