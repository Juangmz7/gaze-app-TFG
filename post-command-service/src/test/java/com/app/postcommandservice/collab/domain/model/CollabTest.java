package com.app.postcommandservice.collab.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.collab.domain.model.valueobj.ColabStatus;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabJoinInstructions;
import com.app.postcommandservice.collab.domain.model.valueobj.CollabTitle;
import com.app.postcommandservice.shared.domain.model.user.valueobj.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class CollabTest {

    private static final UUID COLLAB_ID = UUID.randomUUID();
    private static final UserId CREATOR = new UserId(UUID.randomUUID());

    @Test
    void shouldOpenWithoutJoinInstructionsByDefault() {
        var collab = Collab.open(COLLAB_ID, new CollabTitle("Collab"), CREATOR);

        assertThat(collab.getJoinInstructions()).isNull();
        assertThat(collab.getCollabStatus()).isEqualTo(ColabStatus.OPEN);
    }

    @Test
    void shouldOpenWithTheGivenJoinInstructions() {
        var joinInstructions = new CollabJoinInstructions("Chat: https://chat");

        var collab = Collab.open(COLLAB_ID, new CollabTitle("Collab"), CREATOR, joinInstructions);

        assertThat(collab.getJoinInstructions()).isEqualTo(joinInstructions);
    }

    @Test
    void shouldReplaceAndClearJoinInstructionsKeepingTheRestOfTheCollab() {
        var createdAt = Instant.now();
        var collab = new Collab(COLLAB_ID, new CollabTitle("Collab"), CREATOR, ColabStatus.CLOSED, createdAt,
                new CollabJoinInstructions("old"));

        var updated = collab.updateJoinInstructions(new CollabJoinInstructions("new"));
        var cleared = updated.updateJoinInstructions(null);

        assertThat(updated.getJoinInstructions().value()).isEqualTo("new");
        assertThat(updated.getId()).isEqualTo(COLLAB_ID);
        assertThat(updated.getCollabStatus()).isEqualTo(ColabStatus.CLOSED);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(cleared.getJoinInstructions()).isNull();
    }

    @Test
    void shouldKeepJoinInstructionsWhenClosingOrDeleting() {
        var joinInstructions = new CollabJoinInstructions("Chat: https://chat");
        var collab = Collab.open(COLLAB_ID, new CollabTitle("Collab"), CREATOR, joinInstructions);

        assertThat(collab.close().getJoinInstructions()).isEqualTo(joinInstructions);
        assertThat(collab.delete().getJoinInstructions()).isEqualTo(joinInstructions);
    }
}
