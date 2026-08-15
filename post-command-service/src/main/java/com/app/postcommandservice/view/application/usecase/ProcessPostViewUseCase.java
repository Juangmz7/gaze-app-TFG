package com.app.postcommandservice.view.application.usecase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.enums.EventStatus;
import com.app.postcommandservice.shared.infrastructure.mapper.JsonMapper;
import com.app.postcommandservice.shared.infrastructure.repository.OutboxEventRepository;
import com.app.postcommandservice.view.application.commands.ProcessPostViewCommand;
import com.app.postcommandservice.view.application.repository.PostViewRepository;
import com.app.postcommandservice.view.application.repository.PostViewValidationRepository;
import com.app.postcommandservice.view.domain.events.PostViewedDomainEvent;
import com.app.postcommandservice.view.domain.model.PostView;
import com.app.postcommandservice.view.infrastructure.events.PostViewedEvent;
import com.app.postcommandservice.view.infrastructure.mapper.PostViewEventMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPostViewUseCase {

    private final PostViewRepository postViewRepository;
    private final PostViewValidationRepository postViewValidationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PostViewEventMapper postViewEventMapper;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void process(ProcessPostViewCommand command) {
        var activePost = postViewValidationRepository.findActivePost(command.postId());
        if (activePost.isEmpty()) {
            log.info("Discarding post view command {} because post {} does not exist or is not ACTIVE",
                    command.id(), command.postId());
            return;
        }

        var postOwnerId = activePost.get().ownerUserId();
        if (postViewValidationRepository.existsBlockRelationship(command.userId(), postOwnerId)) {
            log.info("Discarding post view command {} because users {} and {} are blocked",
                    command.id(), command.userId(), postOwnerId);
            return;
        }

        var replayCount = Math.toIntExact(postViewRepository.countByPostIdAndUserId(command.postId(), command.userId()) + 1);
        var serverTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);

        var savedView = postViewRepository.save(new PostView(
                command.viewId(),
                new com.app.postcommandservice.post.domain.model.valueobj.PostId(command.postId()),
                new UserId(command.userId()),
                command.source(),
                command.feedPosition(),
                command.durationMs(),
                command.timeWatchedMs(),
                command.completionPercent(),
                command.exitReason(),
                serverTimestamp,
                replayCount
        ));

        var outboxId = UUID.randomUUID();
        var event = postViewEventMapper.toPostViewedEvent(
                outboxId,
                command.correlationId(),
                savedView,
                serverTimestamp
        );
        saveOutboxEvent(outboxId, command.correlationId(), event);

        applicationEventPublisher.publishEvent(new PostViewedDomainEvent(outboxId));
    }

    private void saveOutboxEvent(UUID outboxId, UUID correlationId, PostViewedEvent event) {
        outboxEventRepository.save(
                OutboxEvent.builder()
                        .id(outboxId)
                        .correlationId(correlationId)
                        .payload(jsonMapper.toJson(event))
                        .eventType(PostViewedEvent.class.getSimpleName())
                        .status(EventStatus.PENDING)
                        .build()
        );
    }
}
