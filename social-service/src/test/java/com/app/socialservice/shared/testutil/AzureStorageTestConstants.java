package com.app.socialservice.shared.testutil;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AzureStorageTestConstants {

    public static final String ACCOUNT_NAME = "devstoreaccount1";
    // Azurite's well-known default emulator key (local emulator only, never a real secret).
    public static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    public static final String AZURITE_ACCOUNT_URL = "http://127.0.0.1:10000/" + ACCOUNT_NAME;
    public static final String CLOUD_ACCOUNT_URL = "https://" + ACCOUNT_NAME + ".blob.core.windows.net";
    public static final String CONTAINER = "post-media-test";
    public static final String READ_PERMISSION = "r";
    public static final String UPLOAD_PERMISSION = "cw";
    public static final String BLOB_RESOURCE = "b";

    private AzureStorageTestConstants() {
    }

    /**
     * The query parameters of a URL, still URL-encoded.
     */
    public static Map<String, String> queryParameters(String url) {
        Objects.requireNonNull(url, "url must not be null");
        var queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return Map.of();
        }
        return Arrays.stream(url.substring(queryStart + 1).split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair.length > 1 ? pair[1] : ""));
    }

    public static String decoded(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
