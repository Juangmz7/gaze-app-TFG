package com.app.socialservice.shared.application.media;

/**
 * A response that carries one profile picture URL to read-sign before it is returned.
 *
 * @param <T> the response type itself
 */
public interface SignablePictureUrl<T extends SignablePictureUrl<T>> {

    String pictureUrlToSign();

    T withPictureUrl(String pictureUrl);
}
