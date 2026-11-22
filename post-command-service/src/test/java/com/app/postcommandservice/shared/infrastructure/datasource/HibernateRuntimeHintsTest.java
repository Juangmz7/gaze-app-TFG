package com.app.postcommandservice.shared.infrastructure.datasource;

import java.util.UUID;

import com.app.postcommandservice.post.infrastructure.entity.PostEntity;
import com.app.postcommandservice.report.infrastructure.entity.CommentReportSnapshotPayload;
import com.app.postcommandservice.report.infrastructure.entity.PostReportSnapshotPayload;
import com.app.postcommandservice.shared.infrastructure.entity.OutboxEvent;
import com.app.postcommandservice.shared.infrastructure.outbox.OutboxDestinationResolver;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class HibernateRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    HibernateRuntimeHintsTest() {
        new HibernateRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void shouldLetHibernateInstantiateTheIdArrayOfMultiIdLoading() {
        // MultiIdEntityLoaderArrayParam creates it reflectively while building the session factory
        assertThat(RuntimeHintsPredicates.reflection().onType(UUID[].class)).accepts(hints);
    }

    @Test
    void shouldReadTheIdTypeFromTheEntitiesOnTheClasspath() throws NoSuchFieldException {
        assertThat(PostEntity.class.getDeclaredField("id").getType()).isEqualTo(UUID.class);

        var entities = HibernateRuntimeHints.entities(getClass().getClassLoader());
        assertThat(HibernateRuntimeHints.idTypes(entities)).contains(UUID.class);
    }

    @Test
    void shouldLetHibernateInvokeTheOutboxEntityListener() throws NoSuchMethodException {
        // OutboxEvent's @EntityListeners: Hibernate calls its @PrePersist on every outbox insert
        var assignDestination = OutboxDestinationResolver.class.getDeclaredMethod("assignDestination", OutboxEvent.class);

        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(assignDestination)).accepts(hints);
    }

    @Test
    void shouldLetHibernateSerializeTheJsonColumns() throws NoSuchMethodException {
        // report snapshots are @JdbcTypeCode(SqlTypes.JSON) records, written with Jackson
        assertThat(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(PostReportSnapshotPayload.class.getMethod("description"))).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onMethodInvocation(CommentReportSnapshotPayload.class.getMethod("content"))).accepts(hints);
    }
}
