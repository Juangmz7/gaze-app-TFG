package com.app.postcommandservice.collab.infrastructure.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.collab.domain.model.Collab;
import com.app.postcommandservice.collab.domain.model.CollabMember;
import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabJoinInstructions;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberRole;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabMemberStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.post.domain.model.Post;
import com.app.postcommandservice.post.domain.model.PostInfo;
import com.app.postcommandservice.post.domain.model.PostMedia;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostDescription;
import com.app.postcommandservice.post.domain.model.valueobj.PostId;
import com.app.postcommandservice.post.domain.model.valueobj.PostStatus;
import com.app.postcommandservice.post.domain.model.valueobj.PostTags;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class CollabEventMapperTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.randomUUID();

    private final CollabEventMapper mapper = new CollabEventMapper();

    @Test
    void shouldCarryJoinInstructionsInTheCollabOpenedEvent() {
        var event = mapper.toCollabOpenedEvent(UUID.randomUUID(), UUID.randomUUID(),
                collab(new CollabJoinInstructions("Chat: https://chat")), creator(), post(), true, Instant.now());

        assertThat(event.joinInstructions()).isEqualTo("Chat: https://chat");
    }

    @Test
    void shouldCarryNullJoinInstructionsInTheCollabOpenedEventWhenTheCollabHasNone() {
        var event = mapper.toCollabOpenedEvent(UUID.randomUUID(), UUID.randomUUID(),
                collab(null), creator(), post(), false, Instant.now());

        assertThat(event.joinInstructions()).isNull();
    }

    @Test
    void shouldMapTheJoinInstructionsUpdatedEvent() {
        var eventId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var occurredAt = Instant.now();

        var event = mapper.toCollabJoinInstructionsUpdatedEvent(eventId, correlationId,
                collab(new CollabJoinInstructions("Repo: https://repo")), occurredAt);
        var clearedEvent = mapper.toCollabJoinInstructionsUpdatedEvent(eventId, correlationId, collab(null), occurredAt);

        assertThat(event.id()).isEqualTo(eventId);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.collabId()).isEqualTo(COLLAB_ID);
        assertThat(event.joinInstructions()).isEqualTo("Repo: https://repo");
        assertThat(clearedEvent.joinInstructions()).isNull();
    }

    private Collab collab(CollabJoinInstructions joinInstructions) {
        return new Collab(COLLAB_ID, new CollabTitle("Collab"), new UserId(USER_ID), ColabStatus.OPEN, Instant.now(),
                joinInstructions);
    }

    private CollabMember creator() {
        return new CollabMember(COLLAB_ID, new UserId(USER_ID), CollabMemberStatus.ACCEPTED, CollabMemberRole.ADMIN,
                Instant.now());
    }

    private Post post() {
        var now = Instant.now();
        return new Post(
                new PostId(POST_ID),
                new UserId(USER_ID),
                COLLAB_ID,
                new PostInfo(new PostDescription("hello"), new PostTags(Set.of("spring")), PostType.COLLAB),
                List.of(PostMedia.create(POST_ID, "https://cdn/image.jpg", null, MediaType.IMAGE, null, Set.of(), 1)),
                PostStatus.PENDING,
                now,
                now
        );
    }
}
