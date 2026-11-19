package com.app.socialservice.shared.application.media;

/**
 * Port: adds a short-lived, read-only SAS to a blob URL of the media container. A null URL
 * stays null and a URL that is not a bare blob URL of the configured container is returned
 * unchanged. The result is only ever returned to a client, never stored or cached.
 */
public interface MediaReadUrlSigner {

    String signReadUrl(String url);
}
