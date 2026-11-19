package com.app.socialservice.shared.application.media;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that read-signs the profile pictures a response returns. Called on the way
 * out, after the (Redis-cached) application result is built, so a signed URL is never cached
 * or stored.
 */
@Component
@RequiredArgsConstructor
public class PictureUrlResponseSigner {

    private final MediaReadUrlSigner mediaReadUrlSigner;

    public <T extends SignablePictureUrl<T>> T sign(T response) {
        Objects.requireNonNull(response, "response must not be null");
        return response.withPictureUrl(mediaReadUrlSigner.signReadUrl(response.pictureUrlToSign()));
    }

    public <T extends SignablePictureUrl<T>> List<T> signAll(List<T> responses) {
        Objects.requireNonNull(responses, "responses must not be null");
        return responses.stream()
                .map(this::sign)
                .toList();
    }
}
