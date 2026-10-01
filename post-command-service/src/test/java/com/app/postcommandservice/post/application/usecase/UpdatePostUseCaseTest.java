package com.app.postcommandservice.post.application.usecase;

import java.time.Instant;
import java.util.List;
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

import com.app.postcommandservice.post.application.commands.UpdatePostCommand;
import com.app.postcommandservice.post.application.repository.PostRepository;
import com.app.postcommandservice.post.domain.events.PostUpdatedDomainEvent;
import com.app.postcommandservice.post.domain.exception.PostOwnershipException;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.infrastructure.events.PostUpdatedEvent;
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
class UpdatePostUseCaseTest {

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
    private UpdatePostUseCase updatePostUseCase;

    @Test
    void shouldUpdatePostSuccessfullyAndPublishEventWhenValuesHaveChanged() {
        var existingPost = persistedPost(OWNER_ID, "old", Set.of("java"));
        var updatedPost = persistedPost(OWNER_ID, "new", Set.of("spring"));
        var command = new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "new",
                Set.of("spring")
        );
        var updatedEvent = updatedEvent(updatedPost);

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(updatedPost);
        when(postEventMapper.toPostUpdatedEvent(any(UUID.class), any(UUID.class), eq(updatedPost), any(Instant.class)))
                .thenReturn(updatedEvent);
        when(jsonMapper.toJson(updatedEvent)).thenReturn("{\"event\":\"payload\"}");

        var response = updatePostUseCase.updatePost(command);

        assertThat(response.description()).isEqualTo("new");
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getDescription().value()).isEqualTo("new");
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostUpdatedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostUpdatedDomainEvent.class));
    }

    @Test
    void shouldLeaveExistingMediaUntouchedWhenDescriptionAndTagsChange() {
        var existingPost = persistedPost(OWNER_ID, "old", Set.of("java"));
        var command = new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "new description",
                Set.of("spring")
        );

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));
        when(postRepository.saveAndFlush(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(postEventMapper.toPostUpdatedEvent(any(UUID.class), any(UUID.class), any(Post.class), any(Instant.class)))
                .thenReturn(updatedEvent(existingPost));
        when(jsonMapper.toJson(any(PostUpdatedEvent.class))).thenReturn("{\"event\":\"payload\"}");

        updatePostUseCase.updatePost(command);

        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getMedia()).isEqualTo(existingPost.getMedia());
        assertThat(postCaptor.getValue().getDescription().value()).isEqualTo("new description");
    }

    @Test
    void shouldReturnExistingPostWithoutDbUpdatesOrEventsWhenNoFieldsAreActuallyChanged() {
        var existingPost = persistedPost(OWNER_ID, "same", Set.of("java"));

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));

        var response = updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "same",
                Set.of("java")
        ));

        assertThat(response.postId()).isEqualTo(existingPost.getId().value());
        assertThat(response.updatedAt()).isEqualTo(existingPost.getUpdatedAt());
        verify(postRepository, never()).saveAndFlush(any(Post.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostUpdatedDomainEvent.class));
    }

    @Test
    void shouldThrowPostOwnershipExceptionWhenUpdaterIsNotThePostOwner() {
        var existingPost = persistedPost(UUID.randomUUID(), "same", Set.of("java"));

        when(postRepository.findById(POST_ID)).thenReturn(Optional.of(existingPost));

        assertThatThrownBy(() -> updatePostUseCase.updatePost(new UpdatePostCommand(
                existingPost.getId().value(),
                OWNER_ID,
                "new",
                Set.of()
        )))
                .isInstanceOf(PostOwnershipException.class)
                .hasMessageContaining(existingPost.getId().value().toString());
    }

    private Post persistedPost(UUID ownerId, String description, Set<String> postTags) {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(ownerId),
                null,
                new PostInfo(
                        new PostDescription(description),
                        new PostTags(new java.util.LinkedHashSet<>(postTags)),
                        PostType.BASIC
                ),
                List.of(PostMedia.create(POST_ID, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of(), 1)),
                PostStatus.ACCEPTED,
                now,
                now
        );
    }

    private PostUpdatedEvent updatedEvent(Post post) {
        return PostUpdatedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(UUID.randomUUID())
                .occurredAt(Instant.now())
                .postId(post.getId().value())
                .userId(post.getUserId().value())
                .collabId(post.getCollabId())
                .postType(post.getPostType())
                .description(post.getDescription().value())
                .postTags(post.getTags().value())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
