package com.app.postcommandservice.collab.domain.exception;

import com.app.postcommandservice.shared.domain.exception.DomainException;

public class CollabJoinInstructionsTooLongException extends DomainException {

    public CollabJoinInstructionsTooLongException(int maxLength) {
        super("JOIN_INSTRUCTIONS_TOO_LONG: join instructions must be lower than or equal to %d characters"
                .formatted(maxLength));
    }
}
