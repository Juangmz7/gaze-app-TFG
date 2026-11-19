package com.app.socialservice.shared.infrastructure.azure;

import org.junit.jupiter.api.Test;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.ACCOUNT_NAME;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.AZURITE_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CLOUD_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CONTAINER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobContainerLocationTest {

    private static final String BLOB = "avatars/user/picture.jpg";

    @Test
    void shouldParseAPathStyleAzuriteAccountUrl() {
        var location = BlobContainerLocation.parse(AZURITE_ACCOUNT_URL, CONTAINER);

        assertThat(location.accountName()).isEqualTo(ACCOUNT_NAME);
        assertThat(location.isPlainHttp()).isTrue();
        assertThat(location.blobUrl(BLOB)).isEqualTo(AZURITE_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB);
    }

    @Test
    void shouldParseACloudAccountUrlAndTakeTheAccountFromTheHost() {
        var location = BlobContainerLocation.parse(CLOUD_ACCOUNT_URL + "/", CONTAINER);

        assertThat(location.accountName()).isEqualTo(ACCOUNT_NAME);
        assertThat(location.isPlainHttp()).isFalse();
        assertThat(location.blobUrl(BLOB)).isEqualTo(CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/" + BLOB);
    }

    @Test
    void shouldRejectInvalidAccountUrlsAndContainers() {
        assertThatThrownBy(() -> BlobContainerLocation.parse(null, CONTAINER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobContainerLocation.parse("ftp://host/account", CONTAINER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobContainerLocation.parse(CLOUD_ACCOUNT_URL + "?sv=1", CONTAINER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobContainerLocation.parse(AZURITE_ACCOUNT_URL + "/extra", CONTAINER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, "Bad_Container"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, "a--b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFindTheBlobNameOnlyOfBareUrlsOfTheContainer() {
        var location = BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER);
        var blobUrl = location.blobUrl(BLOB);

        assertThat(location.blobNameOfBareUrl(blobUrl)).hasValue(BLOB);
        assertThat(location.blobNameOfBareUrl(blobUrl.replace(ACCOUNT_NAME, ACCOUNT_NAME.toUpperCase())))
                .hasValue(BLOB);
        assertThat(location.blobNameOfBareUrl(null)).isEmpty();
        assertThat(location.blobNameOfBareUrl("")).isEmpty();
        assertThat(location.blobNameOfBareUrl("not a url")).isEmpty();
        assertThat(location.blobNameOfBareUrl(blobUrl + "?sv=2025")).isEmpty();
        assertThat(location.blobNameOfBareUrl(blobUrl + "#fragment")).isEmpty();
        assertThat(location.blobNameOfBareUrl(blobUrl.replace("https", "http"))).isEmpty();
        assertThat(location.blobNameOfBareUrl("https://other.blob.core.windows.net/" + CONTAINER + "/" + BLOB))
                .isEmpty();
        assertThat(location.blobNameOfBareUrl(CLOUD_ACCOUNT_URL + "/other-container/" + BLOB)).isEmpty();
        assertThat(location.blobNameOfBareUrl(CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/")).isEmpty();
        assertThat(location.blobNameOfBareUrl("https://user@" + ACCOUNT_NAME + ".blob.core.windows.net/"
                + CONTAINER + "/" + BLOB)).isEmpty();
    }

    @Test
    void shouldFindTheBlobNameOfAnyUrlIntoTheContainerWhateverItsSchemeAndQuery() {
        var location = BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER);
        var blobUrl = location.blobUrl(BLOB);

        assertThat(location.blobNameOfAnyUrl(blobUrl + "?sv=2025&sig=abc")).hasValue(BLOB);
        assertThat(location.blobNameOfAnyUrl(blobUrl.replace("https", "http"))).hasValue(BLOB);
        assertThat(location.blobNameOfAnyUrl("https://cdn.example.com/" + CONTAINER + "/" + BLOB)).isEmpty();
        assertThat(location.blobNameOfAnyUrl("ftp://" + ACCOUNT_NAME + ".blob.core.windows.net/" + CONTAINER
                + "/" + BLOB)).isEmpty();
    }
}
