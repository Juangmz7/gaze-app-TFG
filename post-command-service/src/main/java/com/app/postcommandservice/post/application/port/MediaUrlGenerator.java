package com.app.postcommandservice.post.application.port;

import com.app.postcommandservice.post.domain.model.valueobj.MediaType;

/**
 * Port for generating collision-free Azure Blob URLs for a post media item,
 * before that {@code PostMedia} is constructed (it needs the url up front).
 *
 * <p>Implementations must use a random, unpredictable blob name per blob
 * (never an original file name or any other predictable value), and must derive
 * the URL from configuration (account URL + container), never from a hardcoded
 * host format.</p>
 */
public interface MediaUrlGenerator {

    /**
     * Generates the plain (SAS-less) {@code url}/{@code thumbnailUrl} pair for a media item.
     *
     * <ul>
     *   <li>IMAGE: one random blob is generated; {@code thumbnailUrl} equals {@code url}.</li>
     *   <li>VIDEO: two distinct random blobs are generated, one for content and one for
     *       the thumbnail preview image.</li>
     * </ul>
     */
    GeneratedMediaUrls generate(MediaType mediaType);
}
