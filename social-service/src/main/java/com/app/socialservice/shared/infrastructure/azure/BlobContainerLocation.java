package com.app.socialservice.shared.infrastructure.azure;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the media container lives (scheme, host, account path of Azurite, container) and how
 * blob URLs of that container look. Boundary-agnostic helper: every input is checked.
 */
public record BlobContainerLocation(String scheme, String host, String basePath, String accountName, String container) {

    private static final Pattern CONTAINER_NAME = Pattern.compile("^[a-z0-9](?!.*--)[a-z0-9-]{1,61}[a-z0-9]$");
    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    public BlobContainerLocation {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(basePath, "basePath must not be null");
        Objects.requireNonNull(accountName, "accountName must not be null");
        Objects.requireNonNull(container, "container must not be null");
        if (!CONTAINER_NAME.matcher(container).matches()) {
            throw new IllegalArgumentException("container is not a valid Azure container name: " + container);
        }
    }

    /**
     * Parses {@code https://<account>.blob.core.windows.net} or the path-style Azurite URL
     * {@code http://<host>:<port>/<account>}.
     */
    public static BlobContainerLocation parse(String accountUrl, String container) {
        if (accountUrl == null || accountUrl.isBlank()) {
            throw new IllegalArgumentException("accountUrl must not be blank");
        }
        var uri = toUri(accountUrl.strip())
                .orElseThrow(() -> new IllegalArgumentException("accountUrl is not a valid URL: " + accountUrl));
        var scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTP.equals(scheme) && !HTTPS.equals(scheme)) {
            throw new IllegalArgumentException("accountUrl must be http or https: " + accountUrl);
        }
        if (uri.getRawAuthority() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("accountUrl must be a bare http(s)://host[/account] URL: " + accountUrl);
        }

        var path = trimSlashes(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (path.contains("/")) {
            throw new IllegalArgumentException("accountUrl may hold at most the account in its path: " + accountUrl);
        }
        var host = uri.getRawAuthority().toLowerCase(Locale.ROOT);
        if (!path.isEmpty()) {
            return new BlobContainerLocation(scheme, host, "/" + path, path, container);
        }
        var accountName = uri.getHost().split("\\.", 2)[0];
        return new BlobContainerLocation(scheme, host, "", accountName, container);
    }

    public boolean isPlainHttp() {
        return HTTP.equals(scheme);
    }

    /**
     * The bare URL of a blob of the container. Never carries a SAS.
     */
    public String blobUrl(String blobName) {
        if (blobName == null || blobName.isBlank() || blobName.startsWith("/") || blobName.endsWith("/")) {
            throw new IllegalArgumentException("blobName must be a non-blank relative blob name");
        }
        return String.format("%s://%s%s/%s/%s", scheme, host, basePath, container, blobName);
    }

    /**
     * The blob name of a bare blob URL of this container (same scheme and host, no query, no
     * fragment). Anything else (null, another host or container, a query already present)
     * is not ours to sign.
     */
    public Optional<String> blobNameOfBareUrl(String url) {
        return toUri(url)
                .filter(uri -> uri.getRawQuery() == null && uri.getRawFragment() == null)
                .filter(uri -> scheme.equalsIgnoreCase(uri.getScheme()))
                .flatMap(this::blobNameOnHost);
    }

    /**
     * The blob name of any URL that points into this container, whatever its scheme and
     * whether or not it carries a query string (such as a SAS). Used to recognise our own
     * blobs among URLs sent by clients.
     */
    public Optional<String> blobNameOfAnyUrl(String url) {
        return toUri(url)
                .filter(uri -> uri.getScheme() != null
                        && (HTTP.equalsIgnoreCase(uri.getScheme()) || HTTPS.equalsIgnoreCase(uri.getScheme())))
                .flatMap(this::blobNameOnHost);
    }

    private Optional<String> blobNameOnHost(URI uri) {
        if (uri.getRawAuthority() == null || uri.getRawUserInfo() != null
                || !host.equalsIgnoreCase(uri.getRawAuthority())) {
            return Optional.empty();
        }
        var path = uri.getPath();
        var prefix = basePath + "/" + container + "/";
        if (path == null || !path.startsWith(prefix)) {
            return Optional.empty();
        }
        var blobName = path.substring(prefix.length());
        if (blobName.isEmpty() || blobName.endsWith("/")) {
            return Optional.empty();
        }
        return Optional.of(blobName);
    }

    private static Optional<URI> toUri(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            var uri = new URI(url);
            return uri.isOpaque() ? Optional.empty() : Optional.of(uri);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    private static String trimSlashes(String path) {
        var start = 0;
        var end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
