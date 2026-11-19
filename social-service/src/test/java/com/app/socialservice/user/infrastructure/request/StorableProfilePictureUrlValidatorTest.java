package com.app.socialservice.user.infrastructure.request;

import org.junit.jupiter.api.Test;

import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;

import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CLOUD_ACCOUNT_URL;
import static com.app.socialservice.shared.testutil.AzureStorageTestConstants.CONTAINER;
import static org.assertj.core.api.Assertions.assertThat;

class StorableProfilePictureUrlValidatorTest {

    private final StorableProfilePictureUrlValidator validator = new StorableProfilePictureUrlValidator(
            BlobContainerLocation.parse(CLOUD_ACCOUNT_URL, CONTAINER));

    @Test
    void shouldAcceptAMissingPicture() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void shouldMeasureAForeignUrlAsSent() {
        var fits = "https://cdn.example.com/" + "a".repeat(ProfilePictureUrl.MAX_LENGTH - 24);
        var tooLong = fits + "a";

        assertThat(validator.isValid(fits, null)).isTrue();
        assertThat(validator.isValid(tooLong, null)).isFalse();
    }

    @Test
    void shouldMeasureAContainerUrlWithoutItsSasQuery() {
        var bare = CLOUD_ACCOUNT_URL + "/" + CONTAINER + "/avatars/0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11/"
                + "7c9e6679-7425-40de-944b-e07fc1f90ae7.jpg";
        var signed = bare + "?sv=2025-01-05&sig=" + "x".repeat(400);

        assertThat(validator.isValid(signed, null)).isTrue();
        var overlong = bare + "?sig=" + "x".repeat(StorableProfilePictureUrlValidator.MAX_SIGNED_URL_LENGTH);
        assertThat(validator.isValid(overlong, null)).isFalse();
    }
}
