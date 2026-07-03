package com.app.socialservice.user.infrastructure.request;

import java.util.Map;

import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOwnUserProfileRequest(
        @Size(max = UserBio.MAX_DESCRIPTION_LENGTH) String description,
        @Size(max = ProfilePictureUrl.MAX_LENGTH) String profilePicture,
        Map<@NotBlank String, @NotBlank String> socialMedia
) {
}
