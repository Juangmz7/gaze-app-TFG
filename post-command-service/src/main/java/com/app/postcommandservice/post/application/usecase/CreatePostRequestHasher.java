package com.app.postcommandservice.post.application.usecase;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.app.postcommandservice.post.application.commands.CreatePostCommand;
import com.app.postcommandservice.post.application.commands.PostMediaCommand;
import com.app.postcommandservice.post.domain.model.valueobj.PostType;

/**
 * Hashes the portion of {@link CreatePostCommand} that defines the create-post operation, so a
 * replayed (userId, correlationId) pair can be checked against the payload that originally
 * created it. Package-private and static: this is a pure function kept outside
 * {@link CreatePostUseCase} only so it can be unit-tested without instantiating the use case and
 * its mocks, not a general-purpose abstraction.
 *
 * <p>Every field is length-prefixed before hashing (rather than separator-joined) because
 * {@code description} is free text that could contain any character. Fields are hashed using the
 * exact same normalization persistence already applies at create time (see
 * {@code CreatePostUseCase#persistPendingPost}/the legacy overload): {@code description} defaults
 * to {@code ""} when {@code null}, {@code postType} defaults to {@code BASIC} when {@code null},
 * and tag/tagged-user sets are trimmed exactly as {@code PostTags}/{@code PostTaggedUsers} do (no
 * case change). Collections are sorted first so hashing is independent of client-supplied
 * insertion order.
 */
final class CreatePostRequestHasher {

    private CreatePostRequestHasher() {
    }

    static String hash(CreatePostCommand command) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);

            writeField(data, command.collabId() == null ? "" : command.collabId().toString());
            writeField(data, resolvePostType(command.postType()).name());
            writeField(data, command.description() == null ? "" : command.description());

            for (String tag : sorted(command.postTags())) {
                writeField(data, tag);
            }

            List<PostMediaCommand> media = command.media() == null ? List.of() : command.media();
            List<PostMediaCommand> orderedMedia = media.stream()
                    .sorted(Comparator.comparingInt(PostMediaCommand::order))
                    .toList();
            for (PostMediaCommand mediaCommand : orderedMedia) {
                writeField(data, mediaCommand.mediaType() == null ? "" : mediaCommand.mediaType().name());
                writeField(data, String.valueOf(mediaCommand.order()));
                for (String taggedUser : sorted(mediaCommand.taggedUsers())) {
                    writeField(data, taggedUser);
                }
            }

            data.flush();
            byte[] hashed = digest.digest(out.toByteArray());
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void writeField(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    /**
     * Sorts a tag/tagged-user set for hashing, trimming each value first to match the exact
     * normalization {@code PostTags}/{@code PostTaggedUsers} apply at persistence time (no case
     * change, since nothing in this codebase lowercases these values today).
     */
    private static Set<String> sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> trimmed = new TreeSet<>();
        for (String value : values) {
            trimmed.add(value == null ? "" : value.trim());
        }
        return trimmed;
    }

    private static PostType resolvePostType(PostType postType) {
        return postType == null ? PostType.BASIC : postType;
    }
}
