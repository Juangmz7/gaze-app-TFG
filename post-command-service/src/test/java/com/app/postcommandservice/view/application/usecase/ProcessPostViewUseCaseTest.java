package com.app.postcommandservice.view.application.usecase;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.repository.PostViewRepository;
import com.app.postcommandservice.view.application.repository.PostViewValidationRepository;
import com.app.postcommandservice.view.domain.events.PostViewedDomainEvent;
import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;
import com.app.postcommandservice.view.infrastructure.mapper.PostViewEventMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessPostViewUseCaseTest {

    private static final UUID COMMAND_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID VIEW_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PostViewRepository postViewRepository;

    @Mock
    private PostViewValidationRepository postViewValidationRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private PostViewEventMapper postViewEventMapper;

    @Mock
    private JsonMapper jsonMapper;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<PostView> postViewCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @InjectMocks
    private ProcessPostViewUseCase processPostViewUseCase;

    @Test
    void shouldAugmentViewPersistItAndPublishPostViewedEventWhenCommandIsValid() {
        var command = command();
        var savedView = new PostView(
                VIEW_ID,
                new com.app.postcommandservice.post.domain.model.valueobj.PostId(POST_ID),
                new com.app.postcommandservice.shared.domain.model.user.valueobj.UserId(USER_ID),
                PostViewSource.USER_PROFILE,
                2,
                5000,
                3500,
                70,
                PostViewExitReason.SCROLL_NEXT,
                Instant.now(),
                3
        );
        var event = PostViewedEvent.builder()
                .id(UUID.randomUUID())
                .correlationId(CORRELATION_ID)
                .occurredAt(Instant.now())
                .viewId(VIEW_ID)
                .postId(POST_ID)
                .userId(USER_ID)
                .source(PostViewSource.USER_PROFILE)
                .feedPosition(2)
                .durationMs(5000)
                .timeWatchedMs(3500)
                .completionPercent(70)
                .exitReason(PostViewExitReason.SCROLL_NEXT)
                .serverTimestamp(savedView.getServerTimestamp())
                .replayCount(3)
                .build();

        when(postViewValidationRepository.existsPost(POST_ID)).thenReturn(true);
        when(postViewRepository.countByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(2L);
        when(postViewRepository.save(any(PostView.class))).thenReturn(savedView);
        when(postViewEventMapper.toPostViewedEvent(any(UUID.class), eq(CORRELATION_ID), eq(savedView), any(Instant.class)))
                .thenReturn(event);
        when(jsonMapper.toJson(event)).thenReturn("{\"viewed\":true}");

        processPostViewUseCase.process(command);

        verify(postViewRepository).save(postViewCaptor.capture());
        var persistedView = postViewCaptor.getValue();
        assertThat(persistedView.getViewId()).isEqualTo(VIEW_ID);
        assertThat(persistedView.getUserId().value()).isEqualTo(USER_ID);
        assertThat(persistedView.getPostId().value()).isEqualTo(POST_ID);
        assertThat(persistedView.getReplayCount()).isEqualTo(3);
        assertThat(persistedView.getServerTimestamp()).isNotNull();

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(outboxEventCaptor.getValue().getEventType()).isEqualTo(PostViewedEvent.class.getSimpleName());
        assertThat(outboxEventCaptor.getValue().getStatus()).isEqualTo(EventStatus.PENDING);
        verify(applicationEventPublisher).publishEvent(any(PostViewedDomainEvent.class));
    }

    @Test
    void shouldAbortProcessingWhenPostDoesNotExist() {
        when(postViewValidationRepository.existsPost(POST_ID)).thenReturn(false);

        processPostViewUseCase.process(command());

        verify(postViewRepository, never()).countByPostIdAndUserId(any(UUID.class), any(UUID.class));
        verify(postViewRepository, never()).save(any(PostView.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(PostViewedDomainEvent.class));
    }

    private ProcessPostViewCommand command() {
        return new ProcessPostViewCommand(
                COMMAND_ID,
                CORRELATION_ID,
                Instant.now(),
                VIEW_ID,
                POST_ID,
                USER_ID,
                PostViewSource.USER_PROFILE,
                2,
                5000,
                3500,
                70,
                PostViewExitReason.SCROLL_NEXT
        );
    }
}
