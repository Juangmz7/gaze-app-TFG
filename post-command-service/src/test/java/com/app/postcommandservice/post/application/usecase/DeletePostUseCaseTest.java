package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.LinkedHashSet;
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

import com.app.postcommandservice.post.application.commands.DeletePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.events.PostDeletedDomainEvent;
import com.app.postcommandservice.post.domain.exception.PostNotActiveException;
import com.app.postcommandservice.post.domain.exception.PostNotFoundException;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTaggedUsers;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.infrastructure.events.PostDeletedEvent;
import com.app.postcommandservice.post.infrastructure.mapper.PostEventMapper;
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
class DeletePostUseCaseTest {

    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private PostRepository postRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostEventMapper postEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<Post> postCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private DeletePostUseCase deletePostUseCase;

    @Test
    void shouldSuccessfullyChangeStatusToDeletedAndPublishEventWhenOwnerDeletesAnActivePost() {
        var existingPost = persistedPost(OWNER_ID, PostStatus.ACTIVE);
        var deletedPost = persistedPost(OWNER_ID, PostStatus.DELETED);
        var deletedEvent = new PostDeletedEvent(existingPost.getId().value(), Instant.now());

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(deletedPost);
        when(postEventMapper.toPostDeletedEvent(eq(POST_ID), any(Instant.class))).thenReturn(deletedEvent);
        when(jsonMapper.toJson(deletedEvent)).thenReturn("{\"postId\":\"%s\",\"occurredAt\":\"%s\"}"
                .formatted(POST_ID, deletedEvent.occurredAt()));

        deletePostUseCase.deletePost(new DeletePostCommand(POST_ID, OWNER_ID));

        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getStatus()).isEqualTo(PostStatus.DELETED);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostDeletedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(jsonMapper).toJson(deletedEvent);
        verify(applicationEventPublisher).publishEvent(any(PostDeletedDomainEvent.class));
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenTheUserIsNotThePostOwner() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(UUID.randomUUID(), PostStatus.ACTIVE)));

        assertThatThrownBy(() -> deletePostUseCase.deletePost(new DeletePostCommand(POST_ID, OWNER_ID)))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(postRepository, never()).saveAndFlush(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldThrowPostNotFoundExceptionWhenThePostDoesNotExist() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deletePostUseCase.deletePost(new DeletePostCommand(POST_ID, OWNER_ID)))
                .isInstanceOf(PostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(postRepository, never()).saveAndFlush(any(Post.class));
    }

    @Test
    void shouldThrowPostNotActiveExceptionWhenTryingToDeleteAnAlreadyDeletedPost() {
        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(persistedPost(OWNER_ID, PostStatus.DELETED)));

        assertThatThrownBy(() -> deletePostUseCase.deletePost(new DeletePostCommand(POST_ID, OWNER_ID)))
                .isInstanceOf(PostNotActiveException.class)
                .hasMessageContaining("ACTIVE");

        verify(postRepository, never()).saveAndFlush(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void shouldPublishEventContainingOnlyPostIdAndOccurredAtFields() {
        var existingPost = persistedPost(OWNER_ID, PostStatus.ACTIVE);
        var deletedPost = persistedPost(OWNER_ID, PostStatus.DELETED);
        var deletedEvent = new PostDeletedEvent(existingPost.getId().value(), Instant.now());

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(deletedPost);
        when(postEventMapper.toPostDeletedEvent(eq(POST_ID), any(Instant.class))).thenReturn(deletedEvent);
        when(jsonMapper.toJson(deletedEvent)).thenReturn("{\"postId\":\"%s\",\"occurredAt\":\"%s\"}"
                .formatted(POST_ID, deletedEvent.occurredAt()));

        deletePostUseCase.deletePost(new DeletePostCommand(POST_ID, OWNER_ID));

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getPayload())
                .contains("postId")
                .contains("occurredAt");
        assertThat(outboxEventCaptor.getValue().getPayload())
                .doesNotContain("userId")
                .doesNotContain("description")
                .doesNotContain("createdAt")
                .doesNotContain("updatedAt");
    }

    private Post persistedPost(UUID ownerId, PostStatus status) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                new PostDescription("description"),
                new PostTaggedUsers(new LinkedHashSet<>(Set.of("alice"))),
                new PostTags(new LinkedHashSet<>(Set.of("java"))),
                status,
                now,
                now
        );
    }
}
