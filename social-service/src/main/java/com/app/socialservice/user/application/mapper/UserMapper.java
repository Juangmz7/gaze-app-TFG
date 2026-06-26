package com.app.socialservice.user.application.mapper;

import com.app.socialservice.user.domain.model.User;
import com.app.socialservice.user.domain.model.valueobj.Email;
import com.app.socialservice.user.domain.model.valueobj.ProfilePictureUrl;
import com.app.socialservice.user.domain.model.valueobj.UserBio;
import com.app.socialservice.user.domain.model.valueobj.UserId;
import com.app.socialservice.user.domain.model.valueobj.Username;
import com.app.socialservice.user.infrastructure.entity.UserBioEmbeddable;
import com.app.socialservice.user.infrastructure.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", source = "id.value")
    @Mapping(target = "username", source = "username.value")
    @Mapping(target = "email", source = "email.value")
    @Mapping(target = "pictureUrl", source = "pictureUrl.value")
    @Mapping(target = "bio", source = "bio")
    @Mapping(target = "version", ignore = true)
    UserEntity toEntity(User domain);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "username", source = "username")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "pictureUrl", source = "pictureUrl")
    @Mapping(target = "bio", source = "bio")
    User toDomain(UserEntity entity);

    default UserId toUserId(UUID id) {
        return id != null ? new UserId(id) : null;
    }

    default Username toUsername(String username) {
        return username != null ? new Username(username) : null;
    }

    default Email toEmail(String email) {
        return email != null ? new Email(email) : null;
    }

    default ProfilePictureUrl toProfilePictureUrl(String url) {
        return url != null ? new ProfilePictureUrl(url) : null;
    }

    default UserBioEmbeddable toUserBioEmbeddable(UserBio bio) {
        if (bio == null) {
            return null;
        }
        return UserBioEmbeddable.builder()
                .description(bio.description())
                .socialMedia(copySocialMedia(bio.socialMedia()))
                .build();
    }

    default UserBio toUserBio(UserBioEmbeddable bio) {
        if (bio == null) {
            return null;
        }
        return new UserBio(
                bio.getDescription(),
                copySocialMedia(bio.getSocialMedia())
        );
    }

    private static Map<String, String> copySocialMedia(Map<String, String> socialMedia) {
        return socialMedia == null ? Map.of() : Map.copyOf(socialMedia);
    }
}
