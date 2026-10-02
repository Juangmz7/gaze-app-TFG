package com.app.postcommandservice.post.application.usecase;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.domain.model.valueobj.MediaType;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePostRequestHasherTest {

    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void shouldProduceTheSameHashRegardlessOfTagInsertionOrder() {
        var firstOrder = new LinkedHashSet<String>();
        firstOrder.add("java");
        firstOrder.add("spring");

        var secondOrder = new LinkedHashSet<String>();
        secondOrder.add("spring");
        secondOrder.add("java");

        var firstCommand = command(firstOrder, defaultMedia(Set.of()));
        var secondCommand = command(secondOrder, defaultMedia(Set.of()));

        assertThat(CreatePostRequestHasher.hash(firstCommand))
                .isEqualTo(CreatePostRequestHasher.hash(secondCommand));
    }

    @Test
    void shouldProduceTheSameHashRegardlessOfTaggedUserInsertionOrderWithinMedia() {
        var firstOrder = new LinkedHashSet<String>();
        firstOrder.add("alice");
        firstOrder.add("bob");

        var secondOrder = new LinkedHashSet<String>();
        secondOrder.add("bob");
        secondOrder.add("alice");

        var firstCommand = command(Set.of("java"), defaultMedia(firstOrder));
        var secondCommand = command(Set.of("java"), defaultMedia(secondOrder));

        assertThat(CreatePostRequestHasher.hash(firstCommand))
                .isEqualTo(CreatePostRequestHasher.hash(secondCommand));
    }

    @Test
    void shouldProduceDifferentHashesForDifferentDescriptions() {
        var first = command("hello", Set.of("java"), defaultMedia(Set.of()));
        var second = command("goodbye", Set.of("java"), defaultMedia(Set.of()));

        assertThat(CreatePostRequestHasher.hash(first)).isNotEqualTo(CreatePostRequestHasher.hash(second));
    }

    @Test
    void shouldProduceDifferentHashesForDifferentMediaOrder() {
        var first = command(Set.of("java"), List.of(
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 1),
                new PostMediaCommand(null, null, MediaType.VIDEO, null, Set.of(), 2)
        ));
        var second = command(Set.of("java"), List.of(
                new PostMediaCommand(null, null, MediaType.VIDEO, null, Set.of(), 1),
                new PostMediaCommand(null, null, MediaType.IMAGE, null, Set.of(), 2)
        ));

        assertThat(CreatePostRequestHasher.hash(first)).isNotEqualTo(CreatePostRequestHasher.hash(second));
    }

    @Test
    void shouldReturnSixtyFourCharacterLowercaseHexDigest() {
        var hash = CreatePostRequestHasher.hash(command(Set.of("java"), defaultMedia(Set.of())));

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    private CreatePostCommand command(Set<String> postTags, List<PostMediaCommand> media) {
        return command("hello", postTags, media);
    }

    private CreatePostCommand command(String description, Set<String> postTags, List<PostMediaCommand> media) {
        return new CreatePostCommand(CORRELATION_ID, USER_ID, null, PostType.BASIC, description, postTags, media);
    }

    private List<PostMediaCommand> defaultMedia(Set<String> taggedUsers) {
        return List.of(new PostMediaCommand(null, null, MediaType.IMAGE, null, taggedUsers, 1));
    }
}
