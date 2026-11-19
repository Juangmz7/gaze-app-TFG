package com.app.postcommandservice.view.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.postcommandservice.TestcontainersConfiguration;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.post.infrastructure.repository.PostJpaRepository;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.usecase.ProcessPostViewUseCase;
import com.app.postcommandservice.view.domain.model.PostViewExitReason;
import com.app.postcommandservice.view.domain.model.PostViewSource;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;
import com.app.postcommandservice.view.infrastructure.repository.PostViewJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProcessPostViewConcurrencyIT {

    private static final UUID VIEWER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private ProcessPostViewUseCase processPostViewUseCase;

    @Autowired
    private PostJpaRepository postJpaRepository;

    @Autowired
    private PostViewJpaRepository postViewJpaRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        postViewJpaRepository.deleteAll();
        postJpaRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void shouldStoreOneRowAndOneEventWhenSameViewIdIsDeliveredConcurrently() throws Exception {
        var post = seedPost();
        var viewId = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);

        List<Future<?>> deliveries = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            deliveries.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                processPostViewUseCase.process(command(post.getId(), viewId));
                return null;
            }));
        }
        for (Future<?> delivery : deliveries) {
            delivery.get(30, TimeUnit.SECONDS);
        }

        assertThat(postViewJpaRepository.count()).isEqualTo(1);
        assertThat(postViewJpaRepository.findById(viewId)).isPresent();
        assertThat(postViewedOutboxEvents()).isEqualTo(1);
    }

    @Test
    void shouldTreatDuplicateKeyFromInFlightDeliveryAsAlreadyProcessed() throws Exception {
        var post = seedPost();
        var viewId = UUID.randomUUID();
        var firstInserted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);

        // First delivery: inserts the row and keeps its transaction open until released.
        Future<?> firstDelivery = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            postViewJpaRepository.insertIfAbsent(viewId, post.getId(), VIEWER_ID,
                    PostViewSource.HOME_FEED.name(), PostViewExitReason.SCROLL_NEXT.name(),
                    1, 2000, 1500, 75, 1, Instant.now().truncatedTo(ChronoUnit.MICROS));
            firstInserted.countDown();
            awaitQuietly(releaseFirst);
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();

        // Second delivery: its existence check cannot see the uncommitted row, so it reaches the insert,
        // which blocks on the primary key until the first delivery commits.
        Future<?> secondDelivery = executor.submit(() -> processPostViewUseCase.process(command(post.getId(), viewId)));
        waitUntilInsertIsBlockedOnLock();
        releaseFirst.countDown();

        firstDelivery.get(30, TimeUnit.SECONDS);
        secondDelivery.get(30, TimeUnit.SECONDS);

        assertThat(postViewJpaRepository.count()).isEqualTo(1);
        assertThat(postViewJpaRepository.findById(viewId).orElseThrow().getReplayCount()).isEqualTo(1);
        assertThat(postViewedOutboxEvents()).isZero();
    }

    private long postViewedOutboxEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> PostViewedEvent.class.getSimpleName().equals(event.getEventType()))
                .count();
    }

    private void waitUntilInsertIsBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND query ILIKE 'INSERT INTO post_views%'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Second delivery never blocked on the post_views primary key");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("First delivery was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private PostEntity seedPost() {
        return postJpaRepository.save(PostEntity.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .collabId(null)
                .description("active")
                .postType(PostType.BASIC)
                .status(PostStatus.ACCEPTED)
                .build());
    }

    private ProcessPostViewCommand command(UUID postId, UUID viewId) {
        return new ProcessPostViewCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                viewId,
                postId,
                VIEWER_ID,
                PostViewSource.HOME_FEED,
                1,
                2000,
                1500,
                75,
                PostViewExitReason.SCROLL_NEXT
        );
    }
}
