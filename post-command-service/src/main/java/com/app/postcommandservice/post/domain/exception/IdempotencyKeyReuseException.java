package com.app.postcommandservice.post.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

/**
 * Thrown when a request's correlation id was already used to create a post but the
 * resolved idempotency record does not actually correspond to this request (either because
 * it belongs to a different requester, or because the stored request hash does not match the
 * current payload). The message is intentionally generic: callers outside this service must
 * not be able to distinguish ownership mismatches from payload mismatches.
 */
public class IdempotencyKeyReuseException extends DomainException {

    public IdempotencyKeyReuseException() {
        super("This correlation id was already used for a different request");
    }
}
