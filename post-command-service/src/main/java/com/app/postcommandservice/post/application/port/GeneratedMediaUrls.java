package com.app.postcommandservice.post.application.port;

/**
 * Plain blob URLs (no SAS) produced for a single {@code PostMedia} item by
 * {@link MediaUrlGenerator}. For IMAGE media, {@code url} and {@code thumbnailUrl}
 * are the same blob. For VIDEO media, they are two distinct blobs.
 */
public record GeneratedMediaUrls(String url, String thumbnailUrl) {
}
