package com.app.socialservice.user.infrastructure.request;

import java.util.Objects;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.app.socialservice.shared.infrastructure.azure.BlobContainerLocation;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;

public class StorableProfilePictureUrlValidator implements ConstraintValidator<StorableProfilePictureUrl, String> {

    /**
     * Upper bound for a signed URL of the media container sent back by the app.
     */
    static final int MAX_SIGNED_URL_LENGTH = 2048;

    private final BlobContainerLocation blobContainerLocation;

    public StorableProfilePictureUrlValidator(BlobContainerLocation blobContainerLocation) {
        this.blobContainerLocation = Objects.requireNonNull(
                blobContainerLocation, "blobContainerLocation must not be null");
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() > MAX_SIGNED_URL_LENGTH) {
            return false;
        }
        var storedLength = blobContainerLocation.blobNameOfAnyUrl(value).isPresent()
                ? withoutQueryAndFragment(value).length()
                : value.length();
        return storedLength <= ProfilePictureUrl.MAX_LENGTH;
    }

    private static String withoutQueryAndFragment(String value) {
        var end = value.length();
        var query = value.indexOf('?');
        var fragment = value.indexOf('#');
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return value.substring(0, end);
    }
}
