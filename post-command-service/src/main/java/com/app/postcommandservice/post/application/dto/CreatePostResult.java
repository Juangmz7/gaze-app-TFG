package com.app.postcommandservice.post.application.dto;

/**
 * Outcome of the metadata-only single-post creation flow (task 33). {@code created}
 * distinguishes a brand-new {@code PENDING} post ({@code true}) from an idempotent replay of
 * an already-existing post for the same correlation id ({@code false}), so the HTTP layer can
 * return {@code 201 Created} versus {@code 200 OK} accordingly, mirroring this service's
 * existing idempotent-request-returns-200 precedent.
 */
public record CreatePostResult(PostResponse response, boolean created) {
}
