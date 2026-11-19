package com.app.socialservice.shared.infrastructure.azure.sas;

public class SasSigningKeyUnavailableException extends RuntimeException {

    public SasSigningKeyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
