package com.app.postcommandservice.collab.application.usecase;

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

import com.app.postcommandservice.collab.application.commands.OpenCollabForExistingPostCommand;
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
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
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
class OpenCollabForExistingPostUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private CollabRepository collabRepository;

    @Mock
    private CollabMemberRepository collabMemberRepository;

    @Mock
    private CollabRequestIdempotencyRepository collabRequestIdempotencyRepository;

    @Mock
    private PostRepository postRepository;

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
    private OpenCollabForExistingPostUseCase useCase;

    @Test
    void shouldCreateCollabMemberUpdatePostAndPublishEventForExistingActiveOwnedPost() {
        var collabId = UUID.randomUUID();
        var existingPost = persistedPost(OWNER_ID, null, PostType.BASIC, PostStatus.ACTIVE);
        var savedPost = persistedPost(OWNER_ID, collabId, PostType.COLAB, PostStatus.ACTIVE);
        var savedCollab = openCollab(collabId);
        var savedMember = creatorMember(collabId);
        var event = openedEvent(savedCollab, savedMember, savedPost);

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(collabRepository.save(any(Collab.class))).thenReturn(savedCollab);
        when(collabMemberRepository.save(any(CollabMember.class))).thenReturn(savedMember);
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(savedPost);
        when(collabEventMapper.toCollabOpenedEvent(
                any(UUID.class),
                eq(CORRELATION_ID),
                eq(savedCollab),
                eq(savedMember),
                eq(savedPost),
                any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"event\":\"payload\"}");

        var response = useCase.open(new OpenCollabForExistingPostCommand(
                POST_ID,
                CORRELATION_ID,
                OWNER_ID,
                "Existing post collab"
        ));

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.collabStatus()).isEqualTo(ColabStatus.OPEN);
        assertThat(response.post().postId()).isEqualTo(POST_ID);
        assertThat(response.post().collabId()).isEqualTo(collabId);
        assertThat(response.post().postType()).isEqualTo(PostType.COLAB);
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getCollabId()).isEqualTo(collabId);
        assertThat(postCaptor.getValue().getPostType()).isEqualTo(PostType.COLAB);
        verify(collabRequestIdempotencyRepository).save(CORRELATION_ID, collabId);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(CollabOpenedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(CollabOpenedDomainEvent.class));
    }

    @Test
    void shouldReturnExistingCollabDataWithoutSideEffectsWhenCorrelationIdAlreadyExists() {
        var collabId = UUID.randomUUID();
        var existingCollab = openCollab(collabId);
        var linkedPost = persistedPost(OWNER_ID, collabId, PostType.COLAB, PostStatus.ACTIVE);

        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.of(collabId));
        when(collabRepository.findById(collabId)).thenReturn(Optional.of(existingCollab));
        when(postRepository.findByCollabId(collabId)).thenReturn(Optional.of(linkedPost));

        var response = useCase.open(new OpenCollabForExistingPostCommand(
                POST_ID,
                CORRELATION_ID,
                OWNER_ID,
                "ignored"
        ));

        assertThat(response.collabId()).isEqualTo(collabId);
        assertThat(response.post().postId()).isEqualTo(POST_ID);
        verify(postRepository, never()).findById(POST_ID);
        verify(collabRepository, never()).save(any(Collab.class));
        verify(collabMemberRepository, never()).save(any(CollabMember.class));
        verify(postRepository, never()).saveAndFlush(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(CollabOpenedDomainEvent.class));
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenRequesterDoesNotOwnPost() {
        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(persistedPost(UUID.randomUUID(), null, PostType.BASIC, PostStatus.ACTIVE)));

        assertThatThrownBy(() -> useCase.open(new OpenCollabForExistingPostCommand(
                POST_ID,
                CORRELATION_ID,
                OWNER_ID,
                "Existing post collab"
        )))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(collabRepository, never()).save(any(Collab.class));
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenPostDoesNotExist() {
        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.open(new OpenCollabForExistingPostCommand(
                POST_ID,
                CORRELATION_ID,
                OWNER_ID,
                "Existing post collab"
        )))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());
    }

    @Test
    void shouldThrowPostNotActiveExceptionWhenPostIsDeleted() {
        when(collabRequestIdempotencyRepository.findEntityIdByCorrelationId(CORRELATION_ID))
                .thenReturn(Optional.empty());
        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(persistedPost(OWNER_ID, null, PostType.BASIC, PostStatus.DELETED)));

        assertThatThrownBy(() -> useCase.open(new OpenCollabForExistingPostCommand(
                POST_ID,
                CORRELATION_ID,
                OWNER_ID,
                "Existing post collab"
        )))
                .isInstanceOf(PostNotActiveException.class)
                .hasMessageContaining("open a collab");

        verify(collabRepository, never()).save(any(Collab.class));
    }

    private Post persistedPost(UUID ownerId, UUID collabId, PostType postType, PostStatus postStatus) {
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
                postStatus,
                now,
                now
        );
    }

    private Collab openCollab(UUID collabId) {
        return new Collab(
                collabId,
                new CollabTitle("Existing post collab"),
                new UserId(OWNER_ID),
                ColabStatus.OPEN,
                Instant.now()
        );
    }

    private CollabMember creatorMember(UUID collabId) {
        return new CollabMember(
                collabId,
                new UserId(OWNER_ID),
                CollabMemberStatus.ACCEPTED,
                CollabMemberRole.ADMIN,
                Instant.now()
        );
    }

    private CollabOpenedEvent openedEvent(Collab collab, CollabMember member, Post post) {
        return CollabOpenedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .collabId(collab.getId())
                .title(collab.getTitle().value())
                .createdBy(collab.getCreatedBy().value())
                .collabStatus(collab.getCollabStatus())
                .collabCreatedAt(collab.getCreatedAt())
                .creatorMemberStatus(member.getCollabMemberStatus())
                .creatorRole(member.getRole())
                .creatorMemberCreatedAt(member.getCreatedAt())
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .postCollabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .taggedUsers(post.getTaggedUsers().value())
                .postTags(post.getTags().value())
                .postCreatedAt(post.getCreatedAt())
                .postUpdatedAt(post.getUpdatedAt())
                .build();
    }
}
