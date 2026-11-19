package com.app.socialservice.user.infrastructure.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The profile picture URL fits the stored column once a SAS query of the media container is
 * removed: the app sends back the read-signed URL it received, which is longer than what is
 * stored.
 */
@Documented
@Constraint(validatedBy = StorableProfilePictureUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StorableProfilePictureUrl {

    String message() default "profilePicture must not exceed 255 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
