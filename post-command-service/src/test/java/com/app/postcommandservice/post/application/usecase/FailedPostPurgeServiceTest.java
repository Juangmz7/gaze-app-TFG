package com.app.postcommandservice.post.application.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.postcommandservice.SelfProvider;
import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository;
import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository.PurgeCursor;
import com.app.postcommandservice.post.application.repository.FailedPostPurgeRepository.PurgeablePost;
import com.app.postcommandservice.post.infrastructure.config.FailedPostPurgeProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FailedPostPurgeServiceTest {

    private static final Instant PURGED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final DeleteUnacceptedPostUseCase.Outcome POST_DELETED = new DeleteUnacceptedPostUseCase.Outcome(true, false);

    @Mock
    private FailedPostPurgeRepository failedPostPurgeRepository;

    @Mock
    private DeleteUnacceptedPostUseCase deleteUnacceptedPostUseCase;

    @Captor
    private ArgumentCaptor<Instant> purgedBeforeCaptor;

    private final FailedPostPurgeProperties properties = new FailedPostPurgeProperties();

    private FailedPostPurgeService service;

    /**
     * In production {@code self} provides the Spring proxy of this bean; here it provides the
     * same instance (see {@link SelfProvider}).
     */
    @BeforeEach
    void setUp() {
        var self = new SelfProvider<FailedPostPurgeService>();
        service = self.set(new FailedPostPurgeService(failedPostPurgeRepository, deleteUnacceptedPostUseCase, properties,
                self));
    }

    @Test
    void shouldSelectOnlyFailedPostsPurgedBeforeTheRetentionCutoffAndDeleteEachOne() {
        var first = purgeable();
        var second = purgeable();
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(200)))
                .thenReturn(List.of(first, second));
        when(deleteUnacceptedPostUseCase.delete(any(UUID.class))).thenReturn(POST_DELETED);

        Instant before = Instant.now();
        service.run();
        Instant after = Instant.now();

        verify(failedPostPurgeRepository).findPurgeablePosts(purgedBeforeCaptor.capture(), isNull(), eq(200));
        assertThat(purgedBeforeCaptor.getValue())
                .isBetween(before.minus(Duration.ofDays(7)), after.minus(Duration.ofDays(7)));
        verify(deleteUnacceptedPostUseCase).delete(first.postId());
        verify(deleteUnacceptedPostUseCase).delete(second.postId());
    }

    @Test
    void shouldUseTheConfiguredRetention() {
        properties.setRetention(Duration.ofDays(2));
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(200))).thenReturn(List.of());

        service.run();

        verify(failedPostPurgeRepository).findPurgeablePosts(purgedBeforeCaptor.capture(), isNull(), eq(200));
        assertThat(purgedBeforeCaptor.getValue()).isCloseTo(Instant.now().minus(Duration.ofDays(2)), within(Duration.ofSeconds(5)));
        verifyNoInteractions(deleteUnacceptedPostUseCase);
    }

    @Test
    void shouldContinueWithTheNextPostWhenOnePostFails() {
        var failing = purgeable();
        var next = purgeable();
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(200)))
                .thenReturn(List.of(failing, next));
        when(deleteUnacceptedPostUseCase.delete(failing.postId())).thenThrow(new IllegalStateException("db down"));
        when(deleteUnacceptedPostUseCase.delete(next.postId())).thenReturn(POST_DELETED);

        assertThatNoException().isThrownBy(service::run);

        verify(deleteUnacceptedPostUseCase).delete(next.postId());
    }

    @Test
    void shouldSkipAPostThatIsNoLongerFailedWithoutAbortingTheRun() {
        var skipped = purgeable();
        var next = purgeable();
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(200)))
                .thenReturn(List.of(skipped, next));
        when(deleteUnacceptedPostUseCase.delete(skipped.postId())).thenReturn(DeleteUnacceptedPostUseCase.Outcome.NOT_DELETED);
        when(deleteUnacceptedPostUseCase.delete(next.postId())).thenReturn(new DeleteUnacceptedPostUseCase.Outcome(true, true));

        service.run();

        verify(deleteUnacceptedPostUseCase).delete(next.postId());
    }

    @Test
    void shouldReadTheNextKeysetPageAndStopAtMaxBatchesPerRun() {
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(2);
        var firstPage = List.of(purgeable(), purgeable());
        var secondPage = List.of(purgeable(), purgeable());
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(2))).thenReturn(firstPage);
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), eq(firstPage.getLast().cursor()), eq(2)))
                .thenReturn(secondPage);
        when(deleteUnacceptedPostUseCase.delete(any(UUID.class))).thenReturn(POST_DELETED);

        service.run();

        verify(failedPostPurgeRepository, times(2)).findPurgeablePosts(any(Instant.class), any(), eq(2));
        verify(failedPostPurgeRepository, never())
                .findPurgeablePosts(any(Instant.class), eq(secondPage.getLast().cursor()), eq(2));
        verify(deleteUnacceptedPostUseCase, times(4)).delete(any(UUID.class));
    }

    @Test
    void shouldStopAfterAShortPage() {
        properties.setBatchSize(3);
        var page = new ArrayList<>(List.of(purgeable(), purgeable()));
        when(failedPostPurgeRepository.findPurgeablePosts(any(Instant.class), isNull(), eq(3))).thenReturn(page);
        when(deleteUnacceptedPostUseCase.delete(any(UUID.class))).thenReturn(POST_DELETED);

        service.run();

        verify(failedPostPurgeRepository, times(1)).findPurgeablePosts(any(Instant.class), any(), eq(3));
        verify(failedPostPurgeRepository, never())
                .findPurgeablePosts(any(Instant.class), any(PurgeCursor.class), eq(3));
    }

    private static PurgeablePost purgeable() {
        return new PurgeablePost(UUID.randomUUID(), PURGED_AT);
    }
}
