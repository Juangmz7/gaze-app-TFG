package com.app.socialservice.user.domain.model.valueobj;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.socialservice.user.domain.enums.ProfilePictureContentType;
import com.app.socialservice.user.domain.exception.InvalidProfilePictureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfilePictureBlobNameTest {

    private static final UserId OWNER = new UserId(UUID.fromString("0b6e7b52-1f0e-4a43-9a52-7d4b1b0d3c11"));
    private static final UUID OTHER_USER = UUID.fromString("9a0c3f1d-5b2e-4c7a-8d6f-1e2b3c4d5e6f");
    private static final String PICTURE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String OWN_FOLDER = "avatars/" + OWNER.value() + "/";

    @Test
    void shouldGenerateTheBlobNameInTheOwnersAvatarsFolderWithTheTypesExtension() {
        assertThat(ProfilePictureBlobName.generate(OWNER, ProfilePictureContentType.JPEG).value())
                .matches("avatars/" + OWNER.value() + "/[0-9a-f-]{36}\\.jpg");
        assertThat(ProfilePictureBlobName.generate(OWNER, ProfilePictureContentType.WEBP).value())
                .endsWith(".webp");
        assertThat(ProfilePictureBlobName.generate(OWNER, ProfilePictureContentType.PNG).value())
                .isNotEqualTo(ProfilePictureBlobName.generate(OWNER, ProfilePictureContentType.PNG).value());
    }

    @Test
    void shouldAcceptABlobOfTheCallersOwnFolder() {
        var name = "avatars/" + OWNER.value() + "/" + PICTURE + ".png";

        assertThat(ProfilePictureBlobName.ownedBy(OWNER, name).value()).isEqualTo(name);
    }

    @Test
    void shouldRejectAProfilePictureOutsideTheCallersFolder() {
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(
                OWNER, "avatars/" + OTHER_USER + "/" + PICTURE + ".jpg"))
                .isInstanceOf(InvalidProfilePictureException.class);
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(OWNER,
                "avatars/" + OWNER.value() + "/../" + OTHER_USER + "/" + PICTURE + ".jpg"))
                .isInstanceOf(InvalidProfilePictureException.class);
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(OWNER, PICTURE + ".jpg"))
                .isInstanceOf(InvalidProfilePictureException.class);
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(OWNER, OWN_FOLDER + PICTURE + ".gif"))
                .isInstanceOf(InvalidProfilePictureException.class);
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(OWNER, OWN_FOLDER + "nested/" + PICTURE + ".jpg"))
                .isInstanceOf(InvalidProfilePictureException.class);
        assertThatThrownBy(() -> ProfilePictureBlobName.ownedBy(OWNER, null))
                .isInstanceOf(InvalidProfilePictureException.class);
    }
}
